package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-to-client answer for the admin progression editor: who is online, which player
 * the editor asked about, and that player's discovered figures. It is only ever sent to
 * an operator, and it is kept apart from the receiving admin's own discovery cache.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class AdminProgressionSyncPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "admin_progression_sync");

    /** The last answer this client received; the editor screen draws from it. */
    private static volatile AdminProgressionSyncPacket latest;

    private final List<UUID> playerIds;
    private final List<String> playerNames;
    private final UUID target;
    private final Set<String> discovered;

    public AdminProgressionSyncPacket(List<UUID> playerIds, List<String> playerNames, UUID target, Set<String> discovered) {
        this.playerIds = playerIds;
        this.playerNames = playerNames;
        this.target = target;
        this.discovered = discovered;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(playerIds.size());
        for (int i = 0; i < playerIds.size(); i++) {
            buffer.writeUUID(playerIds.get(i));
            buffer.writeUtf(playerNames.get(i));
        }
        buffer.writeUUID(target);
        buffer.writeInt(discovered.size());
        for (String figureId : discovered) {
            buffer.writeUtf(figureId);
        }
        return buffer;
    }

    public static AdminProgressionSyncPacket decode(FriendlyByteBuf buffer) {
        int players = buffer.readInt();
        List<UUID> playerIds = new ArrayList<>();
        List<String> playerNames = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            playerIds.add(buffer.readUUID());
            playerNames.add(buffer.readUtf());
        }
        UUID target = buffer.readUUID();
        int figures = buffer.readInt();
        Set<String> discovered = new HashSet<>();
        for (int i = 0; i < figures; i++) {
            discovered.add(buffer.readUtf());
        }
        return new AdminProgressionSyncPacket(playerIds, playerNames, target, discovered);
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        AdminProgressionSyncPacket packet = decode(buf);
        context.queue(() -> latest = packet);
    }

    /**
     * Answers the admin about the requested player. A null target means that player is
     * not online: the answer then carries no discoveries, and the id is missing from the
     * player list, which is how the editor knows they left.
     */
    public static void sendToPlayer(ServerPlayer admin, MinecraftServer server, UUID targetId, @Nullable ServerPlayer target) {
        List<UUID> playerIds = new ArrayList<>();
        List<String> playerNames = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            playerIds.add(player.getUUID());
            playerNames.add(player.getName().getString());
        }
        Set<String> discovered = target != null
                ? PlayerDataManager.getDiscovery(target).getDiscoveredSet()
                : Collections.emptySet();
        AdminProgressionSyncPacket packet = new AdminProgressionSyncPacket(playerIds, playerNames, targetId, discovered);
        PacketNetworking.sendToPlayer(admin, ID, packet.encode());
    }

    @Nullable
    public static AdminProgressionSyncPacket latest() {
        return latest;
    }

    public static void clearLatest() {
        latest = null;
    }

    public List<UUID> getPlayerIds() {
        return playerIds;
    }

    public List<String> getPlayerNames() {
        return playerNames;
    }

    public UUID getTarget() {
        return target;
    }

    public Set<String> getDiscovered() {
        return discovered;
    }
}
