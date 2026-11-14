package com.theplumteam.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;

/**
 * Names the runtime assets the files of one player collection are loaded as.
 *
 * Unlike a World collection's, they are named under the collection as well as by content: a
 * player collection is dropped as a whole when it is revoked, and its assets go with it without
 * touching another collection that happens to hold the same file. No bundled resource lives
 * under `player/`, and World and Custom files map elsewhere.
 */
public final class PlayerAssetIds implements ContentIdStrategy {
    private static final String TEXTURE_ROOT = "textures/player/";
    private static final String ROOT = "player/";

    private final String id;

    /** The names of the assets of that player collection. */
    public PlayerAssetIds(String id) {
        if (!PlayerIds.isPlayerId(id)) {
            throw new IllegalArgumentException("Invalid player collection id");
        }
        this.id = id;
    }

    @Override
    public boolean names(String collectionId) {
        return id.equals(collectionId);
    }

    @Override
    public ResourceLocation texture(String sha) {
        return ResourceLocations.of(BlockPopsMod.MOD_ID, TEXTURE_ROOT + id + "/" + requireSha(sha) + ".png");
    }

    @Override
    public ResourceLocation model(String sha) {
        return GeoAssets.model(BlockPopsMod.MOD_ID, ROOT + id + "/" + requireSha(sha));
    }

    @Override
    public ResourceLocation animation(String sha) {
        return GeoAssets.animation(BlockPopsMod.MOD_ID, ROOT + id + "/" + requireSha(sha));
    }

    /** Whether the id is an asset of this collection. */
    public boolean owns(ResourceLocation asset) {
        return owner(asset).equals(id);
    }

    /** Whether the id is an asset of any player collection. */
    public static boolean ownsAny(ResourceLocation asset) {
        return !owner(asset).isEmpty();
    }

    // The player collection an asset id is named under, or empty when it is no player asset
    private static String owner(ResourceLocation asset) {
        if (asset == null || !BlockPopsMod.MOD_ID.equals(asset.getNamespace())) {
            return "";
        }
        String path = asset.getPath();
        for (String root : new String[] {TEXTURE_ROOT, "geo/" + ROOT, "animations/" + ROOT, ROOT}) {
            if (path.startsWith(root)) {
                int end = path.indexOf('/', root.length());
                String owner = end < 0 ? "" : path.substring(root.length(), end);
                return PlayerIds.isPlayerId(owner) ? owner : "";
            }
        }
        return "";
    }

    private static String requireSha(String sha) {
        if (!Sha256.isValidHex(sha)) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return sha;
    }
}
