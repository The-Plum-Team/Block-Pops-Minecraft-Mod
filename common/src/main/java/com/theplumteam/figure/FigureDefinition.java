package com.theplumteam.figure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
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
     * model, size and box face; whatever it leaves out comes from the figure.
     *
     * @param scale       its size in place of the figure's
     * @param showBoxFace whether the box shows its face, in place of the figure's choice;
     *                    when set, {@code boxFaceUv} replaces the figure's face region too
     * @param hiddenBones bones not drawn on this skin, in place of the figure's when not empty
     */
    public record AlternativeSkin(String name, ResourceLocation texture, @Nullable ResourceLocation model,
                                  @Nullable Float scale, @Nullable Boolean showBoxFace,
                                  @Nullable float[] boxFaceUv, List<String> hiddenBones) {
        public AlternativeSkin(String name, ResourceLocation texture) {
            this(name, texture, null, null, null, null, List.of());
        }

        public static AlternativeSkin fromJson(JsonObject json) {
            String name = json.get("name").getAsString();
            ResourceLocation texture = ResourceLocation.tryParse(json.get("texture").getAsString());
            ResourceLocation model = json.has("model")
                    ? GeoAssets.declared(ResourceLocation.tryParse(json.get("model").getAsString()))
                    : null;
            Float scale = json.has("scale") ? json.get("scale").getAsFloat() : null;
            Boolean showBoxFace = json.has("show_box_face") ? json.get("show_box_face").getAsBoolean() : null;
            return new AlternativeSkin(name, texture, model, scale, showBoxFace, readBoxFaceUv(json),
                    readStrings(json, "hidden_bones"));
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("texture", texture.toString());
            if (model != null) {
                json.addProperty("model", model.toString());
            }
            if (scale != null) {
                json.addProperty("scale", scale);
            }
            if (showBoxFace != null) {
                json.addProperty("show_box_face", showBoxFace);
            }
            writeBoxFaceUv(json, boxFaceUv);
            writeStrings(json, "hidden_bones", hiddenBones);
            return json;
        }
    }

    /**
     * A texture the named bones of a figure's own model are drawn with, instead of the
     * figure's texture, for models laid out across several images.
     */
    public record ExtraTexture(ResourceLocation texture, List<String> bones) {
        static ExtraTexture fromJson(JsonObject json) {
            return new ExtraTexture(ResourceLocation.tryParse(json.get("texture").getAsString()),
                    readStrings(json, "bones"));
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("texture", texture.toString());
            writeStrings(json, "bones", bones);
            return json;
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
     * @param showBoxFace   whether the box shows the figure's face, when the default (a face
     *                      region, or the default skin model) is not right
     * @param poseLocked    whether the figure keeps its pose instead of cycling on use
     * @param hiddenBones   bones of the figure's model that are not drawn
     * @param extraTextures textures some bones of the figure's model are drawn with
     * @param poses         the figure's own poses, by their names in its pose animation, which
     *                      using the figure cycles through between standing and sitting
     */
    public record RenderOptions(@Nullable ResourceLocation poseAnimation, float scale, float guiScale,
                                @Nullable float[] boxFaceUv, @Nullable float[] offset,
                                @Nullable Boolean showBoxFace, boolean poseLocked,
                                List<String> hiddenBones, List<ExtraTexture> extraTextures, List<String> poses) {
        public static final RenderOptions DEFAULT = new RenderOptions(null, 1.0f, 1.0f, null, null, null, false,
                List.of(), List.of(), List.of());
        public static final String STAND_POSE = "Pose_Stand";
        public static final String SIT_POSE = "Pose_Sit";
        private static final String[] OFFSET_KEYS = {"offset_x", "offset_y", "offset_z"};

        static RenderOptions fromJson(JsonObject json) {
            ResourceLocation poseAnimation = json.has("pose_animation")
                    ? GeoAssets.declared(ResourceLocation.tryParse(json.get("pose_animation").getAsString()))
                    : null;
            float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
            float guiScale = json.has("gui_scale") ? json.get("gui_scale").getAsFloat() : scale;
            float[] offset = null;
            for (int i = 0; i < OFFSET_KEYS.length; i++) {
                if (json.has(OFFSET_KEYS[i])) {
                    if (offset == null) {
                        offset = new float[] {Float.NaN, Float.NaN, Float.NaN};
                    }
                    offset[i] = json.get(OFFSET_KEYS[i]).getAsFloat();
                }
            }
            Boolean showBoxFace = json.has("show_box_face") ? json.get("show_box_face").getAsBoolean() : null;
            boolean poseLocked = json.has("pose_locked") && json.get("pose_locked").getAsBoolean();
            List<ExtraTexture> extraTextures = new ArrayList<>();
            if (json.has("extra_textures")) {
                for (var element : json.getAsJsonArray("extra_textures")) {
                    extraTextures.add(ExtraTexture.fromJson(element.getAsJsonObject()));
                }
            }
            return new RenderOptions(poseAnimation, scale, guiScale, readBoxFaceUv(json), offset,
                    showBoxFace, poseLocked, readStrings(json, "hidden_bones"), List.copyOf(extraTextures),
                    readStrings(json, "poses"));
        }

        /** How many poses using the figure cycles through: standing, its own poses, then sitting. */
        public int poseCount() {
            return poses.size() + 2;
        }

        /** The animation of a pose in that cycle; an index out of it stands. */
        public String poseName(int index) {
            if (index <= 0 || index >= poseCount()) {
                return STAND_POSE;
            }
            return index == poseCount() - 1 ? SIT_POSE : poses.get(index - 1);
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
            writeBoxFaceUv(json, boxFaceUv);
            if (offset != null) {
                for (int i = 0; i < OFFSET_KEYS.length; i++) {
                    if (!Float.isNaN(offset[i])) {
                        json.addProperty(OFFSET_KEYS[i], offset[i]);
                    }
                }
            }
            if (showBoxFace != null) {
                json.addProperty("show_box_face", showBoxFace);
            }
            if (poseLocked) {
                json.addProperty("pose_locked", true);
            }
            writeStrings(json, "hidden_bones", hiddenBones);
            writeStrings(json, "poses", poses);
            if (!extraTextures.isEmpty()) {
                JsonArray array = new JsonArray();
                extraTextures.forEach(extra -> array.add(extra.toJson()));
                json.add("extra_textures", array);
            }
        }
    }

    private static List<String> readStrings(JsonObject json, String key) {
        if (!json.has(key)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (var element : json.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    private static void writeStrings(JsonObject json, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add(key, array);
    }

    @Nullable
    private static float[] readBoxFaceUv(JsonObject json) {
        if (!json.has("box_face_uv")) {
            return null;
        }
        JsonObject uv = json.getAsJsonObject("box_face_uv");
        return new float[] {uv.get("u").getAsFloat(), uv.get("v").getAsFloat(),
                uv.get("w").getAsFloat(), uv.get("h").getAsFloat(),
                uv.get("tex_width").getAsFloat(), uv.get("tex_height").getAsFloat()};
    }

    private static void writeBoxFaceUv(JsonObject json, @Nullable float[] boxFaceUv) {
        if (boxFaceUv == null) {
            return;
        }
        JsonObject uv = new JsonObject();
        uv.addProperty("u", boxFaceUv[0]);
        uv.addProperty("v", boxFaceUv[1]);
        uv.addProperty("w", boxFaceUv[2]);
        uv.addProperty("h", boxFaceUv[3]);
        uv.addProperty("tex_width", boxFaceUv[4]);
        uv.addProperty("tex_height", boxFaceUv[5]);
        json.add("box_face_uv", uv);
    }

    /** The skin model every collection figure was drawn on before figures had their own. */
    public static final ResourceLocation DEFAULT_MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "figure/box_figure_default");

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
    private final boolean special; // A plush only players with special access receive
    private final RenderOptions renderOptions;

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath) {
        this(id, name, modelPath, texturePath, animationPath, Collections.emptyList());
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, RenderOptions.DEFAULT, false);
    }

    /**
     * Constructor for static figures that may be special
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, boolean special) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, RenderOptions.DEFAULT, special);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, RenderOptions renderOptions) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, renderOptions, false);
    }

    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, RenderOptions renderOptions,
                           boolean special) {
        this(id, name, modelPath, texturePath, animationPath, alternatives, renderOptions, special, true);
    }

    /**
     * Constructor for static figures that an operator may have retired, as World
     * collection figures can be
     */
    public FigureDefinition(String id, String name, ResourceLocation modelPath,
                           ResourceLocation texturePath, ResourceLocation animationPath,
                           List<AlternativeSkin> alternatives, RenderOptions renderOptions,
                           boolean special, boolean enabled) {
        this.id = id;
        this.name = name;
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        this.type = FigureType.STATIC;
        this.playerUUID = null;
        this.alternatives = new ArrayList<>(alternatives);
        this.favoriteColor = null; // Static figures don't have favorite colors
        this.enabled = enabled;
        this.special = special;
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
        this.special = false;
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

            boolean special = json.has("special") && json.get("special").getAsBoolean();
            return new FigureDefinition(id, name, modelPath, texturePath, animationPath, alternatives,
                    RenderOptions.fromJson(json), special);
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

    @Nullable
    private AlternativeSkin alternative(int skinIndex) {
        return skinIndex > 0 && skinIndex - 1 < alternatives.size() ? alternatives.get(skinIndex - 1) : null;
    }

    /** A skin's size: its alternative's own, or the figure's. */
    public float getScaleForSkinIndex(int skinIndex) {
        AlternativeSkin alternative = alternative(skinIndex);
        return alternative != null && alternative.scale() != null ? alternative.scale() : renderOptions.scale();
    }

    /**
     * A skin's poses, or null for the shared poses. A skin on the default skin model
     * always takes the shared ones, which drive that model's bones; any other model
     * takes the figure's own.
     */
    @Nullable
    public ResourceLocation getPoseAnimationForSkinIndex(int skinIndex) {
        return DEFAULT_MODEL.equals(getModelForSkinIndex(skinIndex)) ? null : renderOptions.poseAnimation();
    }

    /**
     * Where a skin's face lies in its texture, or null for the skin layout. An
     * alternative that decides whether its face shows brings its own region.
     */
    @Nullable
    public float[] getBoxFaceUvForSkinIndex(int skinIndex) {
        AlternativeSkin alternative = alternative(skinIndex);
        return alternative != null && alternative.showBoxFace() != null
                ? alternative.boxFaceUv() : renderOptions.boxFaceUv();
    }

    /** The bones a skin does not draw: its alternative's own when it names any, else the figure's. */
    public List<String> getHiddenBonesForSkinIndex(int skinIndex) {
        AlternativeSkin alternative = alternative(skinIndex);
        return alternative != null && !alternative.hiddenBones().isEmpty()
                ? alternative.hiddenBones() : renderOptions.hiddenBones();
    }

    /**
     * The extra textures a skin is drawn with. They belong to the figure's own model, so
     * an alternative on another model has none.
     */
    public List<ExtraTexture> getExtraTexturesForSkinIndex(int skinIndex) {
        return modelPath.equals(getModelForSkinIndex(skinIndex)) ? renderOptions.extraTextures() : List.of();
    }

    /**
     * Whether the box shows a skin's face. Unless the alternative or the figure says,
     * it does when the face has a region or the skin is on the default skin model.
     */
    public boolean showsBoxFaceForSkinIndex(int skinIndex) {
        AlternativeSkin alternative = alternative(skinIndex);
        if (alternative != null && alternative.showBoxFace() != null) {
            return alternative.showBoxFace();
        }
        if (renderOptions.showBoxFace() != null) {
            return renderOptions.showBoxFace();
        }
        return getBoxFaceUvForSkinIndex(skinIndex) != null || DEFAULT_MODEL.equals(getModelForSkinIndex(skinIndex));
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
     * Whether this figure can still be granted and listed. Only World Players figures and
     * World collection figures can be disabled; a disabled one stays defined so existing
     * boxes and figures resolve.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether this is a special plush: listed and counted apart from the regular ones, and
     * handed out only to players with special access.
     */
    public boolean isSpecial() {
        return special;
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
                    alternativesArray.add(alt.toJson());
                }
                json.add("alternatives", alternativesArray);
            }
            if (special) {
                json.addProperty("special", true);
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
