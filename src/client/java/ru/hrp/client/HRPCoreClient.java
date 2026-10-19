
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import ru.hrp.client.HrpProfileState;
import ru.hrp.core.network.HrpNetwork;

public final class HRPCoreClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    ResourceLocation.fromNamespaceAndPath(
                            HRPCore.MOD_ID, "main"
                    )
            );

    @Override
    public void onInitializeClient() {
        KeyMapping openMenu = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.hrpcore.open_menu",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_O,
                        CATEGORY
                )
        );

        ClientPlayNetworking.registerGlobalReceiver(
                HrpNetwork.ProfileResponse.TYPE,
                (payload, context) -> {
                    HrpProfileState.set(payload);
                    HRPCore.LOGGER.info(
                            "Получен профиль игрока: {}", payload.name()
                    );
                }
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {

                // Открываем меню независимо от наличия подключения.
                if (client.screen == null) {
                    client.setScreen(new HrpMainScreen());
                }

                // Запрашиваем профиль только при доступном сетевом соединении.
                if (ClientPlayNetworking.canSend(HrpNetwork.RequestProfile.TYPE)) {
                    ClientPlayNetworking.send(
                            new HrpNetwork.RequestProfile()
                    );
                }
            }
        });

        HRPCore.LOGGER.info("HRPCore client UI initialized");
    }
}