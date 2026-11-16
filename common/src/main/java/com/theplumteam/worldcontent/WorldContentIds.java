package com.theplumteam.worldcontent;

import java.util.regex.Pattern;

/**
 * Names World collections and their figures.
 *
 * Collection ids are `w-` and a slug: the hyphen keeps them apart from bundled ids, from
 * World Players and from every Custom collection id the CDN grammar allows. The runtime assets
 * their files are loaded as are named by {@link WorldAssetIds}.
 */
public final class WorldContentIds {
    public static final String COLLECTION_PREFIX = "w-";
    private static final Pattern COLLECTION_ID = Pattern.compile("w-[a-z0-9_]{1,24}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9_]{1,24}");

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
}
