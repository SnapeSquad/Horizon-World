package ru.hrp.core.network;

import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.hrp.core.HRPCore.MOD_ID;

/** Network payload declarations shared by the logical server and client. */
public final class HrpNetwork {
    private static final int MAX_HISTORY_LINES = 8;

    private HrpNetwork() {}

    public record RequestProfile() implements CustomPacketPayload {
        public static final Type<RequestProfile> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "request_profile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestProfile> CODEC =
                StreamCodec.unit(new RequestProfile());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
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
        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileResponse> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, ProfileResponse payload) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.uuid().toString());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.rpName());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.birthDate());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.biography());
                    ByteBufCodecs.VAR_LONG.encode(buf, payload.firstJoin());
                    ByteBufCodecs.VAR_LONG.encode(buf, payload.playTimeTicks());
                },
                buf -> new ProfileResponse(
                        UUID.fromString(ByteBufCodecs.STRING_UTF8.decode(buf)),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A client request. UUID is intentionally omitted: players can only edit their own character. */
    public record SaveCharacterProfile(String rpName, String birthDate, String biography)
            implements CustomPacketPayload {
        public static final Type<SaveCharacterProfile> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "save_character_profile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveCharacterProfile> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, SaveCharacterProfile payload) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.rpName());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.birthDate());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.biography());
                },
                buf -> new SaveCharacterProfile(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ProfileUpdateResult(boolean success, String message) implements CustomPacketPayload {
        public static final Type<ProfileUpdateResult> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "profile_update_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ProfileUpdateResult> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ProfileUpdateResult::success,
                        ByteBufCodecs.STRING_UTF8, ProfileUpdateResult::message,
                        ProfileUpdateResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestFinance() implements CustomPacketPayload {
        public static final Type<RequestFinance> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "request_finance"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestFinance> CODEC =
                StreamCodec.unit(new RequestFinance());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** action: 0 = deposit cash, 1 = withdraw cash, 2 = transfer cash to a player's wallet. */
    public record FinanceAction(int action, long amountCents, String targetName) implements CustomPacketPayload {
        public static final Type<FinanceAction> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "finance_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FinanceAction> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, FinanceAction payload) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, payload.action());
                    ByteBufCodecs.VAR_LONG.encode(buf, payload.amountCents());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.targetName() == null ? "" : payload.targetName());
                },
                buf -> new FinanceAction(
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<String>> HISTORY_CODEC = StreamCodec.of(
            (RegistryFriendlyByteBuf buf, List<String> history) -> {
                List<String> safeHistory = history == null ? List.of() : history.subList(0, Math.min(history.size(), MAX_HISTORY_LINES));
                ByteBufCodecs.VAR_INT.encode(buf, safeHistory.size());
                for (String line : safeHistory) ByteBufCodecs.STRING_UTF8.encode(buf, line == null ? "" : line);
            },
            buf -> {
                int size = ByteBufCodecs.VAR_INT.decode(buf);
                if (size < 0 || size > MAX_HISTORY_LINES) throw new DecoderException("Invalid finance history size: " + size);
                List<String> history = new ArrayList<>(size);
                for (int i = 0; i < size; i++) history.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                return List.copyOf(history);
            }
    );

    /** Snapshot is server-produced. The client never tells the server what a balance should be. */
    public record FinanceResponse(
            long cashCents,
            long bankCents,
            List<String> history,
            boolean success,
            String message
    ) implements CustomPacketPayload {
        public static final Type<FinanceResponse> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "finance_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FinanceResponse> CODEC = StreamCodec.of(
                (RegistryFriendlyByteBuf buf, FinanceResponse payload) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, payload.cashCents());
                    ByteBufCodecs.VAR_LONG.encode(buf, payload.bankCents());
                    HISTORY_CODEC.encode(buf, payload.history() == null ? List.of() : payload.history());
                    ByteBufCodecs.BOOL.encode(buf, payload.success());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.message() == null ? "" : payload.message());
                },
                buf -> new FinanceResponse(
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        HISTORY_CODEC.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf))
        );

        public FinanceResponse {
            history = history == null ? List.of() : List.copyOf(history);
            message = message == null ? "" : message;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(RequestProfile.TYPE, RequestProfile.CODEC);
        PayloadTypeRegistry.playS2C().register(ProfileResponse.TYPE, ProfileResponse.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveCharacterProfile.TYPE, SaveCharacterProfile.CODEC);
        PayloadTypeRegistry.playS2C().register(ProfileUpdateResult.TYPE, ProfileUpdateResult.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestFinance.TYPE, RequestFinance.CODEC);
        PayloadTypeRegistry.playC2S().register(FinanceAction.TYPE, FinanceAction.CODEC);
        PayloadTypeRegistry.playS2C().register(FinanceResponse.TYPE, FinanceResponse.CODEC);
    }
}
