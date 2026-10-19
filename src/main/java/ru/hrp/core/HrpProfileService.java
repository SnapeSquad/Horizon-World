
package ru.hrp.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import ru.hrp.core.network.HrpNetwork;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HrpProfileService {
    private HrpProfileService() {}

    private record Profile(long firstJoin, long playTimeTicks) {}

    private static final Map<UUID, Profile> PROFILES =
            new ConcurrentHashMap<>();

    private static final Map<UUID, Long> LAST_TICK =
            new ConcurrentHashMap<>();

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.player.getUUID();
            long now = System.currentTimeMillis();

            PROFILES.computeIfAbsent(uuid, id -> new Profile(now, 0));
            LAST_TICK.put(uuid, server.overworld().getGameTime());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            LAST_TICK.remove(uuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tick = server.overworld().getGameTime();

            for (var player : server.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                Long previous = LAST_TICK.put(uuid, tick);

                if (previous != null && tick > previous) {
                    PROFILES.computeIfPresent(uuid, (id, profile) ->
                            new Profile(
                                    profile.firstJoin(),
                                    profile.playTimeTicks() + (tick - previous)
                            )
                    );
                }
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(
                HrpNetwork.RequestProfile.TYPE,
                (payload, context) -> {
                    var player = context.player();
                    UUID uuid = player.getUUID();

                    Profile profile = PROFILES.computeIfAbsent(
                            uuid,
                            id -> new Profile(
                                    System.currentTimeMillis(), 0));

                    ServerPlayNetworking.send(player,
                            new HrpNetwork.ProfileResponse(
                                    uuid,
                                    player.getName().getString(),
                                    profile.firstJoin(),
                                    profile.playTimeTicks()
                            ));
                }
        );
    }
}
