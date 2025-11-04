package com.theplumteam.capability;

import com.theplumteam.block.PopBlockColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    // Favorite color fields
    private boolean hasChosenFavoriteColor = false;
    private String favoriteColor = null; // Store as string name

    // Player figure skin snapshots - maps figureId to skin texture URL
    private final Map<String, String> figureSkins = new HashMap<>();
    private static final String NBT_FIGURE_SKINS_KEY = "FigureSkins";

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

    // Favorite Color Implementation

    @Override
    public boolean hasChosenFavoriteColor() {
        return this.hasChosenFavoriteColor;
    }

    @Override
    public void setHasChosenFavoriteColor(boolean hasChosen) {
        this.hasChosenFavoriteColor = hasChosen;
    }

    @Override
    @Nullable
    public PopBlockColor getFavoriteColor() {
        if (this.favoriteColor == null) {
            return null;
        }
        try {
            return PopBlockColor.valueOf(this.favoriteColor.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // Invalid color name stored
        }
    }

    @Override
    public void setFavoriteColor(@Nullable PopBlockColor color) {
        this.favoriteColor = (color != null) ? color.name() : null;
    }

    // Player Figure Skin Snapshot Implementation

    @Override
    public void saveFigureSkin(String figureId, String skinUrl) {
        figureSkins.put(figureId, skinUrl);
    }

    @Override
    @Nullable
    public String getFigureSkin(String figureId) {
        return figureSkins.get(figureId);
    }

    @Override
    public Map<String, String> getAllFigureSkins() {
        return Collections.unmodifiableMap(figureSkins);
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

        // Serialize favorite color data
        tag.putBoolean("HasChosenFavoriteColor", this.hasChosenFavoriteColor);
        if (this.favoriteColor != null) {
            tag.putString("FavoriteColor", this.favoriteColor);
        }

        // Serialize figure skins
        if (!figureSkins.isEmpty()) {
            CompoundTag skinsTag = new CompoundTag();
            for (Map.Entry<String, String> entry : figureSkins.entrySet()) {
                skinsTag.putString(entry.getKey(), entry.getValue());
            }
            tag.put(NBT_FIGURE_SKINS_KEY, skinsTag);
        }

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

        // Deserialize favorite color data
        this.hasChosenFavoriteColor = tag.getBoolean("HasChosenFavoriteColor");
        if (tag.contains("FavoriteColor", Tag.TAG_STRING)) {
            this.favoriteColor = tag.getString("FavoriteColor");
        } else {
            this.favoriteColor = null;
        }

        // Deserialize figure skins
        figureSkins.clear();
        if (tag.contains(NBT_FIGURE_SKINS_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag skinsTag = tag.getCompound(NBT_FIGURE_SKINS_KEY);
            for (String key : skinsTag.getAllKeys()) {
                figureSkins.put(key, skinsTag.getString(key));
            }
        }
    }
}
