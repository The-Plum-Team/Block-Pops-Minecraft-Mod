package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldAssetSender;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server packet in which a player that received the World collections asks
 * for the files it still needs. Any player may ask, but only for files a current
 * collection names, each at most once per connection and once more after the client
 * failed to verify it.
 */
public class WorldAssetRequestPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_asset_request");
    /** The most files one packet asks for. */
    public static final int MAX_REQUESTS = 64;

    /**
     * One file.
     *
     * @param retry whether the client already received this file and could not verify it
     */
    public record Request(String sha, boolean retry) {
    }

    private final List<Request> requests;

    public WorldAssetRequestPacket(List<Request> requests) {
        if (requests.size() > MAX_REQUESTS) {
            throw new IllegalArgumentException("At most " + MAX_REQUESTS + " World collection files per request");
        }
        this.requests = List.copyOf(requests);
    }

    public List<Request> requests() {
        return requests;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(requests.size());
        for (Request request : requests) {
            buffer.writeUtf(request.sha(), WorldPacketFields.MAX_SHA);
            buffer.writeBoolean(request.retry());
        }
        return buffer;
    }

    public static WorldAssetRequestPacket decode(FriendlyByteBuf buffer) {
        int count = WorldPacketFields.bounded(buffer.readVarInt(), 0, MAX_REQUESTS, "request count");
        List<Request> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String sha = WorldPacketFields.readSha(buffer);
            requests.add(new Request(sha, buffer.readBoolean()));
        }
        return new WorldAssetRequestPacket(requests);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldAssetRequestPacket packet = decode(buf);
        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                WorldAssetSender.request(player, packet.requests);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
