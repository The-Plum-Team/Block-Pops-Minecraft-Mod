package com.theplumteam.client;

import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Client-side copy of the plush collections whose specials the server gives this player.
 * The list only counts for the connection it arrived on, so it needs no reset on disconnect
 * and never leaks into a server that does not send one.
 */
public class ClientSpecialAccess {
    private static Set<String> collections = Set.of();
    private static WeakReference<ClientPacketListener> connection = new WeakReference<>(null);

    /**
     * Replace the collections.
     * Called when the SyncSpecialAccessPacket is received from the server.
     */
    public static void set(Collection<String> collectionIds) {
        collections = Set.copyOf(collectionIds);
        connection = new WeakReference<>(Minecraft.getInstance().getConnection());
    }

    /**
     * Check if the current server gives this player a collection's specials.
     */
    public static boolean has(String collectionId) {
        ClientPacketListener current = Minecraft.getInstance().getConnection();
        return current != null && current == connection.get() && collections.contains(collectionId);
    }

    /**
     * The figures a token spent on this collection can give this player.
     */
    public static List<FigureDefinition> figuresFor(FigureCollection collection) {
        return has(collection.getId()) ? collection.getEnabledFigures() : collection.getRegularFigures();
    }
}
