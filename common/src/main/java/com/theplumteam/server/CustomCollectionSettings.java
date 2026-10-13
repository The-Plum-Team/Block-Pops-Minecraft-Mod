package com.theplumteam.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.RemotePaths;
import com.theplumteam.util.TagReads;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Custom collections an operator has enabled in this world, stored in
 * data/blockpops-custom-collections.json.
 *
 * <p>Worlds from 1.5.0 kept the list in data/blockpops_world.dat. It is read once, when
 * this world has no list of its own yet, and left in place so the world still opens in
 * 1.5.0.
 */
public final class CustomCollectionSettings {
    /** At most this many Custom collections are enabled in one world. */
    public static final int MAX_ENABLED = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer, CustomCollectionSettings> SETTINGS = new WeakHashMap<>();

    private final Path path;
    private final Set<String> enabled = new LinkedHashSet<>();

    private CustomCollectionSettings(Path dataDirectory) {
        this.path = dataDirectory.resolve("blockpops-custom-collections.json");
        if (Files.exists(path)) {
            load();
        } else {
            importLegacy(dataDirectory.resolve("blockpops_world.dat"));
        }
    }

    public static synchronized CustomCollectionSettings get(MinecraftServer server) {
        return SETTINGS.computeIfAbsent(server, owner ->
                new CustomCollectionSettings(owner.getWorldPath(LevelResource.ROOT).resolve("data")));
    }

    public synchronized Set<String> enabled() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(enabled));
    }

    /**
     * Enables or disables a collection and saves the list.
     *
     * @return false if the list is full or could not be saved, in which case nothing changed
     */
    public synchronized boolean setEnabled(String collectionId, boolean enable) {
        if (enable && !enabled.contains(collectionId) && enabled.size() >= MAX_ENABLED) {
            return false;
        }
        boolean changed = enable ? enabled.add(collectionId) : enabled.remove(collectionId);
        if (changed && !save()) {
            if (enable) {
                enabled.remove(collectionId);
            } else {
                enabled.add(collectionId);
            }
            return false;
        }
        return true;
    }

    private void accept(String collectionId) {
        if (RemotePaths.isValidCollectionId(collectionId) && enabled.size() < MAX_ENABLED) {
            enabled.add(collectionId);
        } else {
            BlockPopsMod.LOGGER.warn("Ignoring invalid Custom collection id in world settings: {}", collectionId);
        }
    }

    private void load() {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (JsonElement entry : GSON.fromJson(reader, JsonObject.class).getAsJsonArray("enabled")) {
                accept(entry.getAsString());
            }
        } catch (Exception e) {
            // Set the unreadable file aside, so the next save does not overwrite the list it held
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt");
            BlockPopsMod.LOGGER.error("Failed to read the Custom collections of this world {}, none are enabled; keeping the file as {}",
                    path, backup.getFileName(), e);
            try {
                Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                BlockPopsMod.LOGGER.error("Could not set the unreadable Custom collections file aside", moveFailure);
            }
        }
    }

    /** Reads 1.5.0's {@code {data: {EnabledRemoteCollections: {<id>: 1b}}}} saved data. */
    private void importLegacy(Path legacy) {
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try (InputStream stream = Files.newInputStream(legacy)) {
            //? if >=1.21 {
            /*CompoundTag root = NbtIo.readCompressed(stream, net.minecraft.nbt.NbtAccounter.create(1 << 20));
            *///? } else {
            CompoundTag root = NbtIo.readCompressed(stream);
            //? }
            CompoundTag collections = TagReads.compound(TagReads.compound(root, "data"), "EnabledRemoteCollections");
            for (String collectionId : TagReads.keys(collections)) {
                if (TagReads.bool(collections, collectionId, false)) {
                    accept(collectionId);
                }
            }
            if (!enabled.isEmpty() && save()) {
                BlockPopsMod.LOGGER.info("Imported {} Custom collections from the 1.5.0 world settings", enabled.size());
            }
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not read the 1.5.0 world settings {}: {}", legacy, e.toString());
        }
    }

    private boolean save() {
        JsonArray entries = new JsonArray();
        enabled.forEach(entries::add);
        JsonObject json = new JsonObject();
        json.add("enabled", entries);

        // Write a sibling file and swap it in, so a crash cannot leave a half-written list
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                // Not every file system offers the atomic swap, and Windows can deny it
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            BlockPopsMod.LOGGER.error("Failed to save the Custom collections of this world {}", path, e);
            return false;
        }
    }
}
