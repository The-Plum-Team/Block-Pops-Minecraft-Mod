package com.theplumteam.server;

import com.theplumteam.util.ServerLevels;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Who a player is to the server they are on. */
public final class ServerProfiles {
    private ServerProfiles() {
    }

    /**
     * Whether the player is the host of an integrated server: the one who opened this world in
     * their own game. Nobody is on a dedicated server. 1.21.9 changed what the server asks by.
     */
    public static boolean isHost(MinecraftServer server, ServerPlayer player) {
        //? if >=1.21.9 {
        /*return server.isSingleplayerOwner(player.nameAndId());
        *///? } else {
        return server.isSingleplayerOwner(player.getGameProfile());
        //? }
    }

    /**
     * Whether the player manages the collections a world adds by code: an operator at level 2,
     * or the host of their own world, who has no operator level while cheats are off.
     */
    public static boolean managesCodes(ServerPlayer player) {
        MinecraftServer server = ServerLevels.serverOf(player);
        return ServerLevels.hasCommandLevel(player, 2) || (server != null && isHost(server, player));
    }

    /** Whether whoever runs a command manages the collections a world adds by code. */
    public static boolean managesCodes(CommandSourceStack source) {
        if (ServerLevels.hasCommandLevel(source, 2)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && isHost(source.getServer(), player);
    }
}
