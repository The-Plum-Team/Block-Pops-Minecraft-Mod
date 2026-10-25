package com.theplumteam.command;

import com.theplumteam.util.ServerLevels;
import com.mojang.brigadier.CommandDispatcher;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.server.ServerTickHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command to reload regular tokens for a player: the one running it, or the one named.
 * Usage: /blockpops reloadregulartokens [player]
 */
public class ReloadRegularTokensCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadRegularTokensCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("reloadregulartokens")
                        .requires(source -> ServerLevels.hasCommandLevel(source, 2))
                        .executes(context -> reload(context.getSource(), context.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> reload(context.getSource(), EntityArgument.getPlayer(context, "player"))))
                )
        );
    }

    private static int reload(CommandSourceStack source, ServerPlayer player) {
        try {
            IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);

            // Reload regular tokens
            discovery.setRegularTokens(3);
            discovery.setNextRegularTokenTime(0);
            PlayerDataManager.markDirty(player, discovery);

            BlockPopsMod.logDebug("Reloaded regular tokens for player {}", player.getName().getString());

            // Sync token data back to client
            long gameTime = ServerLevels.of(player).getGameTime();
            long nextRegularTime = discovery.getNextRegularTokenTime();
            long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

            // Calculate millis until next special reset using ServerTickHandler
            long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

            SyncTokenDataPacket.sendToPlayer(
                    player,
                    discovery.getRegularTokens(),
                    ticksUntilNext,
                    discovery.getGuaranteedTokens(),
                    millisUntilReset
            );

            source.sendSuccess(() -> Component.translatable("blockpops.command.reload_regular.done", player.getName().getString()), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("blockpops.command.reload_regular.failed", player.getName().getString()));
            LOGGER.error("Error executing reloadregulartokens command", e);
            return 0;
        }
    }
}
