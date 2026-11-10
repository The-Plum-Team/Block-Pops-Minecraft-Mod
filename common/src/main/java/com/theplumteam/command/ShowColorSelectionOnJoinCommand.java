package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.ServerLevels;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Turns the favorite color screen a player gets on joining on or off; off, a player who has not
 * chosen a color is asked when they open a machine instead.
 * Usage: /blockpops showcolorselectiononjoin [true|false]
 */
public class ShowColorSelectionOnJoinCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("showcolorselectiononjoin")
                .requires(source -> ServerLevels.hasCommandLevel(source, 2)) // Admin permission (op level 2)
                .executes(context -> report(context.getSource(),
                        ServerConfig.getInstance().isShowColorSelectionOnJoin()))
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(ShowColorSelectionOnJoinCommand::set)
                )
            )
        );
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        boolean value = BoolArgumentType.getBool(context, "value");
        ServerConfig.getInstance().setShowColorSelectionOnJoin(value);
        return report(context.getSource(), value);
    }

    private static int report(CommandSourceStack source, boolean value) {
        source.sendSuccess(() -> Component.translatable(value
                ? "blockpops.command.color_selection_on_join.on"
                : "blockpops.command.color_selection_on_join.off"), true);
        return 1;
    }
}
