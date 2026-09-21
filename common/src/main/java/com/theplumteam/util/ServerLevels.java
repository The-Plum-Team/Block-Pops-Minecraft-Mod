package com.theplumteam.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Names the level a server player is in, which 1.21.6 renamed. */
public final class ServerLevels {
    private ServerLevels() {
    }

    public static ServerLevel of(ServerPlayer player) {
        //? if >=1.21.6 {
        /*return player.level();
        *///? } else {
        return player.serverLevel();
        //? }
    }

    /** The server a player belongs to. 1.21.9 removed the shortcut on the player. */
    public static MinecraftServer serverOf(ServerPlayer player) {
        //? if >=1.21.9 {
        /*return player.level().getServer();
        *///? } else {
        return player.getServer();
        //? }
    }

    /**
     * Whether the player holds the given vanilla operator level. 1.21.9 replaced the
     * integer levels with a permission set built from the same command levels.
     */
    public static boolean hasCommandLevel(ServerPlayer player, int level) {
        //? if >=1.21.9 {
        /*return player.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(level));
        *///? } else {
        return player.hasPermissions(level);
        //? }
    }
}
