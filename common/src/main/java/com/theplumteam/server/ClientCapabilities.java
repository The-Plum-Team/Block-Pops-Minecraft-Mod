package com.theplumteam.server;

import com.theplumteam.network.PacketNetworking;
import com.theplumteam.network.SyncPlayerCollectionsPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each connected client said it can do. A client from before player collections says
 * nothing, and so is never sent one: it could not load their models, and it registers what
 * it is sent without a check.
 */
public final class ClientCapabilities {
    /** The first protocol of player collections. */
    public static final int PLAYER_COLLECTIONS = 1;
    private static final Map<UUID, Integer> PROTOCOLS = new ConcurrentHashMap<>();

    private ClientCapabilities() {
    }

    /** Records what a client announced after joining. */
    public static void hello(ServerPlayer player, int protocol) {
        PROTOCOLS.put(player.getUUID(), protocol);
    }

    /** Whether the player's client loads player collections. */
    public static boolean playerCollections(ServerPlayer player) {
        return PROTOCOLS.getOrDefault(player.getUUID(), 0) >= PLAYER_COLLECTIONS
                && PacketNetworking.canPlayerReceive(player, SyncPlayerCollectionsPacket.ID);
    }

    /** Forgets a client that left: the next one to join as that player speaks for itself. */
    public static void forget(ServerPlayer player) {
        PROTOCOLS.remove(player.getUUID());
    }

    public static void clear() {
        PROTOCOLS.clear();
    }
}
