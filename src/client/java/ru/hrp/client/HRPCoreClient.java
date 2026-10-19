package ru.hrp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import ru.hrp.client.screen.HrpMainScreen;
import ru.hrp.core.HRPCore;
import ru.hrp.core.network.HrpNetwork;

public final class HRPCoreClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            ResourceLocation.fromNamespaceAndPath(HRPCore.MOD_ID, "main")
    );

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                HrpNetwork.ProfileResponse.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    HrpProfileState.set(payload);
                    HRPCore.LOGGER.info("Получен профиль игрока: {}", payload.name());
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(
                HrpNetwork.ProfileUpdateResult.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    HrpProfileState.setUpdateResult(payload.success(), payload.message());
                    if (payload.success()) {
                        HRPCore.LOGGER.info("RP-профиль обновлён: {}", payload.message());
                    } else {
                        HRPCore.LOGGER.warn("Не удалось обновить RP-профиль: {}", payload.message());
                    }
                })
        );

        KeyMapping openMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.hrpcore.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.screen == null) client.setScreen(new HrpMainScreen());

                if (ClientPlayNetworking.canSend(HrpNetwork.RequestProfile.TYPE)) {
                    HrpProfileState.clear();
                    ClientPlayNetworking.send(new HrpNetwork.RequestProfile());
                }
            }
        });

        HRPCore.LOGGER.info("HRPCore client UI initialized");
    }
}
