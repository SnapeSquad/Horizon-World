package ru.hrp.core.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import static ru.hrp.core.HRPCore.MOD_ID;

/** Network payload declarations shared by the logical server and client. */
public final class HrpNetwork {
    private HrpNetwork() {}

    public record RequestProfile() implements CustomPacketPayload {
        public static final Type<RequestProfile> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "request_profile"));
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
            String rpName,
            String birthDate,
            String biography,
            long firstJoin,
            long playTimeTicks
    ) implements CustomPacketPayload {
        public static final Type<ProfileResponse> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "profile_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileResponse> CODEC =
                StreamCodec.of(
                        (RegistryFriendlyByteBuf buf, ProfileResponse payload) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.uuid().toString());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.rpName());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.birthDate());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.biography());
                            ByteBufCodecs.VAR_LONG.encode(buf, payload.firstJoin());
                            ByteBufCodecs.VAR_LONG.encode(buf, payload.playTimeTicks());
                        },
                        (RegistryFriendlyByteBuf buf) -> new ProfileResponse(
                                UUID.fromString(ByteBufCodecs.STRING_UTF8.decode(buf)),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.VAR_LONG.decode(buf),
                                ByteBufCodecs.VAR_LONG.decode(buf)
                        )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A client request. UUID is intentionally omitted: players can only edit their own character. */
    public record SaveCharacterProfile(String rpName, String birthDate, String biography)
            implements CustomPacketPayload {
        public static final Type<SaveCharacterProfile> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "save_character_profile"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveCharacterProfile> CODEC =
                StreamCodec.of(
                        (RegistryFriendlyByteBuf buf, SaveCharacterProfile payload) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.rpName());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.birthDate());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.biography());
                        },
                        (RegistryFriendlyByteBuf buf) -> new SaveCharacterProfile(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf)
                        )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ProfileUpdateResult(boolean success, String message) implements CustomPacketPayload {
        public static final Type<ProfileUpdateResult> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "profile_update_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileUpdateResult> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ProfileUpdateResult::success,
                        ByteBufCodecs.STRING_UTF8, ProfileUpdateResult::message,
                        ProfileUpdateResult::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(RequestProfile.TYPE, RequestProfile.CODEC);
        PayloadTypeRegistry.playS2C().register(ProfileResponse.TYPE, ProfileResponse.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveCharacterProfile.TYPE, SaveCharacterProfile.CODEC);
        PayloadTypeRegistry.playS2C().register(ProfileUpdateResult.TYPE, ProfileUpdateResult.CODEC);
    }
}
