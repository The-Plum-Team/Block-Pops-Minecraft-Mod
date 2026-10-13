package com.theplumteam.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the model and texture a skin is drawn with when it hides bones or draws some
 * bones with extra textures.
 *
 * <p>Both are done on the data rather than in the renderers, so every GeckoLib version
 * draws the result in one ordinary pass: hidden bones lose their cubes (and so do their
 * children), and the figure's texture and its extra textures are placed side by side in
 * one image, with the cubes of each extra texture's bones moved onto its part. A variant
 * is built the first time it is asked for; the skin is drawn plainly until it is ready.
 */
public final class FigureVariants {
    /** What a variant is built from; equal skins share one. */
    private record Key(ResourceLocation model, ResourceLocation texture, List<String> hiddenBones,
                       List<FigureDefinition.ExtraTexture> extraTextures) {
    }

    /** The model and texture a skin is drawn with. */
    public record Variant(ResourceLocation model, ResourceLocation texture) {
    }

    private static final Map<Key, Variant> READY = new ConcurrentHashMap<>();
    private static final Set<Key> STARTED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private FigureVariants() {
    }

    /**
     * The variant a skin is drawn with, or null when it needs none or is not built yet;
     * either way the caller draws the skin's own model and texture.
     */
    @Nullable
    public static Variant resolve(FigureDefinition figure, int skinIndex) {
        List<String> hidden = figure.getHiddenBonesForSkinIndex(skinIndex);
        List<FigureDefinition.ExtraTexture> extras = figure.getExtraTexturesForSkinIndex(skinIndex);
        if (hidden.isEmpty() && extras.isEmpty()) {
            return null;
        }
        ResourceLocation texture = textureOf(figure, skinIndex);
        Key key = new Key(figure.getModelForSkinIndex(skinIndex), texture, hidden, extras);
        Variant variant = READY.get(key);
        if (variant != null || texture == null || waiting(key)) {
            return variant;
        }
        if (STARTED.add(key)) {
            build(key);
        }
        return null;
    }

    @Nullable
    private static ResourceLocation textureOf(FigureDefinition figure, int skinIndex) {
        if (skinIndex > 0 && skinIndex - 1 < figure.getAlternatives().size()) {
            return figure.getAlternatives().get(skinIndex - 1).texture();
        }
        return figure.getTexturePath();
    }

    private static boolean waiting(Key key) {
        if (RuntimeGeo.isPending(key.model()) || RuntimeTextures.isPending(key.texture())) {
            return true;
        }
        for (FigureDefinition.ExtraTexture extra : key.extraTextures()) {
            if (RuntimeTextures.isPending(extra.texture())) {
                return true;
            }
        }
        return false;
    }

    private static void build(Key key) {
        int id = NEXT_ID.incrementAndGet();
        ResourceLocation modelId = ResourceLocations.of(BlockPopsMod.MOD_ID, "runtime/variant_" + id);
        ResourceLocation textureId = key.extraTextures().isEmpty() ? key.texture()
                : ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/runtime/variant_" + id + ".png");
        // Sources are read on the render thread, where both the resource manager and the
        // runtime textures are safe to touch; the transform and bake run in the background.
        Minecraft.getInstance().execute(() -> {
            JsonObject source = RuntimeGeo.source(key.model());
            if (source == null) {
                source = readBundled(GeoAssets.modelFile(key.model()));
            }
            if (source == null) {
                BlockPopsMod.LOGGER.warn("No model source for {}, drawing it without its variant", key.model());
                return;
            }
            JsonObject geometry = source.deepCopy();
            CompletableFuture.supplyAsync(() -> RuntimeGeo.bakeModel(modelId, transform(geometry, key)),
                    RuntimeAssets.executor()).whenComplete((model, error) -> Minecraft.getInstance().execute(() -> {
                if (error != null) {
                    BlockPopsMod.LOGGER.warn("Could not build the variant of {}: {}", key.model(), error.toString());
                    return;
                }
                if (!key.extraTextures().isEmpty() && !composeTexture(key, geometry, textureId)) {
                    return;
                }
                RuntimeGeo.putModel(modelId, model);
                READY.put(key, new Variant(modelId, textureId));
            }));
        });
    }

    /** Hides bones and moves extra-texture bones onto their part of the combined texture. */
    private static JsonObject transform(JsonObject geo, Key key) {
        JsonObject geometry = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonArray bones = geometry.getAsJsonArray("bones");
        Map<String, String> parents = new HashMap<>();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            parents.put(bone.get("name").getAsString(), bone.has("parent") ? bone.get("parent").getAsString() : null);
        }
        Set<String> hidden = new HashSet<>(key.hiddenBones());
        JsonObject description = geometry.getAsJsonObject("description");
        float width = description.get("texture_width").getAsFloat();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            if (hiddenWithAncestors(name, hidden, parents)) {
                bone.remove("cubes");
                continue;
            }
            int slot = extraSlot(name, key.extraTextures());
            if (slot > 0 && bone.has("cubes")) {
                for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                    shiftUv(cube.getAsJsonObject(), width * slot);
                }
            }
        }
        if (!key.extraTextures().isEmpty()) {
            description.addProperty("texture_width", width * (key.extraTextures().size() + 1));
        }
        return geo;
    }

    private static boolean hiddenWithAncestors(String name, Set<String> hidden, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        for (String bone = name; bone != null && seen.add(bone); bone = parents.get(bone)) {
            if (hidden.contains(bone)) {
                return true;
            }
        }
        return false;
    }

    /** 1 + the index of the first extra texture naming the bone, or 0 for the figure's own. */
    private static int extraSlot(String bone, List<FigureDefinition.ExtraTexture> extras) {
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).bones().contains(bone)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static void shiftUv(JsonObject cube, float shift) {
        JsonElement uv = cube.get("uv");
        if (uv == null) {
            return;
        }
        if (uv.isJsonArray()) {
            JsonArray box = uv.getAsJsonArray();
            box.set(0, new com.google.gson.JsonPrimitive(box.get(0).getAsFloat() + shift));
            return;
        }
        for (Map.Entry<String, JsonElement> face : uv.getAsJsonObject().entrySet()) {
            JsonObject faceUv = face.getValue().getAsJsonObject();
            if (faceUv.has("uv")) {
                JsonArray start = faceUv.getAsJsonArray("uv");
                start.set(0, new com.google.gson.JsonPrimitive(start.get(0).getAsFloat() + shift));
            }
        }
    }

    /** Places the skin's texture and its extra textures side by side under the variant's id. */
    private static boolean composeTexture(Key key, JsonObject geo, ResourceLocation textureId) {
        JsonObject description = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonObject("description");
        int slots = key.extraTextures().size() + 1;
        float unitsWide = description.get("texture_width").getAsFloat() / slots;
        float unitsHigh = description.get("texture_height").getAsFloat();
        List<NativeImage> images = new ArrayList<>();
        List<NativeImage> owned = new ArrayList<>();
        try {
            List<ResourceLocation> sources = new ArrayList<>();
            sources.add(key.texture());
            key.extraTextures().forEach(extra -> sources.add(extra.texture()));
            for (ResourceLocation source : sources) {
                NativeImage image = RuntimeTextures.image(source);
                if (image == null) {
                    image = readBundledImage(source);
                    if (image == null) {
                        BlockPopsMod.LOGGER.warn("No texture {} for a figure variant", source);
                        return false;
                    }
                    owned.add(image);
                }
                images.add(image);
            }
            // Every part gets the resolution of the sharpest one, enlarged without smoothing.
            float texelsPerUnit = 0;
            for (NativeImage image : images) {
                texelsPerUnit = Math.max(texelsPerUnit, image.getWidth() / unitsWide);
            }
            int slotWidth = Math.round(unitsWide * texelsPerUnit);
            int height = Math.round(unitsHigh * texelsPerUnit);
            NativeImage combined = new NativeImage(slotWidth * slots, height, true);
            for (int slot = 0; slot < slots; slot++) {
                NativeImage image = images.get(slot);
                for (int y = 0; y < height; y++) {
                    int sourceY = Math.min(image.getHeight() - 1, y * image.getHeight() / height);
                    for (int x = 0; x < slotWidth; x++) {
                        int sourceX = Math.min(image.getWidth() - 1, x * image.getWidth() / slotWidth);
                        //? if >=1.21.2 {
                        /*combined.setPixel(slot * slotWidth + x, y, image.getPixel(sourceX, sourceY));
                        *///? } else {
                        combined.setPixelRGBA(slot * slotWidth + x, y, image.getPixelRGBA(sourceX, sourceY));
                        //? }
                    }
                }
            }
            RuntimeTextures.register(textureId, combined);
            return true;
        } finally {
            owned.forEach(NativeImage::close);
        }
    }

    @Nullable
    private static JsonObject readBundled(ResourceLocation file) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            return null;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not read {}: {}", file, e.toString());
            return null;
        }
    }

    @Nullable
    private static NativeImage readBundledImage(ResourceLocation texture) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream stream = resource.get().open()) {
            return NativeImage.read(stream);
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not read {}: {}", texture, e.toString());
            return null;
        }
    }

    /** Forgets every variant; they are rebuilt on demand. */
    static void clear() {
        READY.clear();
        STARTED.clear();
    }
}
