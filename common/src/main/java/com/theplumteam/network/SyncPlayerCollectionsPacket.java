package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.server.PlayerCollectionSettings;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.Sha256;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-to-client packet with the player collections this world shows. It carries pointers,
 * never content: each collection's id and code, the revision the world is pinned to with the
 * SHA-256 that names its index, and the collection's signed head exactly as it was published.
 *
 * <p>A client believes nothing here but what a signature covers. It verifies the head, loads
 * the index by its SHA-256 from the CDN at a path it computes itself, and requires that index
 * to be signed and to be of that collection and revision. There is no path, no definition and
 * no author in the packet.
 *
 * <p>Player collections travel only in this packet, and only to a client that said it loads
 * them ({@link PlayerCollectionsHelloPacket}).
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class SyncPlayerCollectionsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_player_collections");

    /**
     * One collection the world shows.
     *
     * @param rev         the revision the world is pinned to
     * @param indexSha256 the SHA-256 that names that revision's index
     * @param head        the envelope the collection's current head was published in
     */
    public record Entry(String id, String code, int rev, String indexSha256, byte[] head) {
        public Entry {
            if (!PlayerIds.isPlayerId(id) || !PlayerIds.isPlayerCode(code) || rev < 1 || rev > PlayerHead.MAX_REV
                    || !Sha256.isValidHex(indexSha256) || head.length == 0 || head.length > PlayerHead.ENVELOPE_BYTES) {
                throw new IllegalArgumentException("Invalid player collection entry");
            }
        }
    }

    private final List<Entry> entries;

    public SyncPlayerCollectionsPacket(List<Entry> entries) {
        if (entries.size() > PlayerCollectionSettings.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many player collections: " + entries.size());
        }
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("A player collection is listed twice");
            }
        }
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeUtf(entry.id(), PlayerIds.ID_LENGTH);
            buffer.writeUtf(entry.code(), PlayerIds.CODE_LENGTH);
            buffer.writeVarInt(entry.rev());
            buffer.writeUtf(entry.indexSha256(), 64);
            buffer.writeByteArray(entry.head());
        }
        return buffer;
    }

    /** Reads a packet; one that names anything but well-formed pointers is refused whole. */
    public static SyncPlayerCollectionsPacket decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > PlayerCollectionSettings.MAX_ENTRIES) {
            throw new IllegalArgumentException("Player collections packet count out of range: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buffer.readUtf(PlayerIds.ID_LENGTH), buffer.readUtf(PlayerIds.CODE_LENGTH), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readByteArray(PlayerHead.ENVELOPE_BYTES)));
        }
        return new SyncPlayerCollectionsPacket(entries);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncPlayerCollectionsPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.remote.ClientPlayerCollections.apply(packet.entries));
    }

    public static void sendToPlayer(ServerPlayer player, List<Entry> entries) {
        PacketNetworking.sendToPlayer(player, ID, new SyncPlayerCollectionsPacket(entries).encode());
    }
}
