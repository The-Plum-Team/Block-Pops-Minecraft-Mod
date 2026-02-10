package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class ShowColorSelectionOnJoinCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("showcolorselectiononjoin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(ShowColorSelectionOnJoinCommand::executeCommand)
                )
            )
        );
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        boolean value = BoolArgumentType.getBool(context, "value");
        CommandSourceStack source = context.getSource();

        ServerConfig.getInstance().setShowColorSelectionOnJoin(value);

        source.sendSuccess(() -> Component.literal("Show color selection on join set to: " + value), true);
        return 1;
    }
}
