package ru.hrp.core;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Arrays;

/** Operator-only commands for inspecting accounts and performing audited adjustments. */
public final class HrpAdminCommands {
    private static final SuggestionProvider<CommandSourceStack> ROLE_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(HrpRole.values()).map(HrpRole::getId), builder);

    private HrpAdminCommands() {}

    /** Adds the admin branch to the shared /hrp command tree. */
    public static void attach(LiteralArgumentBuilder<CommandSourceStack> hrpRoot) {
        hrpRoot.then(Commands.literal("admin")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            "HRPCore admin: balance <игрок> | history <игрок> | give/take <игрок> <сумма> [причина] | givebank/takebank <игрок> <сумма> [причина] | role <игрок> | setrole <игрок> <роль> [причина]"), false);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("balance")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> {
                                    HrpProfileService.adminBalance(context.getSource(),
                                            StringArgumentType.getString(context, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("history")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> {
                                    HrpProfileService.adminHistory(context.getSource(),
                                            StringArgumentType.getString(context, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(adjustmentCommand("give", true, false))
                .then(adjustmentCommand("take", false, false))
                .then(adjustmentCommand("givebank", true, true))
                .then(adjustmentCommand("takebank", false, true))
                .then(Commands.literal("role")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> {
                                    HrpProfileService.adminGetRole(context.getSource(),
                                            StringArgumentType.getString(context, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("setrole")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests(ROLE_SUGGESTIONS)
                                        .executes(context -> {
                                            HrpProfileService.adminSetRole(context.getSource(),
                                                    StringArgumentType.getString(context, "player"),
                                                    StringArgumentType.getString(context, "role"),
                                                    null);
                                            return Command.SINGLE_SUCCESS;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    HrpProfileService.adminSetRole(context.getSource(),
                                                            StringArgumentType.getString(context, "player"),
                                                            StringArgumentType.getString(context, "role"),
                                                            StringArgumentType.getString(context, "reason"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adjustmentCommand(
            String commandName, boolean give, boolean bankAccount) {
        return Commands.literal(commandName)
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(context -> executeAdjustment(context, give, bankAccount,
                                        "без указания причины"))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> executeAdjustment(context, give, bankAccount,
                                                StringArgumentType.getString(context, "reason"))))));
    }

    private static int executeAdjustment(CommandContext<CommandSourceStack> context,
                                         boolean give, boolean bankAccount, String reason) {
        String targetName = StringArgumentType.getString(context, "player");
        String amount = StringArgumentType.getString(context, "amount");
        HrpProfileService.adminAdjustBalance(context.getSource(), targetName, amount, give, bankAccount, reason);
        return Command.SINGLE_SUCCESS;
    }
}
