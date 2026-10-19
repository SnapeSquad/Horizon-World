package ru.hrp.core;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hrp.core.network.HrpNetwork;

/** Common entry point. Game rules and persistent state belong on the server side. */
public final class HRPCore implements ModInitializer {
    public static final String MOD_ID = "hrpcore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("HRPCore {} initializing", "0.1.0-alpha.1");

        HrpNetwork.registerPayloads();

        HrpProfileService.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = net.minecraft.commands.Commands.literal("hrp")
                    .executes(context -> {
                        context.getSource().sendSuccess(
                                () -> Component.literal("HRPCore is running. Open the HRP menu with O on the client."),
                                false
                        );
                        return Command.SINGLE_SUCCESS;
                    });
            HrpAdminCommands.attach(root);
            dispatcher.register(root);
        });
    }
}
