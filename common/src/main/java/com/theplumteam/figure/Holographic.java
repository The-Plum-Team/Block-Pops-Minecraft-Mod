package com.theplumteam.figure;

import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.util.TagReads;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A drawn figure or plush comes out holographic one time in 25. The flag travels with it under
 * {@link #KEY}: in the box item, the box, the figure taken out of it, the placed figure and
 * plush, and the items they drop. The client draws a holographic one with its own shader.
 */
public final class Holographic {
    public static final String KEY = "Holographic";
    public static final float CHANCE = 0.04F;

    private Holographic() {
    }

    /** Rolls one draw. The packaged E2E never gets one, so its captures stay as they are. */
    public static boolean roll() {
        return !E2EDeterminism.ENABLED && ThreadLocalRandom.current().nextFloat() < CHANCE;
    }

    /** Whether a block-entity tag, as an item or a block carries it, is a holographic one. */
    public static boolean of(@Nullable CompoundTag tag) {
        return tag != null && TagReads.bool(tag, KEY, false);
    }
}
