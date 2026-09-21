package com.theplumteam.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Sends a player a message above their hotbar.
 *
 * 26.1 removed Player#displayClientMessage. Its action-bar flag survives on
 * ServerPlayer#sendSystemMessage, which is where this call always ended up:
 * the base implementation did nothing on a client-side player.
 */
public final class PlayerMessages {
    private PlayerMessages() {
    }

    public static void actionBar(Player player, Component message) {
        //? if >=26 {
        /*if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        }
        *///? } else {
        player.displayClientMessage(message, true);
        //? }
    }
}
