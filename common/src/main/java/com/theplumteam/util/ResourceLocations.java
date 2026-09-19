package com.theplumteam.util;

import net.minecraft.resources.ResourceLocation;

/** Keeps namespace/path construction consistent across Minecraft API versions. */
public final class ResourceLocations {
    private ResourceLocations() {
    }

    public static ResourceLocation of(String namespace, String path) {
        //? if >=1.21 {
        /*return ResourceLocation.fromNamespaceAndPath(namespace, path);
        *///? } else {
        return new ResourceLocation(namespace, path);
        //? }
    }
}
