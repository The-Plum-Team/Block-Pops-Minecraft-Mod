package com.theplumteam.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Default implementation of IPlayerDiscovery.
 * Stores discovered figure IDs in a HashSet for fast lookups.
 */
public class PlayerDiscovery implements IPlayerDiscovery, INBTSerializable<CompoundTag> {
    private final Set<String> discoveredFigures = new HashSet<>();
    private static final String NBT_KEY = "DiscoveredFigures";

    @Override
    public boolean isDiscovered(String figureId) {
        return discoveredFigures.contains(figureId);
    }

    @Override
    public void discover(String figureId) {
        discoveredFigures.add(figureId);
    }

    @Override
    public Set<String> getDiscoveredSet() {
        return Collections.unmodifiableSet(discoveredFigures);
    }

    @Override
    public void syncFrom(Set<String> discovered) {
        discoveredFigures.clear();
        discoveredFigures.addAll(discovered);
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag listTag = new ListTag();

        for (String figureId : discoveredFigures) {
            listTag.add(StringTag.valueOf(figureId));
        }

        tag.put(NBT_KEY, listTag);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        discoveredFigures.clear();

        if (tag.contains(NBT_KEY, Tag.TAG_LIST)) {
            ListTag listTag = tag.getList(NBT_KEY, Tag.TAG_STRING);
            for (int i = 0; i < listTag.size(); i++) {
                discoveredFigures.add(listTag.getString(i));
            }
        }
    }
}
