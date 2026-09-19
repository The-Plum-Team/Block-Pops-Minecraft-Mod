package com.theplumteam.client;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
/*import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
*///? }
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

/** Captures the client connection before allocating a C2S payload. */
public final class ClientPacketNetworking {
    private ClientPacketNetworking() {
    }

    public static void sendToServer(ResourceLocation id, Supplier<FriendlyByteBuf> encoder) {
        sendToServer(Minecraft.getInstance().getConnection(), id, encoder);
    }

    static void sendToServer(ClientPacketListener connection, ResourceLocation id, Supplier<FriendlyByteBuf> encoder) {
        if (connection == null) {
            throw new IllegalStateException("Unable to send packet to the server while not in game!");
        }
        //? if >=1.21 {
        /*RegistryAccess registries = connection.registryAccess();
        RegistryFriendlyByteBuf payload = new RegistryFriendlyByteBuf(encoder.get(), registries);
        *///? } else {
        FriendlyByteBuf payload = encoder.get();
        //? }
        NetworkManager.collectPackets(connection::send, NetworkManager.c2s(), id, payload);
    }
}
