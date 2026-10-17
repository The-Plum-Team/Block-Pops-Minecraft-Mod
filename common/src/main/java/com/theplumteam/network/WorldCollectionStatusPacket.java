package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.WorldContentStatus;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client answer to an upload step or an edit of World collections: what
 * happened, for which request, and the upload and file it is about.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class WorldCollectionStatusPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_collection_status");

    private final int requestId;
    private final WorldContentStatus status;
    private final int uploadId;
    private final String sha;

    /**
     * @param requestId the request this answers, as the client numbered it
     * @param uploadId  the upload this is about, or 0
     * @param sha       the file this is about, or empty
     */
    public WorldCollectionStatusPacket(int requestId, WorldContentStatus status, int uploadId, String sha) {
        this.requestId = requestId;
        this.status = status;
        this.uploadId = uploadId;
        this.sha = sha;
    }

    public int requestId() {
        return requestId;
    }

    public WorldContentStatus status() {
        return status;
    }

    public int uploadId() {
        return uploadId;
    }

    public String sha() {
        return sha;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(requestId);
        buffer.writeEnum(status.result());
        buffer.writeVarInt(uploadId);
        buffer.writeUtf(sha, WorldPacketFields.MAX_SHA);
        buffer.writeUtf(status.detail(), WorldPacketFields.MAX_DETAIL);
        return buffer;
    }

    public static WorldCollectionStatusPacket decode(FriendlyByteBuf buffer) {
        int requestId = buffer.readVarInt();
        WorldContentStatus.Result result = buffer.readEnum(WorldContentStatus.Result.class);
        int uploadId = buffer.readVarInt();
        String sha = WorldPacketFields.readOptionalSha(buffer);
        String detail = buffer.readUtf(WorldPacketFields.MAX_DETAIL);
        return new WorldCollectionStatusPacket(requestId, WorldContentStatus.of(result, detail), uploadId, sha);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldCollectionStatusPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.worldcontent.ClientWorldUploads.onStatus(packet));
    }

    public static void send(ServerPlayer player, int requestId, WorldContentStatus status, int uploadId, String sha) {
        if (WorldPacketFields.canReceive(player, ID)) {
            PacketNetworking.sendToPlayer(player, ID,
                    new WorldCollectionStatusPacket(requestId, status, uploadId, sha == null ? "" : sha).encode());
        }
    }
}
