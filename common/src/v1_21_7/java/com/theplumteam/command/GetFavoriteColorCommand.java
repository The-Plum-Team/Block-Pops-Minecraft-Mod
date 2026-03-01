package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command to display the player's currently chosen favorite color.
 * Usage: /blockpops getfavoritecolor
 */
public class GetFavoriteColorCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetFavoriteColorCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("getfavoritecolor")
                        .executes(GetFavoriteColorCommand::executeCommand)
                )
        );
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerPlayer player = source.getPlayerOrException();
            IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);

            if (!discovery.hasChosenFavoriteColor()) {
                source.sendSuccess(() -> Component.literal("You have not chosen a favorite color yet. Use /blockpops changefavoritecolor to choose one."), false);
                BlockPopsMod.logDebug("Player {} checked their favorite color but hasn't chosen one yet", player.getName().getString());
            } else {
                PopBlockColor favoriteColor = discovery.getFavoriteColor();
                if (favoriteColor != null) {
                    String colorName = formatColorName(favoriteColor.getSerializedName());
                    source.sendSuccess(() -> Component.literal("Your favorite color is: " + colorName), false);
                    BlockPopsMod.logDebug("Player {} checked their favorite color: {}", player.getName().getString(), favoriteColor.getSerializedName());
                } else {
                    source.sendFailure(Component.literal("Error: Favorite color data is corrupted. Please choose a new color with /blockpops changefavoritecolor"));
                    LOGGER.warn("Player {} has chosen a favorite color but the data is null", player.getName().getString());
                }
            }

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            LOGGER.error("Error executing getfavoritecolor command", e);
            return 0;
        }
    }

    private static String formatColorName(String colorName) {
        String[] words = colorName.split("_");
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                formatted.append(" ");
            }
            formatted.append(words[i].substring(0, 1).toUpperCase());
            formatted.append(words[i].substring(1));
        }

        return formatted.toString();
    }
}
