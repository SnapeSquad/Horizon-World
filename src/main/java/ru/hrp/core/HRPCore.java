
package ru.hrp.core;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hrp.core.network.HrpNetwork;

public final class HRPCore implements ModInitializer {

    public static final String MOD_ID = "hrpcore";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("HRPCore initializing");

        // 1. Сначала регистрируем типы сетевых пакетов.
        HrpNetwork.registerPayloads();

        // 2. Затем регистрируем серверные обработчики.
        HrpProfileService.register();

        // 3. После этого регистрируем команды.
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        dispatcher.register(
                                net.minecraft.commands.Commands.literal("hrp")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(
                                                            "HRPCore is running."
                                                    ),
                                                    false
                                            );
                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
        );

        LOGGER.info("HRPCore initialized successfully");
    }
}
