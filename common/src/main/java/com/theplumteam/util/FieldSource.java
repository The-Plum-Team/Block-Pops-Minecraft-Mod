package com.theplumteam.util;

import net.minecraft.nbt.CompoundTag;
//? if >=1.21.6 {
/*import net.minecraft.world.level.storage.ValueInput;
*///? }

/**
 * Reads a block entity's own fields from whichever source the active version hands it.
 *
 * 1.21.6 replaced the `CompoundTag` that `loadAdditional` received by a `ValueInput`,
 * while the same fields still arrive as a raw tag when an item stack carries them.
 * Every read states the value to keep when the key is absent, so a missing key leaves
 * the field exactly as it was, which is what the `contains` guards used to express.
 */
public final class FieldSource {
    private final CompoundTag tag;
    //? if >=1.21.6 {
    /*private final ValueInput input;

    public FieldSource(ValueInput input) {
        this.input = input;
        this.tag = null;
    }
    *///? }

    public FieldSource(CompoundTag tag) {
        this.tag = tag;
        //? if >=1.21.6 {
        /*this.input = null;
        *///? }
    }

    public boolean getBoolean(String key, boolean fallback) {
        //? if >=1.21.6 {
        /*if (input != null) {
            return input.getBooleanOr(key, fallback);
        }
        *///? }
        return TagReads.bool(tag, key, fallback);
    }

    public int getInt(String key, int fallback) {
        //? if >=1.21.6 {
        /*if (input != null) {
            return input.getIntOr(key, fallback);
        }
        *///? }
        return TagReads.integer(tag, key, fallback);
    }

    public double getDouble(String key, double fallback) {
        //? if >=1.21.6 {
        /*if (input != null) {
            return input.getDoubleOr(key, fallback);
        }
        *///? }
        return TagReads.dbl(tag, key, fallback);
    }

    public String getString(String key, String fallback) {
        //? if >=1.21.6 {
        /*if (input != null) {
            return input.getStringOr(key, fallback);
        }
        *///? }
        return TagReads.string(tag, key, fallback);
    }

    /** Answers null when the key is absent, for fields whose absence is meaningful. */
    public String getStringOrNull(String key) {
        //? if >=1.21.6 {
        /*if (input != null) {
            return input.getString(key).orElse(null);
        }
        *///? }
        return TagReads.hasString(tag, key) ? TagReads.string(tag, key, "") : null;
    }

    /** Answers null when the key is absent, for fields whose absence is meaningful. */
    public Double getDoubleOrNull(String key) {
        //? if >=1.21.6 {
        /*if (input != null) {
            // ValueInput has no optional double read, so absence is probed with a value
            // no stored coordinate or scale can hold.
            double probe = input.getDoubleOr(key, Double.NaN);
            return Double.isNaN(probe) ? null : probe;
        }
        *///? }
        return tag.contains(key) ? TagReads.dbl(tag, key, 0.0) : null;
    }
}
