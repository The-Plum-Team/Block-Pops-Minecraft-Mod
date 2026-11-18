package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.server.SupportCheckout;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends the player a link to the checkout of the supporter subscription, made for them.
 * Usage: /blockpops supporter (also what the supporters-only plush's chat hint runs), and
 * /blockpops supporter link, which opens the dialog where a Ko-fi payment is linked to the
 * player's account (what the chat's "Link my account" runs).
 */
public class SupporterCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("supporter")
                        .executes(SupporterCommand::executeCommand)
                        .then(Commands.literal("link")
                                .executes(SupporterCommand::executeLink))
                )
        );
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("blockpops.command.player_only"));
            return 0;
        }
        SupportCheckout.request(player, false);
        return 1;
    }

    private static int executeLink(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("blockpops.command.player_only"));
            return 0;
        }
        SupportCheckout.openLink(player);
        return 1;
    }
}
