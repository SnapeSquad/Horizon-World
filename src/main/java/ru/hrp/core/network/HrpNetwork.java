
package ru.hrp.core.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import static ru.hrp.core.HRPCore.MOD_ID;

public final class HrpNetwork {
    private HrpNetwork() {}

    public record RequestProfile() implements CustomPacketPayload {
        public static final Type<RequestProfile> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(
                        MOD_ID, "request_profile"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestProfile> CODEC =
                StreamCodec.unit(new RequestProfile());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ProfileResponse(
            UUID uuid,
            String name,
            long firstJoin,
            long playTimeTicks
    ) implements CustomPacketPayload {
        public static final Type<ProfileResponse> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(
                        MOD_ID, "profile_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileResponse> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        p -> p.uuid().toString(),
                        ByteBufCodecs.STRING_UTF8,
                        ProfileResponse::name,
                        ByteBufCodecs.VAR_LONG,
                        ProfileResponse::firstJoin,
                        ByteBufCodecs.VAR_LONG,
                        ProfileResponse::playTimeTicks,
                        (uuid, name, firstJoin, playTimeTicks) ->
                                new ProfileResponse(
                                        UUID.fromString(uuid),
                                        name,
                                        firstJoin,
                                        playTimeTicks
                                )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(
                RequestProfile.TYPE, RequestProfile.CODEC);

        PayloadTypeRegistry.playS2C().register(
                ProfileResponse.TYPE, ProfileResponse.CODEC);
    }
}
