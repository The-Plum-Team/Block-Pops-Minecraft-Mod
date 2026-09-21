package com.theplumteam.util;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

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
    public static boolean hasCommandLevel(Player player, int level) {
        //? if >=1.21.9 {
        /*return player.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
        *///? } else {
        return player.hasPermissions(level);
        //? }
    }

    /**
     * Whether a command source holds the given vanilla operator level. The same
     * 1.21.9 permission change reaches commands through their own source stack.
     */
    public static boolean hasCommandLevel(CommandSourceStack source, int level) {
        //? if >=1.21.9 {
        /*return source.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
        *///? } else {
        return source.hasPermission(level);
        //? }
    }

    /**
     * The authlib session service behind a player's server. 1.21.9 folded the
     * shortcut on MinecraftServer into its Services record.
     */
    public static MinecraftSessionService sessionServiceOf(ServerPlayer player) {
        //? if >=1.21.9 {
        /*return serverOf(player).services().sessionService();
        *///? } else {
        return serverOf(player).getSessionService();
        //? }
    }
}
