package com.theplumteam.data;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.util.TagReads;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-platform implementation of IPlayerDiscovery.
 * Stores discovered figure IDs in a HashSet for fast lookups.
 */
public class PlayerDiscovery implements IPlayerDiscovery {
    private final Set<String> discoveredFigures = new HashSet<>();
    private static final String NBT_KEY = "DiscoveredFigures";

    // Token system fields
    private int regularTokens = 0;
    private long nextRegularTokenTime = 0;
    private long lastSpecialTokenResetTimestamp = 0;
    private boolean usedTodaySpecialToken = false;

    // Favorite color fields
    private boolean hasChosenFavoriteColor = false;
    private String favoriteColor = null;

    // Player figure skin snapshots (Mojang)
    private final Map<String, String> figureSkins = new HashMap<>();
    private static final String NBT_FIGURE_SKINS_KEY = "FigureSkins";

    // Quick Skin snapshots (Modded)
    private final Map<String, String> figureQuickSkins = new HashMap<>();
    private static final String NBT_FIGURE_QUICK_SKINS_KEY = "FigureQuickSkins";

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
            return null;
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

    // Quick Skin Implementation

    @Override
    public void saveFigureQuickSkin(String figureId, String quickSkinId) {
        figureQuickSkins.put(figureId, quickSkinId);
    }

    @Override
    @Nullable
    public String getFigureQuickSkin(String figureId) {
        return figureQuickSkins.get(figureId);
    }

    @Override
    public Map<String, String> getAllFigureQuickSkins() {
        return Collections.unmodifiableMap(figureQuickSkins);
    }

    /**
     * Serialize this discovery data to NBT.
     */
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

        // Serialize Quick Skins
        if (!figureQuickSkins.isEmpty()) {
            CompoundTag qsTag = new CompoundTag();
            for (Map.Entry<String, String> entry : figureQuickSkins.entrySet()) {
                qsTag.putString(entry.getKey(), entry.getValue());
            }
            tag.put(NBT_FIGURE_QUICK_SKINS_KEY, qsTag);
        }

        return tag;
    }

    /**
     * Deserialize discovery data from NBT.
     */
    public void deserializeNBT(CompoundTag tag) {
        discoveredFigures.clear();

        if (TagReads.hasList(tag, NBT_KEY)) {
            ListTag listTag = TagReads.list(tag, NBT_KEY, Tag.TAG_STRING);
            for (int i = 0; i < listTag.size(); i++) {
                discoveredFigures.add(TagReads.string(listTag, i, ""));
            }
        }

        // Deserialize token data
        if (tag.contains("RegularTokens")) {
            this.regularTokens = TagReads.integer(tag, "RegularTokens", 0);
        }
        if (tag.contains("NextRegularTokenTime")) {
            this.nextRegularTokenTime = TagReads.lng(tag, "NextRegularTokenTime", 0L);
        }
        if (tag.contains("LastSpecialTokenResetTimestamp")) {
            this.lastSpecialTokenResetTimestamp = TagReads.lng(tag, "LastSpecialTokenResetTimestamp", 0L);
        }
        if (tag.contains("UsedTodaySpecialToken")) {
            this.usedTodaySpecialToken = TagReads.bool(tag, "UsedTodaySpecialToken", false);
        }

        // Deserialize favorite color data
        this.hasChosenFavoriteColor = TagReads.bool(tag, "HasChosenFavoriteColor", false);
        if (TagReads.hasString(tag, "FavoriteColor")) {
            this.favoriteColor = TagReads.string(tag, "FavoriteColor", "");
        } else {
            this.favoriteColor = null;
        }

        // Deserialize figure skins
        figureSkins.clear();
        if (TagReads.hasCompound(tag, NBT_FIGURE_SKINS_KEY)) {
            CompoundTag skinsTag = TagReads.compound(tag, NBT_FIGURE_SKINS_KEY);
            for (String key : skinsTag.getAllKeys()) {
                figureSkins.put(key, TagReads.string(skinsTag, key, ""));
            }
        }

        // Deserialize Quick Skins
        figureQuickSkins.clear();
        if (TagReads.hasCompound(tag, NBT_FIGURE_QUICK_SKINS_KEY)) {
            CompoundTag qsTag = TagReads.compound(tag, NBT_FIGURE_QUICK_SKINS_KEY);
            for (String key : qsTag.getAllKeys()) {
                figureQuickSkins.put(key, TagReads.string(qsTag, key, ""));
            }
        }
    }
}
