package com.theplumteam.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Reads NBT the way the active Minecraft version exposes it.
 *
 * 1.21.5 turned every tag getter into an `Optional` and removed the typed
 * `contains` overload, where earlier versions returned a zero value for a
 * missing or mistyped entry. Every read states the value it wants when the
 * entry is absent, so both eras answer identically.
 */
public final class TagReads {
    private TagReads() {
    }

    public static String string(CompoundTag tag, String key, String fallback) {
        //? if >=1.21.5 {
        /*return tag.getStringOr(key, fallback);
        *///? } else {
        return tag.contains(key) ? tag.getString(key) : fallback;
        //? }
    }

    public static String string(ListTag list, int index, String fallback) {
        //? if >=1.21.5 {
        /*return list.getStringOr(index, fallback);
        *///? } else {
        return index < list.size() ? list.getString(index) : fallback;
        //? }
    }

    public static int integer(CompoundTag tag, String key, int fallback) {
        //? if >=1.21.5 {
        /*return tag.getIntOr(key, fallback);
        *///? } else {
        return tag.contains(key) ? tag.getInt(key) : fallback;
        //? }
    }

    public static long lng(CompoundTag tag, String key, long fallback) {
        //? if >=1.21.5 {
        /*return tag.getLongOr(key, fallback);
        *///? } else {
        return tag.contains(key) ? tag.getLong(key) : fallback;
        //? }
    }

    public static double dbl(CompoundTag tag, String key, double fallback) {
        //? if >=1.21.5 {
        /*return tag.getDoubleOr(key, fallback);
        *///? } else {
        return tag.contains(key) ? tag.getDouble(key) : fallback;
        //? }
    }

    public static boolean bool(CompoundTag tag, String key, boolean fallback) {
        //? if >=1.21.5 {
        /*return tag.getBooleanOr(key, fallback);
        *///? } else {
        return tag.contains(key) ? tag.getBoolean(key) : fallback;
        //? }
    }

    /** Answers an empty compound when the entry is missing, as both eras always did. */
    public static CompoundTag compound(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        /*return tag.getCompoundOrEmpty(key);
        *///? } else {
        return tag.getCompound(key);
        //? }
    }

    /**
     * Answers an empty list when the entry is missing. Below 1.21.5 a list of
     * another element type also reads as empty; from 1.21.5 the element type is
     * checked by each read instead, which is why it is only named here.
     */
    public static ListTag list(CompoundTag tag, String key, int elementType) {
        //? if >=1.21.5 {
        /*return tag.getListOrEmpty(key);
        *///? } else {
        return tag.getList(key, elementType);
        //? }
    }

    /** Lists the keys a compound holds. */
    public static java.util.Set<String> keys(CompoundTag tag) {
        //? if >=1.21.5 {
        /*return tag.keySet();
        *///? } else {
        return tag.getAllKeys();
        //? }
    }

    /** Answers whether the entry exists and holds a compound. */
    public static boolean hasCompound(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        /*return tag.getCompound(key).isPresent();
        *///? } else {
        return tag.contains(key, 10);
        //? }
    }

    /** Answers whether the entry exists and holds a list. */
    public static boolean hasList(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        /*return tag.getList(key).isPresent();
        *///? } else {
        return tag.contains(key, 9);
        //? }
    }

    /** Answers whether the entry exists and holds a string. */
    public static boolean hasString(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        /*return tag.getString(key).isPresent();
        *///? } else {
        return tag.contains(key, 8);
        //? }
    }
}
