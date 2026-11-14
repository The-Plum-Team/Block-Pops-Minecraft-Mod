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

    /**
     * Whether the server this client is connected to takes a client-to-server packet. From
     * 1.21 a server that never registered it is not sent it, and some loaders refuse to. Before
     * 1.21 there is no telling, and a server that does not know a packet drops it.
     */
    public static boolean canServerReceive(ResourceLocation id) {
        //? if >=26 {
        /*return NetworkManager.canServerReceive(
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<
                        com.theplumteam.network.PacketNetworking.RawPayload>(id));
        *///? } elif >=1.21 {
        /*return NetworkManager.canServerReceive(id);
        *///? } else {
        return true;
        //? }
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
        //? if >=26 {
        /*NetworkManager.collectPackets(connection::send, NetworkManager.c2s(),
                new com.theplumteam.network.PacketNetworking.RawPayload(
                        id, com.theplumteam.network.PacketNetworking.drain(payload)),
                registries);
        *///? } else {
        NetworkManager.collectPackets(connection::send, NetworkManager.c2s(), id, payload);
        //? }
    }
}
