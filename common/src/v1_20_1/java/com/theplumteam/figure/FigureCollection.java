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
        private final float scaleZ;

        public LogoConfig(ResourceLocation texture, float positionX, float positionY, float positionZ, float scaleX, float scaleY, float scaleZ) {
            this.texture = texture;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionZ = positionZ;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
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

        public float getScaleZ() {
            return scaleZ;
        }
    }

    private final String id;
    private final String name;
    private final String author;
    private final String authorUrl; // Optional: URL to creator's page
    private final ResourceLocation boxTexture;
    private final LogoConfig logoConfig; // Optional: logo configuration for display on the box
    private final List<FigureDefinition> figures;
    private final int[] backgroundColor; // Optional: RGB color for background (0-255 each)

    public FigureCollection(String id, String name, String author, String authorUrl, ResourceLocation boxTexture, LogoConfig logoConfig, List<FigureDefinition> figures, int[] backgroundColor) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.authorUrl = authorUrl;
        this.boxTexture = boxTexture;
        this.logoConfig = logoConfig;
        this.figures = new ArrayList<>(figures);
        this.backgroundColor = backgroundColor;
    }

    /**
     * Creates a FigureCollection from a JSON object
     */
    public static FigureCollection fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        String author = json.has("author") ? json.get("author").getAsString() : "Unknown";
        String authorUrl = json.has("author_url") ? json.get("author_url").getAsString() : null;
        ResourceLocation boxTexture = ResourceLocation.tryParse(json.get("box_texture").getAsString());

        // Parse logo configuration (optional)
        LogoConfig logoConfig = null;
        if (json.has("logo")) {
            JsonObject logoJson = json.getAsJsonObject("logo");
            ResourceLocation logoTexture = ResourceLocation.tryParse(logoJson.get("texture").getAsString());
            float positionX = logoJson.has("position_x") ? logoJson.get("position_x").getAsFloat() : -3.5f;
            float positionY = logoJson.has("position_y") ? logoJson.get("position_y").getAsFloat() : 0.8f;
            float positionZ = logoJson.has("position_z") ? logoJson.get("position_z").getAsFloat() : -7.4f;
            float scaleX = logoJson.has("scale_x") ? logoJson.get("scale_x").getAsFloat() : 1.0f;
            float scaleY = logoJson.has("scale_y") ? logoJson.get("scale_y").getAsFloat() : 5.0f;
            float scaleZ = logoJson.has("scale_z") ? logoJson.get("scale_z").getAsFloat() : 5.0f;
            logoConfig = new LogoConfig(logoTexture, positionX, positionY, positionZ, scaleX, scaleY, scaleZ);
        } else if (json.has("logo_texture")) {
            // Backward compatibility: support old format
            ResourceLocation logoTexture = ResourceLocation.tryParse(json.get("logo_texture").getAsString());
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
            // For backward compatibility: old scaleX becomes scaleZ (since it was used for Z-axis width)
            logoConfig = new LogoConfig(logoTexture, -3.5f, 0.8f, -7.4f, 1.0f, scaleY, scaleX);
        }

        List<FigureDefinition> figures = new ArrayList<>();
        JsonArray figuresArray = json.getAsJsonArray("figures");
        for (int i = 0; i < figuresArray.size(); i++) {
            JsonObject figureJson = figuresArray.get(i).getAsJsonObject();
            figures.add(FigureDefinition.fromJson(figureJson));
        }

        // Parse background color (optional)
        int[] backgroundColor = null;
        if (json.has("background_color")) {
            JsonObject bgColorJson = json.getAsJsonObject("background_color");
            int r = bgColorJson.has("r") ? bgColorJson.get("r").getAsInt() : 0;
            int g = bgColorJson.has("g") ? bgColorJson.get("g").getAsInt() : 0;
            int b = bgColorJson.has("b") ? bgColorJson.get("b").getAsInt() : 0;
            backgroundColor = new int[]{r, g, b};
        }

        return new FigureCollection(id, name, author, authorUrl, boxTexture, logoConfig, figures, backgroundColor);
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
     * Gets the background color for this collection (RGB 0-255)
     * @return int array [r, g, b] or null if not specified
     */
    public int[] getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Checks if this collection has a custom background color
     */
    public boolean hasBackgroundColor() {
        return backgroundColor != null;
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

    /**
     * Serializes this FigureCollection to a JSON object
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("author", author);
        if (authorUrl != null) {
            json.addProperty("author_url", authorUrl);
        }
        json.addProperty("box_texture", boxTexture.toString());

        // Serialize logo configuration
        if (logoConfig != null) {
            JsonObject logoJson = new JsonObject();
            logoJson.addProperty("texture", logoConfig.getTexture().toString());
            logoJson.addProperty("position_x", logoConfig.getPositionX());
            logoJson.addProperty("position_y", logoConfig.getPositionY());
            logoJson.addProperty("position_z", logoConfig.getPositionZ());
            logoJson.addProperty("scale_x", logoConfig.getScaleX());
            logoJson.addProperty("scale_y", logoConfig.getScaleY());
            logoJson.addProperty("scale_z", logoConfig.getScaleZ());
            json.add("logo", logoJson);
        }

        // Serialize figures
        JsonArray figuresArray = new JsonArray();
        for (FigureDefinition figure : figures) {
            figuresArray.add(figure.toJson());
        }
        json.add("figures", figuresArray);

        // Serialize background color
        if (backgroundColor != null) {
            JsonObject bgColorJson = new JsonObject();
            bgColorJson.addProperty("r", backgroundColor[0]);
            bgColorJson.addProperty("g", backgroundColor[1]);
            bgColorJson.addProperty("b", backgroundColor[2]);
            json.add("background_color", bgColorJson);
        }

        return json;
    }

    @Override
    public String toString() {
        return "FigureCollection{id='" + id + "', name='" + name + "', figures=" + figures.size() + "}";
    }
}
