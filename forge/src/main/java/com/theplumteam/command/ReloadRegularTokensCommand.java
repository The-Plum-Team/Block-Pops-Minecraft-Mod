package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.SyncTokenDataPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReloadRegularTokensCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadRegularTokensCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("reloadregular")
                        .requires(source -> source.hasPermission(2)) // Requires operator permission
                        .executes(ReloadRegularTokensCommand::executeCommand)
                )
        );
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();

            player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                // Reload regular tokens
                discovery.setRegularTokens(3);
                discovery.setNextRegularTokenTime(0);
                LOGGER.info("Reloaded regular tokens for player {}", player.getName().getString());

                // Sync token data back to client
                long gameTime = player.serverLevel().getGameTime();
                long nextRegularTime = discovery.getNextRegularTokenTime();
                long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);

                // Calculate millis until next special reset
                long millisUntilReset = calculateMillisUntilNextReset();

                SyncTokenDataPacket tokenPacket = new SyncTokenDataPacket(
                        discovery.getRegularTokens(),
                        ticksUntilNext,
                        !discovery.hasUsedTodaySpecialToken(),
                        millisUntilReset
                );
                BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), tokenPacket);
            });

            source.sendSuccess(() -> Component.literal("Reloaded regular tokens to 3"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            LOGGER.error("Error executing reloadregular command", e);
            return 0;
        }
    }

    /**
     * Calculate milliseconds until the next daily reset at the configured hour.
     */
    private static long calculateMillisUntilNextReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        int resetHour = com.theplumteam.server.config.ServerConfig.getInstance().getGuaranteedTokenResetHour();
        ZonedDateTime nextReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);

        // If we're past reset hour today, next reset is tomorrow
        if (now.getHour() >= resetHour) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
}
