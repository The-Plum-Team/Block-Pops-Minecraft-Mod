package com.theplumteam.server.config;

import com.theplumteam.BlockPopsMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-world server configuration stored via SavedData.
 * Settings here are specific to each world/server instance, unlike ServerConfig which is global.
 * Stored in: {@code <world_save>/data/blockpops_world.dat}
 */
public class WorldConfig extends SavedData {

    private List<String> enabledRemoteCollections = new ArrayList<>();

    public WorldConfig() {
        // Default: no remote collections enabled
    }

    // --- Getters / Setters ---

    public List<String> getEnabledRemoteCollections() {
        return enabledRemoteCollections;
    }

    public void setEnabledRemoteCollections(List<String> enabled) {
        this.enabledRemoteCollections = enabled != null ? new ArrayList<>(enabled) : new ArrayList<>();
        setDirty();
    }

    // --- Serialization ---

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag collectionsTag = new CompoundTag();
        for (String id : enabledRemoteCollections) {
            collectionsTag.putBoolean(id, true);
        }
        tag.put("EnabledRemoteCollections", collectionsTag);
        return tag;
    }

    public static WorldConfig createFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        WorldConfig config = new WorldConfig();
        CompoundTag collectionsTag = tag.getCompoundOrEmpty("EnabledRemoteCollections");
        for (String key : collectionsTag.keySet()) {
            config.enabledRemoteCollections.add(key);
        }
        BlockPopsMod.LOGGER.debug("Loaded per-world config: {} enabled remote collections", config.enabledRemoteCollections.size());
        return config;
    }

    // --- SavedDataType (1.21.5+ pattern) ---

    private static final Codec<WorldConfig> CODEC = CompoundTag.CODEC.xmap(
        tag -> WorldConfig.createFromTag(tag, null),
        state -> {
            CompoundTag tag = new CompoundTag();
            return state.save(tag, null);
        }
    );

    private static final SavedDataType<WorldConfig> TYPE =
        new SavedDataType<>(
            BlockPopsMod.MOD_ID + "_world",
            WorldConfig::new,
            CODEC,
            null
        );

    /**
     * Get the per-world config from the overworld's data storage.
     * Creates a new empty config if none exists yet.
     */
    public static WorldConfig get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
