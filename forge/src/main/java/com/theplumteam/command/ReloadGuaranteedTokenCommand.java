// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\command\ReloadGuaranteedTokenCommand.java ==========
package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.server.ServerTickHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReloadGuaranteedTokenCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadGuaranteedTokenCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("reloadguaranteed")
                        .requires(source -> source.hasPermission(2)) // Requires operator permission
                        .executes(ReloadGuaranteedTokenCommand::executeCommand)
                )
        );
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();

            player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                // Reload guaranteed token
                discovery.setUsedTodaySpecialToken(false);
                LOGGER.info("Reloaded guaranteed token for player {}", player.getName().getString());

                // Sync token data back to client
                long gameTime = player.serverLevel().getGameTime();
                long nextRegularTime = discovery.getNextRegularTokenTime();
                long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                // Calculate millis until next special reset using central helper
                long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

                SyncTokenDataPacket tokenPacket = new SyncTokenDataPacket(
                        discovery.getRegularTokens(),
                        ticksUntilNext,
                        !discovery.hasUsedTodaySpecialToken(),
                        millisUntilReset
                );
                BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), tokenPacket);
            });

            source.sendSuccess(() -> Component.literal("Reloaded guaranteed token"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            LOGGER.error("Error executing reloadguaranteed command", e);
            return 0;
        }
    }
}