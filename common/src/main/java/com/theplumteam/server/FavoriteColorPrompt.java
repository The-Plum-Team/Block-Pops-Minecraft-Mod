package com.theplumteam.server;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import com.theplumteam.server.config.ServerConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * When a player who has not chosen a favorite color yet is asked for one: on joining, or, when
 * the server turned that off ({@code showColorSelectionOnJoin}), the first times they open a
 * machine. Either way the screen stops coming once a color is chosen, and the machine screen's
 * own button and {@code /blockpops changefavoritecolor} always open it.
 */
public final class FavoriteColorPrompt {
    private FavoriteColorPrompt() {
    }

    /** Called once a joining player's data is synced. */
    public static void onJoin(ServerPlayer player, boolean hasChosenFavoriteColor) {
        if (!hasChosenFavoriteColor && ServerConfig.getInstance().isShowColorSelectionOnJoin()) {
            BlockPopsMod.logDebug("Player {} has not chosen a favorite color. Sending packet to open selection screen.",
                    player.getName().getString());
            OpenFavoriteColorScreenPacket.sendToPlayer(player);
        }
    }

    /** Called on the server when a player opens a claw or gacha machine. */
    public static void onMachineOpened(ServerPlayer player) {
        if (!ServerConfig.getInstance().isShowColorSelectionOnJoin()
                && !PlayerDataManager.getDiscovery(player).hasChosenFavoriteColor()) {
            OpenFavoriteColorScreenPacket.sendToPlayer(player);
        }
    }
}
