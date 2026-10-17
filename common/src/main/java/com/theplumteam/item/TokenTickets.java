package com.theplumteam.item;

import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.registry.ModItems;
import com.theplumteam.util.PlayerMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Turns a ticket used on a token machine into a token: a blue ticket into a regular token and
 * a gold one into a guaranteed token. Tickets add tokens without limit; only the tokens the
 * server grants on its own stop at their cap.
 */
public final class TokenTickets {
    private TokenTickets() {
    }

    public static boolean isTicket(ItemStack stack) {
        return stack.is(ModItems.BLUE_TICKET.get()) || stack.is(ModItems.GOLD_TICKET.get());
    }

    public static void redeem(ServerPlayer player, ItemStack ticket) {
        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
        Component message;
        if (ticket.is(ModItems.BLUE_TICKET.get())) {
            discovery.setRegularTokens(discovery.getRegularTokens() + 1);
            message = Component.literal("Regular token added (" + discovery.getRegularTokens() + ")")
                    .withStyle(ChatFormatting.BLUE);
        } else if (ticket.is(ModItems.GOLD_TICKET.get())) {
            discovery.setExtraGuaranteedTokens(discovery.getExtraGuaranteedTokens() + 1);
            message = Component.literal("Guaranteed token added (" + discovery.getGuaranteedTokens() + ")")
                    .withStyle(ChatFormatting.GOLD);
        } else {
            return;
        }
        ticket.shrink(1);
        PlayerDataManager.markDirty(player, discovery);
        ClawMachineBlock.syncTokenDataToClient(player);
        PlayerMessages.actionBar(player, message);
    }
}
