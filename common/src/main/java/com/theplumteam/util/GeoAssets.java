package com.theplumteam.util;

import net.minecraft.resources.ResourceLocation;

/**
 * Addresses GeckoLib models and animations the way the active GeckoLib expects.
 *
 * GeckoLib 4 reads models from the `geo` root and animations from the `animations`
 * root, and is addressed by the complete resource path, extension included.
 * GeckoLib 5 reads both from `geckolib/models` and `geckolib/animations` and is
 * addressed by the bare path with no extension. Call sites always name the bare
 * path, so only this class knows which layout is in use.
 */
public final class GeoAssets {
    private GeoAssets() {
    }

    /** Addresses a model such as `block/box_block`. */
    public static ResourceLocation model(String namespace, String path) {
        //? if >=1.21.5 {
        /*return ResourceLocations.of(namespace, path);
        *///? } else {
        return ResourceLocations.of(namespace, "geo/" + path + ".geo.json");
        //? }
    }

    /** Addresses an animation such as `figure/figure_poses`. */
    public static ResourceLocation animation(String namespace, String path) {
        //? if >=1.21.5 {
        /*return ResourceLocations.of(namespace, path);
        *///? } else {
        return ResourceLocations.of(namespace, "animations/" + path + ".animation.json");
        //? }
    }

    /**
     * Normalises an id declared in collection data, which is authored once in the
     * GeckoLib 4 form and has to keep working on every version.
     */
    public static ResourceLocation declared(ResourceLocation id) {
        //? if >=1.21.5 {
        /*if (id == null) {
            return null;
        }
        String path = id.getPath();
        if (path.startsWith("geo/")) {
            path = path.substring("geo/".length());
        } else if (path.startsWith("animations/")) {
            path = path.substring("animations/".length());
        }
        if (path.endsWith(".geo.json")) {
            path = path.substring(0, path.length() - ".geo.json".length());
        } else if (path.endsWith(".animation.json")) {
            path = path.substring(0, path.length() - ".animation.json".length());
        }
        return ResourceLocations.of(id.getNamespace(), path);
        *///? } else {
        return id;
        //? }
    }
}
