package com.theplumteam.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The players an operator has disabled in the World Players collection.
 * Stored per world in data/blockpops-world-players.json; a player that is not listed is enabled.
 */
public final class WorldPlayerRoster {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer, WorldPlayerRoster> ROSTERS = new WeakHashMap<>();

    private final Path path;
    private final Set<UUID> disabled = new HashSet<>();

    private WorldPlayerRoster(Path path) {
        this.path = path;
        load();
    }

    public static synchronized WorldPlayerRoster get(MinecraftServer server) {
        return ROSTERS.computeIfAbsent(server, owner -> new WorldPlayerRoster(
                owner.getWorldPath(LevelResource.ROOT).resolve("data").resolve("blockpops-world-players.json")));
    }

    /**
     * Enables or disables a player and saves the roster.
     *
     * @return false if the roster could not be saved, in which case nothing changed
     */
    public boolean setEnabled(UUID playerUUID, boolean enabled) {
        boolean changed = enabled ? disabled.remove(playerUUID) : disabled.add(playerUUID);
        if (changed && !save()) {
            // Keep memory in step with the file that is still on disk
            if (enabled) {
                disabled.add(playerUUID);
            } else {
                disabled.remove(playerUUID);
            }
            return false;
        }
        return true;
    }

    /**
     * Replaces the generated figures of disabled players with disabled copies.
     * They stay in the collection so boxes and figures that already exist keep resolving.
     */
    public void mark(List<FigureDefinition> playerFigures) {
        playerFigures.replaceAll(figure -> disabled.contains(figure.getPlayerUUID())
                ? new FigureDefinition(figure.getId(), figure.getName(), figure.getModelPath(),
                        figure.getAnimationPath(), figure.getPlayerUUID(), figure.getFavoriteColor(), false)
                : figure);
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (JsonElement entry : GSON.fromJson(reader, JsonObject.class).getAsJsonArray("disabled")) {
                try {
                    disabled.add(UUID.fromString(entry.getAsString()));
                } catch (RuntimeException e) {
                    BlockPopsMod.LOGGER.warn("Ignoring invalid World Players roster entry: {}", entry);
                }
            }
        } catch (Exception e) {
            // Set the unreadable file aside, so the next save does not overwrite the list it held
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt");
            BlockPopsMod.LOGGER.error("Failed to read the World Players roster {}, every player stays enabled; keeping the file as {}",
                    path, backup.getFileName(), e);
            try {
                Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                BlockPopsMod.LOGGER.error("Could not set the unreadable World Players roster aside", moveFailure);
            }
        }
    }

    private boolean save() {
        JsonArray entries = new JsonArray();
        for (UUID playerUUID : new TreeSet<>(disabled)) {
            entries.add(playerUUID.toString());
        }
        JsonObject json = new JsonObject();
        json.add("disabled", entries);

        // Write a sibling file and swap it in, so a crash cannot leave a half-written roster
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
            BlockPopsMod.LOGGER.error("Failed to save the World Players roster {}", path, e);
            return false;
        }
    }
}
