package com.theplumteam.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
/*import net.minecraft.network.RegistryFriendlyByteBuf;
*///? }
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Adapts an encoded S2C payload to the target player's networking API. */
public final class PacketNetworking {
    private PacketNetworking() {
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload) {
        //? if >=1.21 {
        /*NetworkManager.sendToPlayer(player, id, new RegistryFriendlyByteBuf(payload, player.registryAccess()));
        *///? } else {
        NetworkManager.sendToPlayer(player, id, payload);
        //? }
    }
}
