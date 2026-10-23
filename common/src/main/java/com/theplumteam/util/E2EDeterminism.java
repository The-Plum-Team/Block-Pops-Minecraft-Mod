package com.theplumteam.util;

import java.util.Random;

/**
 * Holds every clock and random choice still that the packaged E2E photographs.
 *
 * Only the packaged E2E passes {@code blockpops.e2e.enabled} to the client and server JVMs.
 * With it, the same screen, block or item renders the same pixels on every run and loader,
 * so an unchanged capture stays byte-identical to its reference and needs no model review.
 * Quick Skin pins its panorama clock the same way. Players never set the property and keep
 * the live animations and random draws.
 */
public final class E2EDeterminism {
    public static final boolean ENABLED = Boolean.getBoolean("blockpops.e2e.enabled");

    // Any fixed seed works; it only has to be the same on every run.
    private static final long SEED = 0x426c6f636bL;

    private E2EDeterminism() {}

    /** The scroll clock, in seconds, a screen's star background uses. */
    public static double scrollSeconds(double liveSeconds) {
        return ENABLED ? 0.0 : liveSeconds;
    }

    /** The GeckoLib animation tick of a box, figure or claw machine. */
    public static double animationTick(double liveTick) {
        return ENABLED ? 0.0 : liveTick;
    }

    /** The source of a claw-machine draw: seeded in the packaged E2E, otherwise fresh. */
    public static Random random() {
        return ENABLED ? new Random(SEED) : new Random();
    }
}
