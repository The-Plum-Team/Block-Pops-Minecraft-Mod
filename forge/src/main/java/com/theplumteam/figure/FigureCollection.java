package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents a collection/edition of figures (e.g., JoJos, Jujutsu Kaisen, etc.)
 * Each collection has its own box texture and set of figures.
 */
public class FigureCollection {
    private final String id;
    private final String name;
    private final ResourceLocation boxTexture;
    private final ResourceLocation logoTexture; // Optional: logo to display on the box
    private final String logoType; // Optional: "square", "wide", or "tall" (defaults to "square")
    private final List<FigureDefinition> figures;

    public FigureCollection(String id, String name, ResourceLocation boxTexture, ResourceLocation logoTexture, String logoType, List<FigureDefinition> figures) {
        this.id = id;
        this.name = name;
        this.boxTexture = boxTexture;
        this.logoTexture = logoTexture;
        this.logoType = logoType != null ? logoType : "square";
        this.figures = new ArrayList<>(figures);
    }

    /**
     * Creates a FigureCollection from a JSON object
     */
    public static FigureCollection fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        ResourceLocation boxTexture = new ResourceLocation(json.get("box_texture").getAsString());

        // Logo texture is optional
        ResourceLocation logoTexture = null;
        if (json.has("logo_texture")) {
            logoTexture = new ResourceLocation(json.get("logo_texture").getAsString());
        }

        // Logo type is optional (defaults to "square")
        String logoType = json.has("logo_type") ? json.get("logo_type").getAsString() : "square";

        List<FigureDefinition> figures = new ArrayList<>();
        JsonArray figuresArray = json.getAsJsonArray("figures");
        for (int i = 0; i < figuresArray.size(); i++) {
            JsonObject figureJson = figuresArray.get(i).getAsJsonObject();
            figures.add(FigureDefinition.fromJson(figureJson));
        }

        return new FigureCollection(id, name, boxTexture, logoTexture, logoType, figures);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ResourceLocation getBoxTexture() {
        return boxTexture;
    }

    public ResourceLocation getLogoTexture() {
        return logoTexture;
    }

    public String getLogoType() {
        return logoType;
    }

    public List<FigureDefinition> getFigures() {
        return Collections.unmodifiableList(figures);
    }

    /**
     * Gets a specific figure by ID from this collection
     */
    public Optional<FigureDefinition> getFigure(String figureId) {
        return figures.stream()
                .filter(f -> f.getId().equals(figureId))
                .findFirst();
    }

    /**
     * Checks if this collection has any figures
     */
    public boolean hasFigures() {
        return !figures.isEmpty();
    }

    @Override
    public String toString() {
        return "FigureCollection{id='" + id + "', name='" + name + "', figures=" + figures.size() + "}";
    }
}
