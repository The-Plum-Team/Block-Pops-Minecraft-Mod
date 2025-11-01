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
    /**
     * Configuration for how the logo should be rendered on the box
     */
    public static class LogoConfig {
        private final ResourceLocation texture;
        private final float positionX;
        private final float positionY;
        private final float positionZ;
        private final float scaleX;
        private final float scaleY;

        public LogoConfig(ResourceLocation texture, float positionX, float positionY, float positionZ, float scaleX, float scaleY) {
            this.texture = texture;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionZ = positionZ;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        public ResourceLocation getTexture() {
            return texture;
        }

        public float getPositionX() {
            return positionX;
        }

        public float getPositionY() {
            return positionY;
        }

        public float getPositionZ() {
            return positionZ;
        }

        public float getScaleX() {
            return scaleX;
        }

        public float getScaleY() {
            return scaleY;
        }
    }

    private final String id;
    private final String name;
    private final String author;
    private final String authorUrl; // Optional: URL to creator's page
    private final ResourceLocation boxTexture;
    private final LogoConfig logoConfig; // Optional: logo configuration for display on the box
    private final List<FigureDefinition> figures;

    public FigureCollection(String id, String name, String author, String authorUrl, ResourceLocation boxTexture, LogoConfig logoConfig, List<FigureDefinition> figures) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.authorUrl = authorUrl;
        this.boxTexture = boxTexture;
        this.logoConfig = logoConfig;
        this.figures = new ArrayList<>(figures);
    }

    /**
     * Creates a FigureCollection from a JSON object
     */
    public static FigureCollection fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        String author = json.has("author") ? json.get("author").getAsString() : "Unknown";
        String authorUrl = json.has("author_url") ? json.get("author_url").getAsString() : null;
        ResourceLocation boxTexture = new ResourceLocation(json.get("box_texture").getAsString());

        // Parse logo configuration (optional)
        LogoConfig logoConfig = null;
        if (json.has("logo")) {
            JsonObject logoJson = json.getAsJsonObject("logo");
            ResourceLocation logoTexture = new ResourceLocation(logoJson.get("texture").getAsString());
            float positionX = logoJson.has("position_x") ? logoJson.get("position_x").getAsFloat() : -3.5f;
            float positionY = logoJson.has("position_y") ? logoJson.get("position_y").getAsFloat() : 0.8f;
            float positionZ = logoJson.has("position_z") ? logoJson.get("position_z").getAsFloat() : -7.4f;
            float scaleX = logoJson.has("scale_x") ? logoJson.get("scale_x").getAsFloat() : 5.0f;
            float scaleY = logoJson.has("scale_y") ? logoJson.get("scale_y").getAsFloat() : 5.0f;
            logoConfig = new LogoConfig(logoTexture, positionX, positionY, positionZ, scaleX, scaleY);
        } else if (json.has("logo_texture")) {
            // Backward compatibility: support old format
            ResourceLocation logoTexture = new ResourceLocation(json.get("logo_texture").getAsString());
            // Use default position and scale values based on old logo_type
            String logoType = json.has("logo_type") ? json.get("logo_type").getAsString() : "square";
            float scaleX = 5.0f;
            float scaleY = 5.0f;
            // Adjust scale for different types
            if ("wide".equals(logoType)) {
                scaleX = 6.0f;
                scaleY = 4.15f;
            } else if ("tall".equals(logoType)) {
                scaleX = 4.0f;
                scaleY = 6.0f;
            }
            logoConfig = new LogoConfig(logoTexture, -3.5f, 0.8f, -7.4f, scaleX, scaleY);
        }

        List<FigureDefinition> figures = new ArrayList<>();
        JsonArray figuresArray = json.getAsJsonArray("figures");
        for (int i = 0; i < figuresArray.size(); i++) {
            JsonObject figureJson = figuresArray.get(i).getAsJsonObject();
            figures.add(FigureDefinition.fromJson(figureJson));
        }

        return new FigureCollection(id, name, author, authorUrl, boxTexture, logoConfig, figures);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getAuthorUrl() {
        return authorUrl;
    }

    public ResourceLocation getBoxTexture() {
        return boxTexture;
    }

    public LogoConfig getLogoConfig() {
        return logoConfig;
    }

    /**
     * @deprecated Use getLogoConfig() instead
     */
    @Deprecated
    public ResourceLocation getLogoTexture() {
        return logoConfig != null ? logoConfig.getTexture() : null;
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
