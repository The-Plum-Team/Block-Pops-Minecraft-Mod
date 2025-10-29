package com.theplumteam.figure;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/**
 * Represents a single figure within a collection.
 * Contains all resource paths needed to render the figure.
 */
public class FigureDefinition {
    private final String id;
    private final String name;
    private final ResourceLocation modelPath;
    private final ResourceLocation texturePath;
    private final ResourceLocation animationPath;

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
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

        return new FigureDefinition(id, name, modelPath, texturePath, animationPath);
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

    @Override
    public String toString() {
        return "FigureDefinition{id='" + id + "', name='" + name + "'}";
    }
}
