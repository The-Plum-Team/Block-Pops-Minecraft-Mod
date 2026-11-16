package com.theplumteam.worldcontent;

import net.minecraft.resources.ResourceLocation;

/**
 * How one family of collections made from a record names itself: which collection ids are its
 * own, and the runtime asset each of its files is loaded as. A World collection and a player
 * collection are built from the same record by the same code and differ only in this, so
 * neither's assets can be taken for the other's.
 */
public interface ContentIdStrategy {
    /** World collections: `w-` ids, and assets shared by content between collections. */
    ContentIdStrategy WORLD = new ContentIdStrategy() {
        @Override
        public boolean names(String collectionId) {
            return WorldContentIds.isWorldCollectionId(collectionId);
        }

        @Override
        public ResourceLocation texture(String sha) {
            return WorldAssetIds.texture(sha);
        }

        @Override
        public ResourceLocation model(String sha) {
            return WorldAssetIds.model(sha);
        }

        @Override
        public ResourceLocation animation(String sha) {
            return WorldAssetIds.animation(sha);
        }
    };

    /** Whether the id is one a collection of this family may have. */
    boolean names(String collectionId);

    /** The texture id a PNG is loaded as. */
    ResourceLocation texture(String sha);

    /** The model id a `.geo.json` is loaded as. */
    ResourceLocation model(String sha);

    /** The animation id an `.animation.json` is loaded as. */
    ResourceLocation animation(String sha);
}
