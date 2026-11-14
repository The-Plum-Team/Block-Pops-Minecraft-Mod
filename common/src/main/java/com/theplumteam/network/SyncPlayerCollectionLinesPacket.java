package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.remote.player.PlayerText;
import com.theplumteam.server.PlayerCollectionEngine.Condition;
import com.theplumteam.server.PlayerCollectionEngine.Reason;
import com.theplumteam.server.PlayerCollectionSettings;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client packet for whoever manages a world's codes: every player collection added
 * to the world and where it stands, shown or not, so the settings can list one that was taken
 * out of use and offer a newer revision of one the world is pinned behind.
 *
 * <p>It is a list to read, not content: nothing is registered or loaded because of it. A name
 * here is what the server last knew a collection as, and is shown only if it is one a
 * collection can have; what a shown collection is called comes from its signed record.
 *
 * NOTE: This class must NOT import any client-side classes.
 */
public class SyncPlayerCollectionLinesPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_player_collection_lines");

    /**
     * One added collection.
     *
     * @param rev    the revision the world is pinned to
     * @param follow whether the world moves to each new revision by itself
     * @param reason why it is not shown; null when it is, or when nothing says
     * @param newest the head's revision when it is above the pinned one, 0 otherwise
     * @param name   what the collection is called, or its code when the server knows no name
     */
    public record Line(String id, String code, int rev, boolean follow, Condition condition, @Nullable Reason reason,
                       int newest, String name) {
        public Line {
            if (!PlayerIds.isPlayerId(id) || !PlayerIds.isPlayerCode(code) || rev < 1 || rev > PlayerHead.MAX_REV
                    || newest < 0 || newest > PlayerHead.MAX_REV || condition == null) {
                throw new IllegalArgumentException("Invalid player collection line");
            }
            // Anything that is not a name a collection can have is not shown as one
            if (PlayerText.nameProblem(name) != null) {
                name = code;
            }
        }
    }

    private final List<Line> lines;

    public SyncPlayerCollectionLinesPacket(List<Line> lines) {
        if (lines.size() > PlayerCollectionSettings.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many player collections: " + lines.size());
        }
        this.lines = List.copyOf(lines);
    }

    public List<Line> lines() {
        return lines;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(lines.size());
        for (Line line : lines) {
            buffer.writeUtf(line.id(), PlayerIds.ID_LENGTH);
            buffer.writeUtf(line.code(), PlayerIds.CODE_LENGTH);
            buffer.writeVarInt(line.rev());
            buffer.writeBoolean(line.follow());
            buffer.writeByte(line.condition().ordinal());
            buffer.writeByte(line.reason() == null ? -1 : line.reason().ordinal());
            buffer.writeVarInt(line.newest());
            buffer.writeUtf(line.name(), PlayerText.MAX_NAME_LENGTH);
        }
        return buffer;
    }

    public static SyncPlayerCollectionLinesPacket decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > PlayerCollectionSettings.MAX_ENTRIES) {
            throw new IllegalArgumentException("Player collection lines count out of range: " + count);
        }
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(PlayerIds.ID_LENGTH);
            String code = buffer.readUtf(PlayerIds.CODE_LENGTH);
            int rev = buffer.readVarInt();
            boolean follow = buffer.readBoolean();
            Condition condition = byOrdinal(Condition.values(), buffer.readByte());
            Reason reason = byOrdinal(Reason.values(), buffer.readByte());
            lines.add(new Line(id, code, rev, follow, condition, reason, buffer.readVarInt(),
                    buffer.readUtf(PlayerText.MAX_NAME_LENGTH)));
        }
        return new SyncPlayerCollectionLinesPacket(lines);
    }

    @Nullable
    private static <T> T byOrdinal(T[] values, int ordinal) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncPlayerCollectionLinesPacket packet = decode(buf);
        // Use fully qualified name to avoid importing client classes
        context.queue(() -> com.theplumteam.client.remote.ClientPlayerCollections.managed(packet.lines));
    }

    public static void sendToPlayer(ServerPlayer player, List<Line> lines) {
        if (PacketNetworking.canPlayerReceive(player, ID)) {
            PacketNetworking.sendToPlayer(player, ID, new SyncPlayerCollectionLinesPacket(lines).encode());
        }
    }
}
