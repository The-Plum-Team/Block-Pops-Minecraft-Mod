package com.theplumteam.figure;

import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.worldcontent.WorldContentIds;

/**
 * Which family a collection belongs to, by its id. Every family has its own way to a client
 * and its own checks on arrival, so the one question "whose is this id" is asked here and
 * nowhere tests a prefix by hand.
 *
 * <ul>
 *   <li>Built-in collections ship with the game or are made by it, as World Players is.</li>
 *   <li>Custom collections come from the CDN's official tree.</li>
 *   <li>World collections ({@code w-}) are authored by a world's operators.</li>
 *   <li>Player collections ({@code p_}) are published by players.</li>
 *   <li>A preview is the creator's own, and never leaves the client that made it.</li>
 * </ul>
 */
public final class CollectionFamilies {
    /** The families a collection id can belong to. */
    public enum Family {
        BUILT_IN, CUSTOM, WORLD, PLAYER, PREVIEW
    }

    private CollectionFamilies() {
    }

    /**
     * The family of an id, as the server knows it. An id in the space reserved for player
     * collections is a player collection's whether or not it is a valid one.
     */
    public static Family family(String collectionId) {
        if (collectionId == null) {
            return Family.BUILT_IN;
        }
        if (collectionId.startsWith(CollectionRegistry.PREVIEW_PREFIX)) {
            return Family.PREVIEW;
        }
        if (WorldContentIds.isWorldCollectionId(collectionId)) {
            return Family.WORLD;
        }
        if (PlayerIds.isReserved(collectionId)) {
            return Family.PLAYER;
        }
        return CustomCollections.isCustom(collectionId) ? Family.CUSTOM : Family.BUILT_IN;
    }

    /**
     * Whether the collection is built from a record, as World and player collections are: its
     * figures are handed out as figure blocks, and its box shows its own art.
     */
    public static boolean isMaterialized(String collectionId) {
        return WorldContentIds.isWorldCollectionId(collectionId) || PlayerIds.isPlayerId(collectionId);
    }

    /**
     * Whether the shared collection sync may carry the collection. It carries only what every
     * client of every version can take without a check: never a Custom, a World or a player
     * collection, each of which travels in its family's own packet to clients that can load it.
     */
    public static boolean genericSyncAllows(String collectionId) {
        Family family = family(collectionId);
        return family == Family.BUILT_IN;
    }
}
