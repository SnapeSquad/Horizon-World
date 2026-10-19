package ru.hrp.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.hrp.core.HrpProfileStorage.StoredProfile;
import ru.hrp.core.network.HrpNetwork;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

/** Owns in-memory session state and serializes every SQLite operation off-thread. */
public final class HrpProfileService {
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;

    private HrpProfileService() {}

    private record Profile(String name, long firstJoin, long playTimeTicks) {
        private Profile ticked() {
            return new Profile(name, firstJoin, playTimeTicks + 1);
        }

        private StoredProfile toStored(UUID uuid) {
            return new StoredProfile(uuid, name, firstJoin, playTimeTicks);
        }
    }

    private static final Map<UUID, Profile> PROFILES = new ConcurrentHashMap<>();
    private static volatile HrpProfileStorage storage;
    private static volatile ExecutorService storageExecutor = newStorageExecutor();
    private static long ticksSinceLastSave;

    private static ExecutorService newStorageExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "HRPCore-ProfileStorage");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PROFILES.clear();
            ticksSinceLastSave = 0;

            Path databasePath = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data")
                    .resolve("hrpcore")
                    .resolve("profiles.db");

            storageExecutor.execute(() -> {
                try {
                    HrpProfileStorage previous = storage;
                    if (previous != null) {
                        previous.close();
                    }
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
                storageExecutor.execute(() -> saveOne(snapshot));
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
                        return;
                    }

                    MinecraftServer server = context.server();
                    if (server != null) {
                        loadProfileAsync(server, player, true);
                    }
                }
        );

        // Flush and close only while the server is stopping; normal database work is async.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushAndClose());
    }

    private static void loadProfileAsync(MinecraftServer server, ServerPlayer player, boolean respondWhenLoaded) {
        UUID uuid = player.getUUID();
        String playerName = player.getName().getString();
        long nowMillis = System.currentTimeMillis();

        storageExecutor.execute(() -> {
            StoredProfile loaded;
            try {
                HrpProfileStorage repository = storage;
                if (repository == null) {
                    throw new IllegalStateException("Profile database is not available");
                }
                loaded = repository.loadOrCreate(uuid, playerName, nowMillis);
            } catch (Exception exception) {
                HRPCore.LOGGER.error("Could not load profile for {} ({})", playerName, uuid, exception);
                // Keep the UI usable if storage fails, while logging that this fallback is not persisted.
                loaded = new StoredProfile(uuid, playerName, nowMillis, 0);
            }

            StoredProfile result = loaded;
            server.execute(() -> {
                if (player.isRemoved()) {
                    return;
                }

                Profile profile = PROFILES.compute(uuid, (ignored, current) -> {
                    if (current == null) {
                        return new Profile(result.name(), result.firstJoin(), result.playTimeTicks());
                    }
                    return new Profile(
                            result.name(),
                            Math.min(current.firstJoin(), result.firstJoin()),
                            Math.max(current.playTimeTicks(), result.playTimeTicks())
                    );
                });

                if (respondWhenLoaded) {
                    sendProfile(player, profile);
                }
            });
        });
    }

    private static void sendProfile(ServerPlayer player, Profile profile) {
        ServerPlayNetworking.send(player, new HrpNetwork.ProfileResponse(
                player.getUUID(),
                profile.name(),
                profile.firstJoin(),
                profile.playTimeTicks()
        ));
    }

    private static List<StoredProfile> snapshotProfiles() {
        List<StoredProfile> snapshot = new ArrayList<>(PROFILES.size());
        PROFILES.forEach((uuid, profile) -> snapshot.add(profile.toStored(uuid)));
        return List.copyOf(snapshot);
    }

    private static void saveSnapshotAsync(List<StoredProfile> snapshot) {
        storageExecutor.execute(() -> {
            HrpProfileStorage repository = storage;
            if (repository == null) {
                return;
            }
            try {
                repository.saveAll(snapshot);
            } catch (SQLException exception) {
                HRPCore.LOGGER.error("Could not save HRPCore profile snapshot", exception);
            }
        });
    }

    private static void saveOne(StoredProfile profile) {
        HrpProfileStorage repository = storage;
        if (repository == null) {
            return;
        }
        try {
            repository.save(profile);
        } catch (SQLException exception) {
            HRPCore.LOGGER.error("Could not save profile for {} ({})", profile.name(), profile.uuid(), exception);
        }
    }

    private static void flushAndClose() {
        List<StoredProfile> snapshot = snapshotProfiles();
        try {
            storageExecutor.submit(() -> {
                HrpProfileStorage repository = storage;
                if (repository == null) {
                    return;
                }
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
