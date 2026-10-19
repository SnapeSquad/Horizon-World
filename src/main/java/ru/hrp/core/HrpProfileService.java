package ru.hrp.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import ru.hrp.core.HrpProfileStorage.StoredProfile;
import ru.hrp.core.network.HrpNetwork;

import java.nio.file.Path;
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
                    if (profile != null) {
                        sendProfile(player, profile);
                    } else {
                        loadProfileAsync(context.server(), player, true);
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.SaveCharacterProfile.TYPE,
                (payload, context) -> context.server().execute(() ->
                        saveCharacterProfile(context.server(), context.player(), payload))
        );

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushAndClose());
    }

    private static void loadProfileAsync(MinecraftServer server, ServerPlayer player, boolean respondWhenLoaded) {
        UUID uuid = player.getUUID();
        String playerName = player.getName().getString();
        long nowMillis = System.currentTimeMillis();

        STORAGE_EXECUTOR.execute(() -> {
            StoredProfile loaded;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) throw new IllegalStateException("Profile database is not available");
                loaded = repository.loadOrCreate(uuid, playerName, nowMillis);
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not load profile for {} ({})", playerName, uuid, exception);
                // Fallback keeps the UI usable, but the log makes the storage failure visible.
                loaded = new StoredProfile(uuid, playerName, playerName, "", "", nowMillis, 0);
            }

            StoredProfile result = loaded;
            server.execute(() -> {
                if (player.isRemoved()) return;

                Profile profile = PROFILES.compute(uuid, (ignored, current) -> {
                    if (current == null) {
                        return new Profile(result.name(), result.rpName(), result.birthDate(), result.biography(),
                                result.firstJoin(), result.playTimeTicks());
                    }
                    // The active session wins for editable fields and play time if a second load was requested.
                    return new Profile(result.name(), current.rpName(), current.birthDate(), current.biography(),
                            Math.min(current.firstJoin(), result.firstJoin()),
                            Math.max(current.playTimeTicks(), result.playTimeTicks()));
                });

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

        // Update the in-memory snapshot before queueing I/O so a shutdown snapshot cannot overwrite
        // newly submitted fields with stale values. If persistence fails, the fields are rolled back.
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
                        // Do not roll back a newer edit that may have been submitted afterward.
                        if (latest.rpName().equals(rpName)
                                && latest.birthDate().equals(birthDate)
                                && latest.biography().equals(biography)) {
                            return latest.withCharacterDetails(previous.rpName(), previous.birthDate(), previous.biography());
                        }
                        return latest;
                    });
                    if (!player.isRemoved()) {
                        sendUpdateResult(player, false, "Не удалось сохранить данные. Проверь лог сервера.");
                    }
                });
            }
        });
    }

    /** Returns null when valid, otherwise a short message safe to show to the player. */
    private static String validateCharacterDetails(String rpName, String birthDate, String biography) {
        int nameLength = rpName.codePointCount(0, rpName.length());
        if (nameLength < 2 || nameLength > 24) {
            return "Имя персонажа должно содержать от 2 до 24 символов.";
        }
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
                if (parsed.isAfter(LocalDate.now(ZoneOffset.UTC))) {
                    return "Дата рождения не может быть в будущем.";
                }
            } catch (DateTimeParseException exception) {
                return "Укажи существующую дату в формате ГГГГ-ММ-ДД или оставь поле пустым.";
            }
        }

        int biographyLength = biography.codePointCount(0, biography.length());
        if (biographyLength > 160) return "Биография не должна превышать 160 символов.";
        for (int offset = 0; offset < biography.length();) {
            int codePoint = biography.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                return "Биография должна быть одной строкой без управляющих символов.";
            }
            offset += Character.charCount(codePoint);
        }
        return null;
    }

    private static void sendProfile(ServerPlayer player, Profile profile) {
        ServerPlayNetworking.send(player, new HrpNetwork.ProfileResponse(
                player.getUUID(),
                profile.name(),
                profile.rpName(),
                profile.birthDate(),
                profile.biography(),
                profile.firstJoin(),
                profile.playTimeTicks()
        ));
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
        }
    }
}
