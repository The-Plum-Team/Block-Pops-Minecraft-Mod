package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.block.PopBlockColor;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a single figure within a collection.
 * Contains all resource paths needed to render the figure.
 * Can represent either a static figure (from JSON) or a dynamic player figure.
 */
public class FigureDefinition {
    /** The default skin-based figure model whose UVs match the box face bones */
    private static final String DEFAULT_MODEL_PATH = "figure/box_figure_default";

    /**
     * Represents an extra texture layer for specific bones in a multi-texture model
     */
    public record ExtraTexture(ResourceLocation texture, List<String> bones) {
        public static ExtraTexture fromJson(JsonObject json) {
            ResourceLocation texture = ResourceLocation.tryParse(json.get("texture").getAsString());
            List<String> bones = new ArrayList<>();
            JsonArray bonesArray = json.getAsJsonArray("bones");
            for (int i = 0; i < bonesArray.size(); i++) {
                bones.add(bonesArray.get(i).getAsString());
            }
            return new ExtraTexture(texture, bones);
        }
    }

    /**
     * Represents an alternative skin variant for a figure
     */
    public record AlternativeSkin(String name, @Nullable ResourceLocation model, ResourceLocation texture,
                                   List<String> hiddenBones, @Nullable Boolean showBoxFace, @Nullable float[] boxFaceUV, @Nullable Float scale) {
        public static AlternativeSkin fromJson(JsonObject json) {
            String name = json.get("name").getAsString();
            ResourceLocation model = json.has("model") ? convertToGeckoLib5Path(json.get("model").getAsString()) : null;
            ResourceLocation texture = ResourceLocation.tryParse(json.get("texture").getAsString());
            List<String> hiddenBones = new ArrayList<>();
            if (json.has("hidden_bones")) {
                JsonArray bonesArray = json.getAsJsonArray("hidden_bones");
                for (int i = 0; i < bonesArray.size(); i++) {
                    hiddenBones.add(bonesArray.get(i).getAsString());
                }
            }
            Boolean showBoxFace = json.has("show_box_face") ? json.get("show_box_face").getAsBoolean() : null;
            float[] boxFaceUV = parseBoxFaceUV(json);
            Float scale = json.has("scale") ? json.get("scale").getAsFloat() : null;
            return new AlternativeSkin(name, model, texture, hiddenBones, showBoxFace, boxFaceUV, scale);
        }
    }

    private final String id;
    private final String name;
    private final ResourceLocation modelPath;
    private final ResourceLocation texturePath;
    private final ResourceLocation animationPath;
    @Nullable private final ResourceLocation poseAnimationPath;
    private final FigureType type;
    private final UUID playerUUID;
    private final List<AlternativeSkin> alternatives;
    private final PopBlockColor favoriteColor; // For player figures, stores their chosen color
    @Nullable private final String authorUrl;
    private List<String> hiddenBones = Collections.emptyList();
    private List<ExtraTexture> extraTextures = Collections.emptyList();
    private float scale = 1.0f;
    private float guiScale = 1.0f;
    private boolean showBoxFace = true;
    @Nullable private float[] boxFaceUV = null; // [u, v, width, height, texWidth, texHeight]
    private float offsetX = 0.0f;
    private float offsetZ = 0.0f;
    private boolean poseLocked = false;

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
        this(id, name, modelPath, texturePath, animationPath, null, alternatives, authorUrl);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           @Nullable ResourceLocation poseAnimationPath,
                           List<AlternativeSkin> alternatives, @Nullable String authorUrl) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        this.poseAnimationPath = poseAnimationPath;
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
        this.poseAnimationPath = null;
        this.type = FigureType.PLAYER;
        this.playerUUID = playerUUID;
        this.alternatives = Collections.emptyList();
        this.favoriteColor = favoriteColor;
        this.authorUrl = null;
    }

    /**
     * Helper method to convert GeckoLib 4 paths to GeckoLib 5 format
     * GeckoLib 4: "modid:geo/path/file.geo.json"
     * GeckoLib 5: "modid:path/file" (no prefix, no extension)
     */
    private static ResourceLocation convertToGeckoLib5Path(String oldPath) {
        ResourceLocation loc = ResourceLocation.tryParse(oldPath);
        if (loc == null) return null;

        String path = loc.getPath();
        // Remove "geo/" prefix if present
        if (path.startsWith("geo/")) {
            path = path.substring(4);
        }
        // Remove "animations/" prefix if present
        if (path.startsWith("animations/")) {
            path = path.substring(11);
        }
        // Remove .geo.json suffix
        if (path.endsWith(".geo.json")) {
            path = path.substring(0, path.length() - 9);
        }
        // Remove .animation.json suffix
        if (path.endsWith(".animation.json")) {
            path = path.substring(0, path.length() - 15);
        }

        return ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), path);
    }

    /**
     * Creates a FigureDefinition from a JSON object
     */
    public static FigureDefinition fromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String name = json.get("name").getAsString();
        ResourceLocation modelPath = convertToGeckoLib5Path(json.get("model").getAsString());
        ResourceLocation animationPath = convertToGeckoLib5Path(json.get("animation").getAsString());
        ResourceLocation poseAnimationPath = json.has("pose_animation") ? convertToGeckoLib5Path(json.get("pose_animation").getAsString()) : null;

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
            float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
            FigureDefinition def = new FigureDefinition(id, name, modelPath, animationPath, playerUUID, favoriteColor);
            def.scale = scale;
            def.offsetX = json.has("offset_x") ? json.get("offset_x").getAsFloat() : 0.0f;
            def.offsetZ = json.has("offset_z") ? json.get("offset_z").getAsFloat() : 0.0f;
            def.guiScale = json.has("gui_scale") ? json.get("gui_scale").getAsFloat() : scale;
            boolean isDefaultModel = modelPath != null && modelPath.getPath().equals(DEFAULT_MODEL_PATH);
            def.showBoxFace = json.has("show_box_face") ? json.get("show_box_face").getAsBoolean() : isDefaultModel;
            def.boxFaceUV = parseBoxFaceUV(json);
            def.poseLocked = json.has("pose_locked") && json.get("pose_locked").getAsBoolean();
            return def;
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

            float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
            FigureDefinition def = new FigureDefinition(id, name, modelPath, texturePath, animationPath, poseAnimationPath, alternatives, authorUrl);
            def.scale = scale;
            def.offsetX = json.has("offset_x") ? json.get("offset_x").getAsFloat() : 0.0f;
            def.offsetZ = json.has("offset_z") ? json.get("offset_z").getAsFloat() : 0.0f;
            def.guiScale = json.has("gui_scale") ? json.get("gui_scale").getAsFloat() : scale;
            boolean isDefaultModel = modelPath != null && modelPath.getPath().equals(DEFAULT_MODEL_PATH);
            def.showBoxFace = json.has("show_box_face") ? json.get("show_box_face").getAsBoolean() : isDefaultModel;
            def.boxFaceUV = parseBoxFaceUV(json);
            def.poseLocked = json.has("pose_locked") && json.get("pose_locked").getAsBoolean();
            if (json.has("hidden_bones")) {
                List<String> bones = new ArrayList<>();
                JsonArray bonesArray = json.getAsJsonArray("hidden_bones");
                for (int i = 0; i < bonesArray.size(); i++) {
                    bones.add(bonesArray.get(i).getAsString());
                }
                def.hiddenBones = bones;
            }
            if (json.has("extra_textures")) {
                List<ExtraTexture> extras = new ArrayList<>();
                JsonArray extrasArray = json.getAsJsonArray("extra_textures");
                for (int i = 0; i < extrasArray.size(); i++) {
                    extras.add(ExtraTexture.fromJson(extrasArray.get(i).getAsJsonObject()));
                }
                def.extraTextures = extras;
            }
            return def;
        }
    }

    /**
     * Parses box_face_uv from JSON: {"u": N, "v": N, "w": N, "h": N, "tex_width": N, "tex_height": N}
     * Returns float[6] or null if not present.
     */
    @Nullable
    private static float[] parseBoxFaceUV(JsonObject json) {
        if (!json.has("box_face_uv")) return null;
        JsonObject uv = json.getAsJsonObject("box_face_uv");
        return new float[] {
            uv.get("u").getAsFloat(),
            uv.get("v").getAsFloat(),
            uv.get("w").getAsFloat(),
            uv.get("h").getAsFloat(),
            uv.get("tex_width").getAsFloat(),
            uv.get("tex_height").getAsFloat()
        };
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

    @Nullable
    public ResourceLocation getPoseAnimationPath() {
        return poseAnimationPath;
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

    public float getScale() {
        return scale;
    }

    public float getGuiScale() {
        return guiScale;
    }

    public boolean showBoxFace() {
        return showBoxFace;
    }

    @Nullable
    public float[] getBoxFaceUV() {
        return boxFaceUV;
    }

    public float getOffsetX() {
        return offsetX;
    }

    public float getOffsetZ() {
        return offsetZ;
    }

    public boolean isPoseLocked() {
        return poseLocked;
    }

    /**
     * Returns the scale for the given skin variant.
     * If the alternative specifies a scale, uses that; otherwise falls back to the base figure's scale.
     */
    public float getScaleForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < alternatives.size()) {
                Float altScale = alternatives.get(altListIndex).scale();
                if (altScale != null) return altScale;
            }
        }
        return scale;
    }

    public boolean getShowBoxFaceForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < alternatives.size()) {
                Boolean altVal = alternatives.get(altListIndex).showBoxFace();
                if (altVal != null) return altVal;
            }
        }
        return showBoxFace;
    }

    @Nullable
    public float[] getBoxFaceUVForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < alternatives.size()) {
                if (alternatives.get(altListIndex).showBoxFace() != null) {
                    return alternatives.get(altListIndex).boxFaceUV();
                }
            }
        }
        return boxFaceUV;
    }

    public List<String> getHiddenBones() {
        return hiddenBones;
    }

    public List<ExtraTexture> getExtraTextures() {
        return extraTextures;
    }

    public List<String> getHiddenBonesForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < alternatives.size()) {
                List<String> altBones = alternatives.get(altListIndex).hiddenBones();
                if (!altBones.isEmpty()) return altBones;
            }
        }
        return hiddenBones;
    }

    /**
     * Returns the model path for a given skin index.
     * If the alternative has a custom model, that is returned; otherwise the figure's default model is used.
     */
    public ResourceLocation getModelForSkinIndex(int skinIndex) {
        if (skinIndex > 0 && hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < alternatives.size()) {
                ResourceLocation altModel = alternatives.get(altListIndex).model();
                if (altModel != null) return altModel;
            }
        }
        return modelPath;
    }

    /**
     * Returns the union of all bone names used in hidden_bones across the default and all alternatives.
     * Used to reset bone visibility on the shared BakedGeoModel before applying the current variant's hidden bones.
     */
    public Set<String> getAllVariantBoneNames() {
        Set<String> allNames = new HashSet<>(hiddenBones);
        for (AlternativeSkin alt : alternatives) {
            allNames.addAll(alt.hiddenBones());
        }
        return allNames;
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
        if (poseAnimationPath != null) {
            json.addProperty("pose_animation", poseAnimationPath.toString());
        }

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
                    if (alt.model() != null) {
                        altJson.addProperty("model", alt.model().toString());
                    }
                    altJson.addProperty("texture", alt.texture().toString());
                    if (!alt.hiddenBones().isEmpty()) {
                        JsonArray altBonesArray = new JsonArray();
                        for (String bone : alt.hiddenBones()) {
                            altBonesArray.add(bone);
                        }
                        altJson.add("hidden_bones", altBonesArray);
                    }
                    if (alt.showBoxFace() != null) {
                        altJson.addProperty("show_box_face", alt.showBoxFace());
                    }
                    if (alt.boxFaceUV() != null) {
                        JsonObject uvObj = new JsonObject();
                        uvObj.addProperty("u", alt.boxFaceUV()[0]);
                        uvObj.addProperty("v", alt.boxFaceUV()[1]);
                        uvObj.addProperty("w", alt.boxFaceUV()[2]);
                        uvObj.addProperty("h", alt.boxFaceUV()[3]);
                        uvObj.addProperty("tex_width", alt.boxFaceUV()[4]);
                        uvObj.addProperty("tex_height", alt.boxFaceUV()[5]);
                        altJson.add("box_face_uv", uvObj);
                    }
                    alternativesArray.add(altJson);
                }
                json.add("alternatives", alternativesArray);
            }

            if (authorUrl != null && !authorUrl.isEmpty()) {
                json.addProperty("author_url", authorUrl);
            }
        }

        if (scale != 1.0f) {
            json.addProperty("scale", scale);
        }
        if (guiScale != 1.0f) {
            json.addProperty("gui_scale", guiScale);
        }
        if (!showBoxFace) {
            json.addProperty("show_box_face", false);
        }
        if (boxFaceUV != null) {
            JsonObject uvObj = new JsonObject();
            uvObj.addProperty("u", boxFaceUV[0]);
            uvObj.addProperty("v", boxFaceUV[1]);
            uvObj.addProperty("w", boxFaceUV[2]);
            uvObj.addProperty("h", boxFaceUV[3]);
            uvObj.addProperty("tex_width", boxFaceUV[4]);
            uvObj.addProperty("tex_height", boxFaceUV[5]);
            json.add("box_face_uv", uvObj);
        }
        if (poseLocked) {
            json.addProperty("pose_locked", true);
        }
        if (offsetX != 0.0f) {
            json.addProperty("offset_x", offsetX);
        }
        if (offsetZ != 0.0f) {
            json.addProperty("offset_z", offsetZ);
        }
        if (!hiddenBones.isEmpty()) {
            JsonArray bonesArray = new JsonArray();
            for (String bone : hiddenBones) {
                bonesArray.add(bone);
            }
            json.add("hidden_bones", bonesArray);
        }
        if (!extraTextures.isEmpty()) {
            JsonArray extrasArray = new JsonArray();
            for (ExtraTexture extra : extraTextures) {
                JsonObject extraJson = new JsonObject();
                extraJson.addProperty("texture", extra.texture().toString());
                JsonArray extraBonesArray = new JsonArray();
                for (String bone : extra.bones()) {
                    extraBonesArray.add(bone);
                }
                extraJson.add("bones", extraBonesArray);
                extrasArray.add(extraJson);
            }
            json.add("extra_textures", extrasArray);
        }

        return json;
    }

    @Override
    public String toString() {
        return "FigureDefinition{id='" + id + "', name='" + name + "', type=" + type + "}";
    }
}
