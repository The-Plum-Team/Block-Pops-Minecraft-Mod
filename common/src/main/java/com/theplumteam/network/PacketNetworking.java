package com.theplumteam.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
/*import net.minecraft.network.RegistryFriendlyByteBuf;
*///? }
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/** Adapts payloads to the target connection's networking API. */
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

    public static void sendToServer(ResourceLocation id, Supplier<FriendlyByteBuf> encoder) {
        if (Platform.getEnvironment() != Env.CLIENT) {
            throw new IllegalStateException("Client packets can only be sent from the client environment");
        }
        EnvExecutor.runInEnv(Env.CLIENT, () -> () ->
                com.theplumteam.client.ClientPacketNetworking.sendToServer(id, encoder));
    }

    public static void registerServerS2CPayloads(ResourceLocation... ids) {
        //? if >=1.21 {
        /*if (Platform.getEnvironment() == Env.SERVER) {
            for (ResourceLocation id : ids) {
                NetworkManager.registerS2CPayloadType(id);
            }
        }
        *///? }
    }
}
