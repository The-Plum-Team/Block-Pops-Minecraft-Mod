package com.theplumteam.util;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Hands a player an item. */
public final class PlayerItems {
    private PlayerItems() {
    }

    /**
     * Puts the stack in the player's inventory, or drops what does not fit at their feet, so
     * a prize the player paid for is never lost to a full inventory. Server side.
     */
    public static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            //? if >=26.3 {
            /*player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            *///? } else {
            player.drop(stack, false);
            //? }
        }
    }
}
