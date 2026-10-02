package com.theplumteam.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Set;

/**
 * Client-side copy of the collections the server hides from the collection list.
 * The list only counts for the connection it arrived on, so it needs no reset on disconnect
 * and never leaks into a server that does not send one.
 */
public class ClientHiddenCollections {
    private static Set<String> hiddenCollections = Set.of();
    private static WeakReference<ClientPacketListener> connection = new WeakReference<>(null);
    private static int revision = 0;

    /**
     * Replace the hidden collections.
     * Called when the SyncHiddenCollectionsPacket is received from the server.
     */
    public static void set(Collection<String> collectionIds) {
        hiddenCollections = Set.copyOf(collectionIds);
        connection = new WeakReference<>(Minecraft.getInstance().getConnection());
        revision++;
    }

    /**
     * Check if the current server has sent its hidden collections.
     * A server on an older build never does, and cannot receive changes to them either.
     */
    public static boolean isSynced() {
        ClientPacketListener current = Minecraft.getInstance().getConnection();
        return current != null && current == connection.get();
    }

    /**
     * Check if the current server hides a collection from the collection list.
     */
    public static boolean isHidden(String collectionId) {
        return isSynced() && hiddenCollections.contains(collectionId);
    }

    /**
     * Changes every time the server sends its list, so open screens know when to refresh.
     */
    public static int getRevision() {
        return revision;
    }
}
