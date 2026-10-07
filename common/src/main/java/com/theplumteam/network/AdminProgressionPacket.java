package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Client-to-server request from the admin progression editor: view an online player's
 * discovery, or re-lock / unlock one whole collection for that player. Only discovery is
 * edited; tokens, favorite colors, skin snapshots and boxes already handed out are untouched.
 * Requires operator permissions.
 */
public class AdminProgressionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminProgressionPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "admin_progression");

    public static final int VIEW = 0;
    public static final int LOCK = 1;
    public static final int UNLOCK = 2;
    private static final int MAX_COLLECTION_ID_LENGTH = 256;

    private final UUID target;
    private final int action;
    private final String collectionId;

    public AdminProgressionPacket(UUID target, int action, String collectionId) {
        this.target = target;
        this.action = action;
        this.collectionId = collectionId;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUUID(target);
        buffer.writeByte(action);
        buffer.writeUtf(collectionId, MAX_COLLECTION_ID_LENGTH);
        return buffer;
    }

    public static AdminProgressionPacket decode(FriendlyByteBuf buffer) {
        UUID target = buffer.readUUID();
        int action = buffer.readByte();
        String collectionId = buffer.readUtf(MAX_COLLECTION_ID_LENGTH);
        return new AdminProgressionPacket(target, action, collectionId);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        AdminProgressionPacket packet = decode(buf);

        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer admin)) {
                return;
            }
            // Check permissions (Level 2 = OP/Cheats). The client check only hides the UI.
            if (!ServerLevels.hasCommandLevel(admin, 2)) {
                LOGGER.warn("Player {} tried to edit progression without permission", admin.getName().getString());
                return;
            }
            MinecraftServer server = ServerLevels.serverOf(admin);
            if (server == null) {
                return;
            }

            ServerPlayer target = server.getPlayerList().getPlayer(packet.target);
            if (target != null && packet.action != VIEW) {
                apply(admin, target, packet.action, packet.collectionId);
            }
            // A target that left is never edited and never replaced by another player:
            // the answer names the requested player and the editor shows that they left.
            AdminProgressionSyncPacket.sendToPlayer(admin, server, packet.target, target);
        });
    }

    private static void apply(ServerPlayer admin, ServerPlayer target, int action, String collectionId) {
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection == null || (action != LOCK && action != UNLOCK)) {
            LOGGER.warn("Player {} sent an invalid progression edit: action {} collection {}",
                    admin.getName().getString(), action, collectionId);
            return;
        }

        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(target);
        Set<String> edited = edit(discovery.getDiscoveredSet(), collection, action == LOCK);
        if (edited == null) {
            return;
        }
        discovery.syncFrom(edited);
        PlayerDataManager.markDirty(target, discovery);
        // The target's collection screen reads this cache every frame, so it updates at once.
        SyncDiscoveryDataPacket.sendToPlayer(target, discovery.getDiscoveredSet(),
                discovery.getAllFigureSkins(), discovery.getAllFigureQuickSkins());
        LOGGER.info("{} {} collection {} for {}", admin.getName().getString(),
                action == LOCK ? "re-locked" : "unlocked", collectionId, target.getName().getString());
    }

    /**
     * Returns the discovery set after locking or unlocking one collection, or null when
     * nothing changes. Discoveries of every other collection are kept as they are.
     */
    private static Set<String> edit(Set<String> discovered, FigureCollection collection, boolean lock) {
        Set<String> edited = new HashSet<>(discovered);
        String prefix = collection.getId() + ":";
        boolean changed = false;
        if (lock) {
            // By prefix, so figures that have since left the collection are locked too.
            changed = edited.removeIf(figureId -> figureId.startsWith(prefix));
        } else {
            // Players disabled in the World Players roster stay locked, as in the Cheats unlock
            for (FigureDefinition figure : collection.getEnabledFigures()) {
                changed |= edited.add(prefix + figure.getId());
            }
        }
        return changed ? edited : null;
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
