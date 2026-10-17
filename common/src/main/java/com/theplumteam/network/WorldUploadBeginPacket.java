package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldUploads;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldContentStatus;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-to-server packet in which an operator announces a file for a World collection:
 * its type, size and sha. The server answers READY with an upload id, ALREADY_STORED
 * when it has the file, or why not, in a {@link WorldCollectionStatusPacket}.
 */
public class WorldUploadBeginPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldUploadBeginPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_upload_begin");

    private final int requestId;
    private final int type;
    private final int size;
    private final String sha;

    public WorldUploadBeginPacket(int requestId, BlobType type, int size, String sha) {
        this(requestId, type.id(), size, sha);
    }

    private WorldUploadBeginPacket(int requestId, int type, int size, String sha) {
        this.requestId = requestId;
        this.type = type;
        this.size = size;
        this.sha = sha;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(requestId);
        buffer.writeByte(type);
        buffer.writeVarInt(size);
        buffer.writeUtf(sha, WorldPacketFields.MAX_SHA);
        return buffer;
    }

    public static WorldUploadBeginPacket decode(FriendlyByteBuf buffer) {
        int requestId = buffer.readVarInt();
        int type = buffer.readUnsignedByte();
        int size = buffer.readVarInt();
        return new WorldUploadBeginPacket(requestId, type, size, WorldPacketFields.readSha(buffer));
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldUploadBeginPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!ServerLevels.hasCommandLevel(player, 2)) {
                LOGGER.warn("Player {} tried to upload a World collection file without permission",
                        player.getName().getString());
                WorldCollectionStatusPacket.send(player, packet.requestId,
                        WorldContentStatus.of(WorldContentStatus.Result.NOT_ALLOWED), 0, packet.sha);
                return;
            }
            // An unknown type reaches the upload check as null, which refuses it
            WorldUploads.begin(player, packet.requestId, BlobType.byId(packet.type), packet.size, packet.sha);
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
