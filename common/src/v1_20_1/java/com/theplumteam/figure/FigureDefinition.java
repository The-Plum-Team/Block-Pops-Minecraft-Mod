package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.block.PopBlockColor;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single figure within a collection.
 * Contains all resource paths needed to render the figure.
 * Can represent either a static figure (from JSON) or a dynamic player figure.
 */
public class FigureDefinition {
    /**
     * Represents an alternative skin variant for a figure
     */
    public record AlternativeSkin(String name, ResourceLocation texture) {
        public static AlternativeSkin fromJson(JsonObject json) {
            String name = json.get("name").getAsString();
            ResourceLocation texture = ResourceLocation.tryParse(json.get("texture").getAsString());
            return new AlternativeSkin(name, texture);
        }
    }

    private final String id;
    private final String name;
    private final ResourceLocation modelPath;
    private final ResourceLocation texturePath;
    private final ResourceLocation animationPath;
    private final FigureType type;
    private final UUID playerUUID;
    private final List<AlternativeSkin> alternatives;
    private final PopBlockColor favoriteColor; // For player figures, stores their chosen color
    @Nullable private final String authorUrl;

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath) {
        this(id, name, modelPath, texturePath, animationPath, Collections.emptyList(), null);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, null);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, @Nullable String authorUrl) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        this.type = FigureType.STATIC;
        this.playerUUID = null;
        this.alternatives = new ArrayList<>(alternatives);
        this.favoriteColor = null; // Static figures don't have favorite colors
        this.authorUrl = authorUrl;
    }

    /**
     * Constructor for player figures (dynamic figures using player skins)
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation animationPath, UUID playerUUID, PopBlockColor favoriteColor) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = null; // Texture is handled dynamically
        this.animationPath = animationPath;
        this.type = FigureType.PLAYER;
        this.playerUUID = playerUUID;
        this.alternatives = Collections.emptyList();
        this.favoriteColor = favoriteColor;
        this.authorUrl = null;
    }

    /**
     * Creates a FigureDefinition from a JSON object
     */
    public static FigureDefinition fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        ResourceLocation modelPath = ResourceLocation.tryParse(json.get("model").getAsString());
        ResourceLocation animationPath = ResourceLocation.tryParse(json.get("animation").getAsString());

        // Check if this is a player figure
        String type = json.has("type") ? json.get("type").getAsString() : "static";

        if ("player".equals(type)) {
            // Parse player figure
            UUID playerUUID = json.has("player_uuid") ? UUID.fromString(json.get("player_uuid").getAsString()) : null;
            PopBlockColor favoriteColor = null;
            if (json.has("favorite_color")) {
                try {
                    favoriteColor = PopBlockColor.valueOf(json.get("favorite_color").getAsString().toUpperCase());
                } catch (IllegalArgumentException e) {
                    favoriteColor = PopBlockColor.ORIGINAL; // Default if invalid
                }
            }
            return new FigureDefinition(id, name, modelPath, animationPath, playerUUID, favoriteColor);
        } else {
            // Parse static figure
            ResourceLocation texturePath = ResourceLocation.tryParse(json.get("texture").getAsString());

            // Parse alternative skins if present
            List<AlternativeSkin> alternatives = new ArrayList<>();
            if (json.has("alternatives")) {
                JsonArray alternativesArray = json.getAsJsonArray("alternatives");
                for (int i = 0; i < alternativesArray.size(); i++) {
                    JsonObject altJson = alternativesArray.get(i).getAsJsonObject();
                    alternatives.add(AlternativeSkin.fromJson(altJson));
                }
            }

            String authorUrl = json.has("author_url") ? json.get("author_url").getAsString() : null;

            return new FigureDefinition(id, name, modelPath, texturePath, animationPath, alternatives, authorUrl);
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ResourceLocation getModelPath() {
        return modelPath;
    }

    public ResourceLocation getTexturePath() {
        return texturePath;
    }

    public ResourceLocation getAnimationPath() {
        return animationPath;
    }

    public FigureType getType() {
        return type;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public List<AlternativeSkin> getAlternatives() {
        return Collections.unmodifiableList(alternatives);
    }

    public boolean hasAlternatives() {
        return !alternatives.isEmpty();
    }

    @Nullable
    public PopBlockColor getFavoriteColor() {
        return favoriteColor;
    }

    @Nullable
    public String getAuthorUrl() {
        return authorUrl;
    }

    public boolean hasAuthorUrl() {
        return authorUrl != null && !authorUrl.isEmpty();
    }

    /**
     * Serializes this FigureDefinition to a JSON object
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("model", modelPath.toString());
        json.addProperty("animation", animationPath.toString());

        if (type == FigureType.PLAYER) {
            // For player figures, include player UUID and favorite color
            json.addProperty("type", "player");
            if (playerUUID != null) {
                json.addProperty("player_uuid", playerUUID.toString());
            }
            if (favoriteColor != null) {
                json.addProperty("favorite_color", favoriteColor.getSerializedName());
            }
        } else {
            // For static figures, include texture and alternatives
            json.addProperty("type", "static");
            if (texturePath != null) {
                json.addProperty("texture", texturePath.toString());
            }

            if (!alternatives.isEmpty()) {
                JsonArray alternativesArray = new JsonArray();
                for (AlternativeSkin alt : alternatives) {
                    JsonObject altJson = new JsonObject();
                    altJson.addProperty("name", alt.name());
                    altJson.addProperty("texture", alt.texture().toString());
                    alternativesArray.add(altJson);
                }
                json.add("alternatives", alternativesArray);
            }

            if (authorUrl != null && !authorUrl.isEmpty()) {
                json.addProperty("author_url", authorUrl);
            }
        }

        return json;
    }

    @Override
    public String toString() {
        return "FigureDefinition{id='" + id + "', name='" + name + "', type=" + type + "}";
    }
}
