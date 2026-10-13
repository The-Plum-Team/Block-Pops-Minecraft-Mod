package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.util.GeoAssets;
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
     * Represents an alternative skin variant for a figure. A variant may bring its own
     * model; without one it uses the figure's.
     */
    public record AlternativeSkin(String name, ResourceLocation texture, @Nullable ResourceLocation model) {
        public static AlternativeSkin fromJson(JsonObject json) {
            String name = json.get("name").getAsString();
            ResourceLocation texture = ResourceLocation.tryParse(json.get("texture").getAsString());
            ResourceLocation model = json.has("model")
                    ? GeoAssets.declared(ResourceLocation.tryParse(json.get("model").getAsString()))
                    : null;
            return new AlternativeSkin(name, texture, model);
        }
    }

    /**
     * How a figure with its own model is drawn. Figures on the default skin model need
     * none of it, so every value is optional.
     *
     * @param poseAnimation the figure's own stand and sit poses, for models whose bones
     *                      the shared poses do not drive
     * @param scale         the figure's size in the box and as a placed figure
     * @param guiScale      the figure's size in the collection screen
     * @param boxFaceUv     where the face shown on the box lies in the figure's texture:
     *                      {u, v, width, height, textureWidth, textureHeight}
     * @param offset        the figure's own position in the box, {x, y, z}, each NaN when
     *                      the box default applies
     */
    public record RenderOptions(@Nullable ResourceLocation poseAnimation, float scale, float guiScale,
                                @Nullable float[] boxFaceUv, @Nullable float[] offset) {
        public static final RenderOptions DEFAULT = new RenderOptions(null, 1.0f, 1.0f, null, null);
        private static final String[] OFFSET_KEYS = {"offset_x", "offset_y", "offset_z"};

        static RenderOptions fromJson(JsonObject json) {
            ResourceLocation poseAnimation = json.has("pose_animation")
                    ? GeoAssets.declared(ResourceLocation.tryParse(json.get("pose_animation").getAsString()))
                    : null;
            float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
            float guiScale = json.has("gui_scale") ? json.get("gui_scale").getAsFloat() : scale;
            float[] boxFaceUv = null;
            if (json.has("box_face_uv")) {
                JsonObject uv = json.getAsJsonObject("box_face_uv");
                boxFaceUv = new float[] {uv.get("u").getAsFloat(), uv.get("v").getAsFloat(),
                        uv.get("w").getAsFloat(), uv.get("h").getAsFloat(),
                        uv.get("tex_width").getAsFloat(), uv.get("tex_height").getAsFloat()};
            }
            float[] offset = null;
            for (int i = 0; i < OFFSET_KEYS.length; i++) {
                if (json.has(OFFSET_KEYS[i])) {
                    if (offset == null) {
                        offset = new float[] {Float.NaN, Float.NaN, Float.NaN};
                    }
                    offset[i] = json.get(OFFSET_KEYS[i]).getAsFloat();
                }
            }
            return new RenderOptions(poseAnimation, scale, guiScale, boxFaceUv, offset);
        }

        void toJson(JsonObject json) {
            if (poseAnimation != null) {
                json.addProperty("pose_animation", poseAnimation.toString());
            }
            if (scale != 1.0f) {
                json.addProperty("scale", scale);
            }
            if (guiScale != scale) {
                json.addProperty("gui_scale", guiScale);
            }
            if (boxFaceUv != null) {
                JsonObject uv = new JsonObject();
                uv.addProperty("u", boxFaceUv[0]);
                uv.addProperty("v", boxFaceUv[1]);
                uv.addProperty("w", boxFaceUv[2]);
                uv.addProperty("h", boxFaceUv[3]);
                uv.addProperty("tex_width", boxFaceUv[4]);
                uv.addProperty("tex_height", boxFaceUv[5]);
                json.add("box_face_uv", uv);
            }
            if (offset != null) {
                for (int i = 0; i < OFFSET_KEYS.length; i++) {
                    if (!Float.isNaN(offset[i])) {
                        json.addProperty(OFFSET_KEYS[i], offset[i]);
                    }
                }
            }
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
    private final boolean enabled; // False once an operator disables a World Players figure
    private final RenderOptions renderOptions;

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath) {
        this(id, name, modelPath, texturePath, animationPath, Collections.emptyList());
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, RenderOptions.DEFAULT);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, RenderOptions renderOptions) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        this.type = FigureType.STATIC;
        this.playerUUID = null;
        this.alternatives = new ArrayList<>(alternatives);
        this.favoriteColor = null; // Static figures don't have favorite colors
        this.enabled = true;
        this.renderOptions = renderOptions;
    }

    /**
     * Constructor for player figures (dynamic figures using player skins)
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation animationPath, UUID playerUUID, PopBlockColor favoriteColor) {
        this(id, name, modelPath, animationPath, playerUUID, favoriteColor, true);
    }

    /**
     * Constructor for player figures that an operator may have disabled
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation animationPath, UUID playerUUID, PopBlockColor favoriteColor,
                           boolean enabled) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = null; // Texture is handled dynamically
        this.animationPath = animationPath;
        this.type = FigureType.PLAYER;
        this.playerUUID = playerUUID;
        this.alternatives = Collections.emptyList();
        this.favoriteColor = favoriteColor;
        this.enabled = enabled;
        this.renderOptions = RenderOptions.DEFAULT;
    }

    /**
     * Creates a FigureDefinition from a JSON object
     */
    public static FigureDefinition fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        ResourceLocation modelPath = GeoAssets.declared(ResourceLocation.tryParse(json.get("model").getAsString()));
        ResourceLocation animationPath = GeoAssets.declared(ResourceLocation.tryParse(json.get("animation").getAsString()));

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
            // Peers that predate the flag never send it, so a missing flag means enabled
            boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
            return new FigureDefinition(id, name, modelPath, animationPath, playerUUID, favoriteColor, enabled);
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

            return new FigureDefinition(id, name, modelPath, texturePath, animationPath, alternatives,
                    RenderOptions.fromJson(json));
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

    /**
     * The model of a skin: 0 is the figure's own, 1 and up are its alternatives, which
     * fall back to the figure's model when they do not name one.
     */
    public ResourceLocation getModelForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && skinIndex - 1 < alternatives.size()) {
            ResourceLocation model = alternatives.get(skinIndex - 1).model();
            if (model != null) {
                return model;
            }
        }
        return modelPath;
    }

    public RenderOptions getRenderOptions() {
        return renderOptions;
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

    /**
     * Whether this figure can still be granted and listed. Only World Players figures
     * can be disabled; a disabled one stays defined so existing boxes and figures resolve.
     */
    public boolean isEnabled() {
        return enabled;
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
            if (!enabled) {
                json.addProperty("enabled", false);
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
                    if (alt.model() != null) {
                        altJson.addProperty("model", alt.model().toString());
                    }
                    alternativesArray.add(altJson);
                }
                json.add("alternatives", alternativesArray);
            }
            renderOptions.toJson(json);
        }

        return json;
    }

    @Override
    public String toString() {
        return "FigureDefinition{id='" + id + "', name='" + name + "', type=" + type + "}";
    }
}
