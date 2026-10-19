package ru.hrp.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import ru.hrp.core.HrpProfileStorage.FinanceAccount;
import ru.hrp.core.HrpProfileStorage.FinanceOperation;
import ru.hrp.core.HrpProfileStorage.RoleOperationResult;
import ru.hrp.core.HrpProfileStorage.StoredProfile;
import ru.hrp.core.network.HrpNetwork;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns active profile state, validates RP edits, and serializes SQLite access off-thread. */
public final class HrpProfileService {
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;
    private static final long STARTING_CASH_CENTS = 100_000L; // 1,000.00 ₽ for a newly created wallet.
    private static final long MAX_TRANSACTION_CENTS = 10_000_000_000L;
    private static final int FINANCE_HISTORY_LIMIT = 6;

    private HrpProfileService() {}

    private record Profile(
            String name,
            String rpName,
            String birthDate,
            String biography,
            long firstJoin,
            long playTimeTicks
    ) {
        private Profile ticked() {
            return new Profile(name, rpName, birthDate, biography, firstJoin, playTimeTicks + 1);
        }

        private StoredProfile toStored(UUID uuid) {
            return new StoredProfile(uuid, name, rpName, birthDate, biography, firstJoin, playTimeTicks);
        }

        private Profile withCharacterDetails(String newRpName, String newBirthDate, String newBiography) {
            return new Profile(name, newRpName, newBirthDate, newBiography, firstJoin, playTimeTicks);
        }
    }

    private static final Map<UUID, Profile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<UUID, HrpRole> ROLES = new ConcurrentHashMap<>();
    private static volatile HrpProfileStorage storage;
    private static final ExecutorService STORAGE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HRPCore-ProfileStorage");
        thread.setDaemon(true);
        return thread;
    });
    private static long ticksSinceLastSave;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PROFILES.clear();
            ROLES.clear();
            ticksSinceLastSave = 0;

            Path databasePath = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data")
                    .resolve("hrpcore")
                    .resolve("profiles.db");

            STORAGE_EXECUTOR.execute(() -> {
                try {
                    HrpProfileStorage previous = storage;
                    if (previous != null) previous.close();
                    storage = new HrpProfileStorage(databasePath);
                    HRPCore.LOGGER.info("HRPCore profile database ready: {}", databasePath);
                } catch (Exception exception) {
                    storage = null;
                    HRPCore.LOGGER.error("Could not open HRPCore profile database at {}", databasePath, exception);
                }
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                loadProfileAsync(server, handler.player, false));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            Profile profile = PROFILES.remove(uuid);
            ROLES.remove(uuid);
            if (profile != null) {
                StoredProfile snapshot = profile.toStored(uuid);
                STORAGE_EXECUTOR.execute(() -> saveOne(snapshot));
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PROFILES.computeIfPresent(player.getUUID(), (uuid, profile) -> profile.ticked());
            }

            ticksSinceLastSave++;
            if (ticksSinceLastSave >= SAVE_INTERVAL_TICKS) {
                ticksSinceLastSave = 0;
                saveSnapshotAsync(snapshotProfiles());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.RequestProfile.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    Profile profile = PROFILES.get(player.getUUID());
                    if (profile != null) sendProfile(player, profile);
                    else loadProfileAsync(context.server(), player, true);
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.SaveCharacterProfile.TYPE,
                (payload, context) -> context.server().execute(() ->
                        saveCharacterProfile(context.server(), context.player(), payload))
        );

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.RequestFinance.TYPE,
                (payload, context) -> context.server().execute(() ->
                        sendFinanceSnapshotAsync(context.server(), context.player(), true, ""))
        );

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.FinanceAction.TYPE,
                (payload, context) -> context.server().execute(() ->
                        handleFinanceAction(context.server(), context.player(), payload))
        );

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushAndClose());
    }

    private static void loadProfileAsync(MinecraftServer server, ServerPlayer player, boolean respondWhenLoaded) {
        UUID uuid = player.getUUID();
        String playerName = player.getName().getString();
        long nowMillis = System.currentTimeMillis();

        STORAGE_EXECUTOR.execute(() -> {
            StoredProfile loaded;
            HrpRole loadedRole = null;
            HrpProfileStorage repository = storage;
            try {
                if (repository == null) throw new IllegalStateException("Profile database is not available");
                loaded = repository.loadOrCreate(uuid, playerName, nowMillis);
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not load profile for {} ({})", playerName, uuid, exception);
                loaded = new StoredProfile(uuid, playerName, playerName, "", "", nowMillis, 0);
            }

            // Finance & Role initialization are independent from profile loading.
            if (repository != null) {
                try {
                    repository.loadFinanceAccount(uuid, STARTING_CASH_CENTS, nowMillis);
                } catch (Exception exception) {
                    HRPCore.LOGGER.error("Could not initialize finance account for {} ({})", playerName, uuid, exception);
                }
                try {
                    loadedRole = repository.loadOrCreateRole(uuid, nowMillis);
                } catch (Exception exception) {
                    HRPCore.LOGGER.error("Could not initialize player role for {} ({})", playerName, uuid, exception);
                }
            }

            StoredProfile result = loaded;
            HrpRole resultRole = loadedRole;
            server.execute(() -> {
                if (player.isRemoved()) return;

                Profile profile = PROFILES.compute(uuid, (ignored, current) -> {
                    if (current == null) {
                        return new Profile(result.name(), result.rpName(), result.birthDate(), result.biography(),
                                result.firstJoin(), result.playTimeTicks());
                    }
                    return new Profile(result.name(), current.rpName(), current.birthDate(), current.biography(),
                            Math.min(current.firstJoin(), result.firstJoin()),
                            Math.max(current.playTimeTicks(), result.playTimeTicks()));
                });

                if (resultRole != null) {
                    ROLES.putIfAbsent(uuid, resultRole);
                }

                if (respondWhenLoaded) sendProfile(player, profile);
            });
        });
    }

    private static void saveCharacterProfile(
            MinecraftServer server,
            ServerPlayer player,
            HrpNetwork.SaveCharacterProfile payload
    ) {
        UUID uuid = player.getUUID();
        Profile previous = PROFILES.get(uuid);
        if (previous == null) {
            sendUpdateResult(player, false, "Профиль ещё загружается. Попробуй сохранить ещё раз через секунду.");
            loadProfileAsync(server, player, true);
            return;
        }

        String rpName = payload.rpName() == null ? "" : payload.rpName().trim();
        String birthDate = payload.birthDate() == null ? "" : payload.birthDate().trim();
        String biography = payload.biography() == null ? "" : payload.biography().trim();
        String validationError = validateCharacterDetails(rpName, birthDate, biography);
        if (validationError != null) {
            sendUpdateResult(player, false, validationError);
            return;
        }

        Profile changed = previous.withCharacterDetails(rpName, birthDate, biography);
        PROFILES.put(uuid, changed);

        STORAGE_EXECUTOR.execute(() -> {
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("База профилей пока недоступна.");
                repository.updateCharacterProfile(uuid, rpName, birthDate, biography);

                server.execute(() -> {
                    if (player.isRemoved()) return;
                    Profile latest = PROFILES.get(uuid);
                    if (latest != null) sendProfile(player, latest);
                    sendUpdateResult(player, true, "Данные персонажа сохранены.");
                });
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not save RP details for {} ({})", previous.name(), uuid, exception);
                server.execute(() -> {
                    PROFILES.computeIfPresent(uuid, (ignored, latest) -> {
                        if (latest.rpName().equals(rpName)
                                && latest.birthDate().equals(birthDate)
                                && latest.biography().equals(biography)) {
                            return latest.withCharacterDetails(previous.rpName(), previous.birthDate(), previous.biography());
                        }
                        return latest;
                    });
                    if (!player.isRemoved()) sendUpdateResult(player, false,
                            "Не удалось сохранить данные. Проверь лог сервера.");
                });
            }
        });
    }

    /** Validates incoming finance requests before any database operation. */
    private static void handleFinanceAction(MinecraftServer server, ServerPlayer player,
                                            HrpNetwork.FinanceAction payload) {
        int action = payload.action();
        long amountCents = payload.amountCents();
        String targetName = payload.targetName() == null ? "" : payload.targetName().trim();

        if (amountCents <= 0 || amountCents > MAX_TRANSACTION_CENTS) {
            sendFinanceSnapshotAsync(server, player, false, "Сумма должна быть больше нуля и не превышать 100 000 000 ₽.");
            return;
        }
        if (action < 0 || action > 2) {
            sendFinanceSnapshotAsync(server, player, false, "Неизвестная финансовая операция.");
            return;
        }
        if (action == 2 && !targetName.matches("[A-Za-z0-9_]{3,16}")) {
            sendFinanceSnapshotAsync(server, player, false, "Укажи корректный ник Minecraft (3–16 символов).");
            return;
        }

        UUID playerUuid = player.getUUID();
        long nowMillis = System.currentTimeMillis();
        STORAGE_EXECUTOR.execute(() -> {
            FinanceOperation operation;
            FinanceAccount account;
            List<String> history;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Finance database is not available");
                repository.loadFinanceAccount(playerUuid, STARTING_CASH_CENTS, nowMillis);
                operation = switch (action) {
                    case 0 -> repository.deposit(playerUuid, amountCents, nowMillis);
                    case 1 -> repository.withdraw(playerUuid, amountCents, nowMillis);
                    case 2 -> repository.transferCash(playerUuid, targetName, amountCents, nowMillis);
                    default -> new FinanceOperation(false, "Неизвестная финансовая операция.", null);
                };
                account = repository.loadFinanceAccount(playerUuid, STARTING_CASH_CENTS, nowMillis);
                history = repository.loadFinanceHistory(playerUuid, FINANCE_HISTORY_LIMIT);
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not perform finance action for {}", playerUuid, exception);
                operation = new FinanceOperation(false, "Ошибка финансовой операции. Проверь лог сервера.", null);
                account = new FinanceAccount(playerUuid, 0, 0);
                history = List.of();
            }

            FinanceOperation result = operation;
            FinanceAccount accountResult = account;
            List<String> historyResult = history;
            server.execute(() -> {
                if (player.isRemoved()) return;
                ServerPlayNetworking.send(player, new HrpNetwork.FinanceResponse(
                        accountResult.cashCents(), accountResult.bankCents(), historyResult,
                        result.success(), result.message()));
                if (result.success() && result.counterpartyUuid() != null) {
                    ServerPlayer recipient = server.getPlayerList().getPlayer(result.counterpartyUuid());
                    if (recipient != null) sendFinanceSnapshotAsync(server, recipient, true, "Получен перевод от "
                            + player.getName().getString() + ".");
                }
            });
        });
    }

    private static void sendFinanceSnapshotAsync(MinecraftServer server, ServerPlayer player,
                                                 boolean success, String message) {
        UUID uuid = player.getUUID();
        String playerName = player.getName().getString();
        long nowMillis = System.currentTimeMillis();
        STORAGE_EXECUTOR.execute(() -> {
            FinanceAccount account;
            List<String> history;
            boolean finalSuccess = success;
            String finalMessage = message == null ? "" : message;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Finance database is not available");
                // Profile rows are required by the account foreign key; first join creates them before this request.
                account = repository.loadFinanceAccount(uuid, STARTING_CASH_CENTS, nowMillis);
                history = repository.loadFinanceHistory(uuid, FINANCE_HISTORY_LIMIT);
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not load finance data for {} ({})", playerName, uuid, exception);
                account = new FinanceAccount(uuid, 0, 0);
                history = List.of();
                finalSuccess = false;
                if (finalMessage.isBlank()) finalMessage = "Не удалось загрузить финансовые данные. Проверь лог сервера.";
            }
            HrpNetwork.FinanceResponse response = new HrpNetwork.FinanceResponse(
                    account.cashCents(), account.bankCents(), history, finalSuccess, finalMessage);
            server.execute(() -> {
                if (!player.isRemoved()) ServerPlayNetworking.send(player, response);
            });
        });
    }

    /** Displays current sender player's role. */
    public static void showOwnRole(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Эта команда предназначена только для игроков."));
            return;
        }

        UUID uuid = player.getUUID();
        HrpRole cached = ROLES.get(uuid);
        if (cached != null) {
            source.sendSuccess(() -> Component.literal("Ваша текущая роль: " + cached.getFormatted() + "."), false);
            return;
        }

        MinecraftServer server = source.getServer();
        STORAGE_EXECUTOR.execute(() -> {
            HrpRole role = null;
            try {
                HrpProfileStorage repository = storage;
                if (repository != null) {
                    role = repository.loadOrCreateRole(uuid, System.currentTimeMillis());
                }
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not load role for {}", uuid, exception);
            }
            HrpRole resultRole = role;
            server.execute(() -> {
                if (player.isRemoved()) return;
                if (resultRole != null) {
                    HrpRole effectiveRole = ROLES.computeIfAbsent(uuid, k -> resultRole);
                    source.sendSuccess(() -> Component.literal("Ваша текущая роль: " + effectiveRole.getFormatted() + "."), false);
                } else {
                    source.sendFailure(Component.literal("Не удалось загрузить данные о вашей роли. Проверьте лог сервера."));
                }
            });
        });
    }

    /** Queries target player's role (online or offline). */
    public static void adminGetRole(CommandSourceStack source, String targetName) {
        if (targetName == null || !targetName.matches("[A-Za-z0-9_]{3,16}")) {
            source.sendFailure(Component.literal("Укажи корректный ник Minecraft (3–16 символов)."));
            return;
        }

        MinecraftServer server = source.getServer();
        STORAGE_EXECUTOR.execute(() -> {
            boolean success = false;
            String message;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Profile database is not available");
                HrpProfileStorage.KnownPlayer target = repository.findKnownPlayerByName(targetName);
                if (target == null) {
                    message = "Игрок не найден: " + targetName + ". Он должен хотя бы раз зайти на сервер.";
                } else {
                    HrpRole role = repository.getPlayerRole(target.uuid());
                    if (role == null) {
                        message = "Роль игрока " + target.name() + " не найдена в базе данных.";
                    } else {
                        message = "Текущая роль игрока " + target.name() + ": " + role.getFormatted() + ".";
                        success = true;
                    }
                }
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Administrator role query failed for target {}", targetName, exception);
                message = "Ошибка базы данных. Проверь logs/latest.log.";
            }

            boolean finalSuccess = success;
            String finalMessage = message;
            server.execute(() -> sendAdminCommandResult(source, finalSuccess, finalMessage));
        });
    }

    /** Sets target player's role (online or offline), updates audit log and in-memory cache if online. */
    public static void adminSetRole(CommandSourceStack source, String targetName, String roleInput, String reason) {
        if (targetName == null || !targetName.matches("[A-Za-z0-9_]{3,16}")) {
            source.sendFailure(Component.literal("Укажи корректный ник Minecraft (3–16 символов)."));
            return;
        }

        HrpRole role = HrpRole.parse(roleInput);
        if (role == null) {
            source.sendFailure(Component.literal("Некорректная роль: '" + roleInput + "'. Допустимые роли: CITIZEN, POLICE, MEDIC, GOVERNMENT, BANKER."));
            return;
        }

        String actorName = source.getEntity() instanceof ServerPlayer actor
                ? actor.getName().getString()
                : "CONSOLE";
        String safeReason = sanitizeAdminReason(reason);
        MinecraftServer server = source.getServer();

        STORAGE_EXECUTOR.execute(() -> {
            boolean success = false;
            String message;
            UUID affectedUuid = null;
            HrpRole newRole = null;

            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Profile database is not available");
                HrpProfileStorage.KnownPlayer target = repository.findKnownPlayerByName(targetName);
                if (target == null) {
                    message = "Игрок не найден: " + targetName + ". Он должен хотя бы раз зайти на сервер.";
                } else {
                    RoleOperationResult operation = repository.setPlayerRole(
                            target.uuid(), role, actorName, safeReason, System.currentTimeMillis());
                    success = operation.success();
                    message = operation.message();
                    affectedUuid = target.uuid();
                    newRole = operation.newRole();
                }
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Administrator role change failed for target {}", targetName, exception);
                message = "Ошибка базы данных. Проверь logs/latest.log.";
            }

            boolean finalSuccess = success;
            String finalMessage = message;
            UUID finalUuid = affectedUuid;
            HrpRole finalRole = newRole;

            server.execute(() -> {
                sendAdminCommandResult(source, finalSuccess, finalMessage);
                if (finalSuccess && finalUuid != null && finalRole != null) {
                    ROLES.computeIfPresent(finalUuid, (k, v) -> finalRole);
                    ServerPlayer onlineTarget = server.getPlayerList().getPlayer(finalUuid);
                    if (onlineTarget != null) {
                        ROLES.put(finalUuid, finalRole);
                        onlineTarget.sendSystemMessage(Component.literal(
                                "Администратор назначил вам новую роль: " + finalRole.getFormatted() + "."));
                    }
                }
            });
        });
    }

    /** Prints an offline-safe account balance to an operator; storage work stays off the server thread. */
    public static void adminBalance(CommandSourceStack source, String targetName) {
        executeAdminLookup(source, targetName, false);
    }

    /** Prints the target player's latest account transactions to an operator. */
    public static void adminHistory(CommandSourceStack source, String targetName) {
        executeAdminLookup(source, targetName, true);
    }

    /** Audited administrator adjustment of either cash or bank funds. Amounts are exact rubles, never floating point. */
    public static void adminAdjustBalance(CommandSourceStack source, String targetName,
                                          String amountText, boolean give, boolean bankAccount, String reason) {
        if (targetName == null || !targetName.matches("[A-Za-z0-9_]{3,16}")) {
            source.sendFailure(Component.literal("Укажи корректный ник Minecraft (3–16 символов)."));
            return;
        }

        final long amountCents;
        try {
            if (amountText == null || !amountText.matches("[0-9]+(?:[.,][0-9]{1,2})?")) {
                throw new IllegalArgumentException();
            }
            BigDecimal amount = new BigDecimal(amountText.replace(',', '.'))
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000.00")) > 0) {
                source.sendFailure(Component.literal("Сумма должна быть больше нуля и не превышать 100 000 000,00 ₽."));
                return;
            }
            amountCents = amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Некорректная сумма. Пример: 125 или 125,50. Максимум — 100 000 000,00 ₽."));
            return;
        }

        String actorName = source.getEntity() instanceof ServerPlayer actor
                ? actor.getName().getString()
                : "CONSOLE";
        String safeReason = sanitizeAdminReason(reason);
        MinecraftServer server = source.getServer();

        STORAGE_EXECUTOR.execute(() -> {
            boolean success = false;
            String message;
            UUID affectedUuid = null;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Finance database is not available");
                HrpProfileStorage.KnownPlayer target = repository.findKnownPlayerByName(targetName);
                if (target == null) {
                    message = "Игрок не найден: " + targetName + ". Он должен хотя бы раз зайти на сервер.";
                } else {
                    FinanceOperation operation = repository.adminAdjustBalance(
                            target.uuid(), amountCents, give, bankAccount, actorName, safeReason,
                            System.currentTimeMillis());
                    success = operation.success();
                    message = operation.message();
                    affectedUuid = target.uuid();
                }
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Administrator economy adjustment failed for target {}", targetName, exception);
                message = "Ошибка базы данных. Проверь logs/latest.log.";
            }

            boolean resultSuccess = success;
            String resultMessage = message;
            UUID resultUuid = affectedUuid;
            server.execute(() -> {
                sendAdminCommandResult(source, resultSuccess, resultMessage);
                if (resultSuccess && resultUuid != null) {
                    ServerPlayer onlineTarget = server.getPlayerList().getPlayer(resultUuid);
                    if (onlineTarget != null) {
                        sendFinanceSnapshotAsync(server, onlineTarget, true,
                                "Администратор изменил баланс вашего счёта.");
                    }
                }
            });
        });
    }

    private static void executeAdminLookup(CommandSourceStack source, String targetName, boolean history) {
        if (targetName == null || !targetName.matches("[A-Za-z0-9_]{3,16}")) {
            source.sendFailure(Component.literal("Укажи корректный ник Minecraft (3–16 символов)."));
            return;
        }

        MinecraftServer server = source.getServer();
        STORAGE_EXECUTOR.execute(() -> {
            boolean success = false;
            String message = "";
            List<String> historyLines = List.of();
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Finance database is not available");
                HrpProfileStorage.KnownPlayer target = repository.findKnownPlayerByName(targetName);
                if (target == null) {
                    message = "Игрок не найден: " + targetName + ". Он должен хотя бы раз зайти на сервер.";
                } else if (history) {
                    historyLines = repository.loadFinanceHistory(target.uuid(), 8);
                    message = "История операций игрока " + target.name() + ":";
                    success = true;
                } else {
                    FinanceAccount account = repository.loadFinanceAccount(target.uuid(), 0, System.currentTimeMillis());
                    message = "Баланс " + target.name() + " — наличные: " + formatRubles(account.cashCents())
                            + " ₽; банк: " + formatRubles(account.bankCents()) + " ₽.";
                    success = true;
                }
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Administrator economy lookup failed for target {}", targetName, exception);
                message = "Ошибка базы данных. Проверь logs/latest.log.";
            }

            boolean resultSuccess = success;
            String resultMessage = message;
            List<String> resultHistory = historyLines;
            server.execute(() -> {
                sendAdminCommandResult(source, resultSuccess, resultMessage);
                if (resultSuccess && history) {
                    if (resultHistory.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("История операций пуста."), false);
                    } else {
                        for (String line : resultHistory) {
                            source.sendSuccess(() -> Component.literal(" • " + line), false);
                        }
                    }
                }
            });
        });
    }

    private static String sanitizeAdminReason(String reason) {
        if (reason == null) return "без указания причины";
        StringBuilder clean = new StringBuilder();
        reason.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(clean::appendCodePoint);
        String value = clean.toString().trim();
        if (value.isBlank()) return "без указания причины";
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints > 100) value = value.substring(0, value.offsetByCodePoints(0, 100));
        return value;
    }

    private static String formatRubles(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString().replace('.', ',');
    }

    private static void sendAdminCommandResult(CommandSourceStack source, boolean success, String message) {
        if (success) source.sendSuccess(() -> Component.literal(message), false);
        else source.sendFailure(Component.literal(message));
    }

    /** Returns null when valid, otherwise a short message safe to show to the player. */
    private static String validateCharacterDetails(String rpName, String birthDate, String biography) {
        int nameLength = rpName.codePointCount(0, rpName.length());
        if (nameLength < 2 || nameLength > 24) return "Имя персонажа должно содержать от 2 до 24 символов.";
        for (int offset = 0; offset < rpName.length();) {
            int codePoint = rpName.codePointAt(offset);
            boolean allowed = Character.isLetterOrDigit(codePoint)
                    || codePoint == ' ' || codePoint == '_' || codePoint == '-'
                    || codePoint == '\'' || codePoint == '.';
            if (!allowed) return "В имени разрешены буквы, цифры, пробел, точка, дефис и подчёркивание.";
            offset += Character.charCount(codePoint);
        }

        if (!birthDate.isEmpty()) {
            if (birthDate.length() > 10) return "Дата рождения должна быть в формате ГГГГ-ММ-ДД.";
            try {
                LocalDate parsed = LocalDate.parse(birthDate);
                if (parsed.isAfter(LocalDate.now(ZoneOffset.UTC))) return "Дата рождения не может быть в будущем.";
            } catch (DateTimeParseException exception) {
                return "Укажи существующую дату в формате ГГГГ-ММ-ДД или оставь поле пустым.";
            }
        }

        int biographyLength = biography.codePointCount(0, biography.length());
        if (biographyLength > 160) return "Биография не должна превышать 160 символов.";
        for (int offset = 0; offset < biography.length();) {
            int codePoint = biography.codePointAt(offset);
            if (Character.isISOControl(codePoint)) return "Биография должна быть одной строкой без управляющих символов.";
            offset += Character.charCount(codePoint);
        }
        return null;
    }

    private static void sendProfile(ServerPlayer player, Profile profile) {
        ServerPlayNetworking.send(player, new HrpNetwork.ProfileResponse(
                player.getUUID(), profile.name(), profile.rpName(), profile.birthDate(), profile.biography(),
                profile.firstJoin(), profile.playTimeTicks()));
    }

    private static void sendUpdateResult(ServerPlayer player, boolean success, String message) {
        ServerPlayNetworking.send(player, new HrpNetwork.ProfileUpdateResult(success, message));
    }

    private static List<StoredProfile> snapshotProfiles() {
        List<StoredProfile> snapshot = new ArrayList<>(PROFILES.size());
        PROFILES.forEach((uuid, profile) -> snapshot.add(profile.toStored(uuid)));
        return List.copyOf(snapshot);
    }

    private static void saveSnapshotAsync(List<StoredProfile> snapshot) {
        STORAGE_EXECUTOR.execute(() -> {
            HrpProfileStorage repository = storage;
            if (repository == null) return;
            try {
                repository.saveAll(snapshot);
            } catch (SQLException exception) {
                HRPCore.LOGGER.error("Could not save HRPCore profile snapshot", exception);
            }
        });
    }

    private static void saveOne(StoredProfile profile) {
        HrpProfileStorage repository = storage;
        if (repository == null) return;
        try {
            repository.save(profile);
        } catch (SQLException exception) {
            HRPCore.LOGGER.error("Could not save profile for {} ({})", profile.name(), profile.uuid(), exception);
        }
    }

    private static void flushAndClose() {
        List<StoredProfile> snapshot = snapshotProfiles();
        try {
            STORAGE_EXECUTOR.submit(() -> {
                HrpProfileStorage repository = storage;
                if (repository == null) return;
                try {
                    repository.saveAll(snapshot);
                } catch (SQLException exception) {
                    HRPCore.LOGGER.error("Could not save profiles while stopping server", exception);
                }
                try {
                    repository.close();
                } catch (SQLException exception) {
                    HRPCore.LOGGER.error("Could not close HRPCore profile database", exception);
                } finally {
                    storage = null;
                }
            }).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            HRPCore.LOGGER.error("Interrupted while waiting for profile storage to close", exception);
        } catch (ExecutionException | TimeoutException exception) {
            HRPCore.LOGGER.error("Timed out or failed while closing profile storage", exception);
        } finally {
            PROFILES.clear();
            ROLES.clear();
        }
    }
}
