package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

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
            ResourceLocation texture = new ResourceLocation(json.get("texture").getAsString());
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

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath) {
        this(id, name, modelPath, texturePath, animationPath, Collections.emptyList());
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        this.type = FigureType.STATIC;
        this.playerUUID = null;
        this.alternatives = new ArrayList<>(alternatives);
    }

    /**
     * Constructor for player figures (dynamic figures using player skins)
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation animationPath, UUID playerUUID) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = null; // Texture is handled dynamically
        this.animationPath = animationPath;
        this.type = FigureType.PLAYER;
        this.playerUUID = playerUUID;
        this.alternatives = Collections.emptyList();
    }

    /**
     * Creates a FigureDefinition from a JSON object
     */
    public static FigureDefinition fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();

        ResourceLocation modelPath = new ResourceLocation(json.get("model").getAsString());
        ResourceLocation texturePath = new ResourceLocation(json.get("texture").getAsString());
        ResourceLocation animationPath = new ResourceLocation(json.get("animation").getAsString());

        // Parse alternative skins if present
        List<AlternativeSkin> alternatives = new ArrayList<>();
        if (json.has("alternatives")) {
            JsonArray alternativesArray = json.getAsJsonArray("alternatives");
            for (int i = 0; i < alternativesArray.size(); i++) {
                JsonObject altJson = alternativesArray.get(i).getAsJsonObject();
                alternatives.add(AlternativeSkin.fromJson(altJson));
            }
        }

        return new FigureDefinition(id, name, modelPath, texturePath, animationPath, alternatives);
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

    @Override
    public String toString() {
        return "FigureDefinition{id='" + id + "', name='" + name + "', type=" + type + "}";
    }
}
