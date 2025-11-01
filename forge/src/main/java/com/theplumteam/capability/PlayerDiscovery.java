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

    // Token system fields
    private int regularTokens = 0;
    private long nextRegularTokenTime = 0;
    private long lastSpecialTokenResetTimestamp = 0;
    private boolean usedTodaySpecialToken = false;

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

    // Token System Implementation

    @Override
    public int getRegularTokens() {
        return regularTokens;
    }

    @Override
    public void setRegularTokens(int count) {
        this.regularTokens = count;
    }

    @Override
    public long getNextRegularTokenTime() {
        return nextRegularTokenTime;
    }

    @Override
    public void setNextRegularTokenTime(long worldTimeTicks) {
        this.nextRegularTokenTime = worldTimeTicks;
    }

    @Override
    public long getLastSpecialTokenResetTimestamp() {
        return lastSpecialTokenResetTimestamp;
    }

    @Override
    public void setLastSpecialTokenResetTimestamp(long timestamp) {
        this.lastSpecialTokenResetTimestamp = timestamp;
    }

    @Override
    public boolean hasUsedTodaySpecialToken() {
        return usedTodaySpecialToken;
    }

    @Override
    public void setUsedTodaySpecialToken(boolean used) {
        this.usedTodaySpecialToken = used;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag listTag = new ListTag();

        for (String figureId : discoveredFigures) {
            listTag.add(StringTag.valueOf(figureId));
        }

        tag.put(NBT_KEY, listTag);

        // Serialize token data
        tag.putInt("RegularTokens", this.regularTokens);
        tag.putLong("NextRegularTokenTime", this.nextRegularTokenTime);
        tag.putLong("LastSpecialTokenResetTimestamp", this.lastSpecialTokenResetTimestamp);
        tag.putBoolean("UsedTodaySpecialToken", this.usedTodaySpecialToken);

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

        // Deserialize token data
        if (tag.contains("RegularTokens")) {
            this.regularTokens = tag.getInt("RegularTokens");
        }
        if (tag.contains("NextRegularTokenTime")) {
            this.nextRegularTokenTime = tag.getLong("NextRegularTokenTime");
        }
        if (tag.contains("LastSpecialTokenResetTimestamp")) {
            this.lastSpecialTokenResetTimestamp = tag.getLong("LastSpecialTokenResetTimestamp");
        }
        if (tag.contains("UsedTodaySpecialToken")) {
            this.usedTodaySpecialToken = tag.getBoolean("UsedTodaySpecialToken");
        }
    }
}
