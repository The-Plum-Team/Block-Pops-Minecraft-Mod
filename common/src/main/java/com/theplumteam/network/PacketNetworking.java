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

    //? if >=26 {
    /*// Architectury 21 dropped the raw-identifier networking API in favour of
    // CustomPacketPayload. Every packet here already encodes itself into a byte
    // buffer, so one payload carries those bytes unchanged and each packet keeps
    // its own id as the payload type.
    public record RawPayload(net.minecraft.resources.Identifier id, byte[] data)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawPayload> type() {
            return new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
        }
    }

    public static net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, RawPayload>
            rawCodec(net.minecraft.resources.Identifier id) {
        return net.minecraft.network.codec.StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.data()),
                buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    return new RawPayload(id, bytes);
                });
    }

    public static byte[] drain(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }
    *///? }

    /** Registers a receiver for one packet id on the given side. */
    public static void registerReceiver(NetworkManager.Side side, ResourceLocation id,
                                        NetworkManager.NetworkReceiver<
                                                //? if >=1.21 {
                                                /*RegistryFriendlyByteBuf
                                                *///? } else {
                                                FriendlyByteBuf
                                                //? }
                                                > receiver) {
        //? if >=26 {
        /*NetworkManager.registerReceiver(side,
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawPayload>(id),
                rawCodec(id),
                (payload, context) -> receiver.receive(new RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(payload.data()), context.registryAccess()), context));
        *///? } else {
        NetworkManager.registerReceiver(side, id, receiver);
        //? }
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload) {
        //? if >=26 {
        /*NetworkManager.sendToPlayer(player, new RawPayload(id, drain(payload)));
        *///? } elif >=1.21 {
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
        //? if >=26 {
        /*if (Platform.getEnvironment() == Env.SERVER) {
            for (ResourceLocation id : ids) {
                NetworkManager.registerS2CPayloadType(
                        new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RawPayload>(id),
                        rawCodec(id));
            }
        }
        *///? } elif >=1.21 {
        /*if (Platform.getEnvironment() == Env.SERVER) {
            for (ResourceLocation id : ids) {
                NetworkManager.registerS2CPayloadType(id);
            }
        }
        *///? }
    }
}
