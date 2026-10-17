package com.theplumteam.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;

import java.util.regex.Pattern;

/**
 * Names World collections and the runtime assets their files are loaded as.
 *
 * Collection ids are `w-` and a slug: the hyphen keeps them apart from bundled ids, from
 * World Players and from every Custom collection id the CDN grammar allows. Asset ids
 * are addressed by content, so two collections that share a file share its asset, and
 * every cache keyed by id stays correct when a file changes.
 */
public final class WorldContentIds {
    public static final String COLLECTION_PREFIX = "w-";
    private static final Pattern COLLECTION_ID = Pattern.compile("w-[a-z0-9_]{1,24}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9_]{1,24}");
    private static final String TEXTURE_ROOT = "textures/world_content/";
    private static final String MODEL_ROOT = "world_content/model/";
    private static final String ANIMATION_ROOT = "world_content/animation/";

    private WorldContentIds() {
    }

    /** Whether the id names a World collection. */
    public static boolean isWorldCollectionId(String id) {
        return id != null && COLLECTION_ID.matcher(id).matches();
    }

    /** Whether the text may be the slug of a collection or the id of a figure. */
    public static boolean isSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    /** The collection id of a valid slug. */
    public static String collectionId(String slug) {
        if (!isSlug(slug)) {
            throw new IllegalArgumentException("Invalid World collection slug");
        }
        return COLLECTION_PREFIX + slug;
    }

    /** The texture id a PNG is loaded as. */
    public static ResourceLocation texture(String sha) {
        return ResourceLocations.of(BlockPopsMod.MOD_ID, TEXTURE_ROOT + requireSha(sha) + ".png");
    }

    /** The model id a `.geo.json` is loaded as, in the form the active GeckoLib addresses. */
    public static ResourceLocation model(String sha) {
        return GeoAssets.model(BlockPopsMod.MOD_ID, MODEL_ROOT + requireSha(sha));
    }

    /** The animation id an `.animation.json` is loaded as. */
    public static ResourceLocation animation(String sha) {
        return GeoAssets.animation(BlockPopsMod.MOD_ID, ANIMATION_ROOT + requireSha(sha));
    }

    /**
     * Whether the id is one World content owns. Bundled resources have no `world_content`
     * directory and Custom files map elsewhere, so nothing else can match.
     */
    public static boolean owns(ResourceLocation id) {
        if (id == null || !BlockPopsMod.MOD_ID.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.startsWith(TEXTURE_ROOT) || path.startsWith("geo/world_content/")
                || path.startsWith("animations/world_content/") || path.startsWith("world_content/");
    }

    private static String requireSha(String sha) {
        if (!Sha256.isValidHex(sha)) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return sha;
    }
}
