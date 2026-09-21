package com.theplumteam.util;

import software.bernie.geckolib.cache.object.GeoBone;

/** Bone accessors GeckoLib 5.4 renamed to record form. */
public final class GeoBones {
    private GeoBones() {
    }

    public static String name(GeoBone bone) {
        //? if >=1.21.9 {
        /*return bone.name();
        *///? } else {
        return bone.getName();
        //? }
    }
}
