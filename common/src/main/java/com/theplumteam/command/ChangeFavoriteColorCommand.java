package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command to open the favorite color selection screen, allowing players to change their favorite color.
 * Usage: /blockpops changefavoritecolor
 */
public class ChangeFavoriteColorCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeFavoriteColorCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("changefavoritecolor")
                        .executes(ChangeFavoriteColorCommand::executeCommand)
                )
        );
    }

    /**
     * Execute the command - sends the OpenFavoriteColorScreenPacket to the player
     */
    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerPlayer player = source.getPlayerOrException();

            // Send packet to client to open the favorite color selection screen using cross-platform networking
            OpenFavoriteColorScreenPacket.sendToPlayer(player);

            source.sendSuccess(() -> Component.literal("Opening favorite color selection..."), false);
            LOGGER.info("Player {} opened the favorite color selection screen", player.getName().getString());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            LOGGER.error("Error executing changefavoritecolor command", e);
            return 0;
        }
    }
}
