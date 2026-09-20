package com.theplumteam.util;

import net.minecraft.nbt.CompoundTag;
//? if >=1.21.6 {
/*import net.minecraft.world.level.storage.ValueOutput;
*///? }

/**
 * Writes a block entity's own fields to whichever sink the active version hands it.
 *
 * 1.21.6 replaced the `CompoundTag` that `saveAdditional` received by a `ValueOutput`.
 * The keys and values written are identical either way, so each block entity keeps one
 * body and only the wrapper around it differs.
 */
public final class FieldSink {
    private final CompoundTag tag;
    //? if >=1.21.6 {
    /*private final ValueOutput output;

    public FieldSink(ValueOutput output) {
        this.output = output;
        this.tag = null;
    }
    *///? }

    public FieldSink(CompoundTag tag) {
        this.tag = tag;
        //? if >=1.21.6 {
        /*this.output = null;
        *///? }
    }

    public void putBoolean(String key, boolean value) {
        //? if >=1.21.6 {
        /*if (output != null) {
            output.putBoolean(key, value);
            return;
        }
        *///? }
        tag.putBoolean(key, value);
    }

    public void putInt(String key, int value) {
        //? if >=1.21.6 {
        /*if (output != null) {
            output.putInt(key, value);
            return;
        }
        *///? }
        tag.putInt(key, value);
    }

    public void putDouble(String key, double value) {
        //? if >=1.21.6 {
        /*if (output != null) {
            output.putDouble(key, value);
            return;
        }
        *///? }
        tag.putDouble(key, value);
    }

    public void putString(String key, String value) {
        //? if >=1.21.6 {
        /*if (output != null) {
            output.putString(key, value);
            return;
        }
        *///? }
        tag.putString(key, value);
    }
}
