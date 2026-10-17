package com.theplumteam.client.runtime;

import com.google.gson.JsonObject;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
//? if >=26 {
/*import com.geckolib.cache.animation.BakedAnimations;
*///? } else {
import software.bernie.geckolib.loading.object.BakedAnimations;
//? }

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * GeckoLib models and animations that arrive while the game runs instead of with the
 * resource packs: remote collections, World collections, and figure variants built from
 * another model.
 *
 * <p>They live in this registry rather than in GeckoLib's own caches, which are rebuilt
 * on every resource reload and on 26.x cannot be added to at all. The figure models look
 * here first ({@code getBakedModel} and the animation lookup), so the same ids work on
 * every GeckoLib version and survive a reload.
 *
 * <p>An id announced with {@link #expect} but not baked yet is pending: the figure models
 * draw the default figure in its place until it arrives. So is every World content id
 * not baked here, which GeckoLib cannot have either: in single player the integrated
 * server registers a changed collection before its sync tells this client what to expect.
 */
public final class RuntimeGeo {
    private static final Map<ResourceLocation, BakedGeoModel> MODELS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, BakedAnimations> ANIMATIONS = new ConcurrentHashMap<>();
    // The JSON each runtime model was baked from, for building variants of it.
    private static final Map<ResourceLocation, JsonObject> SOURCES = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> PENDING = ConcurrentHashMap.newKeySet();

    private RuntimeGeo() {
    }

    /** Marks ids as coming, so they are drawn as the default figure until baked. */
    public static void expect(Collection<ResourceLocation> ids) {
        PENDING.addAll(ids);
    }

    /** Whether an id was announced and has not arrived (or failed to), or is World content not baked here. */
    public static boolean isPending(@Nullable ResourceLocation id) {
        return id != null && (PENDING.contains(id)
                || WorldContentIds.owns(id) && !MODELS.containsKey(id) && !ANIMATIONS.containsKey(id));
    }

    @Nullable
    public static BakedGeoModel model(ResourceLocation id) {
        return MODELS.get(id);
    }

    @Nullable
    public static BakedAnimations animations(ResourceLocation id) {
        return ANIMATIONS.get(id);
    }

    /** The JSON a model loaded here was baked from, or null for any other model. */
    @Nullable
    public static JsonObject source(ResourceLocation id) {
        return SOURCES.get(id);
    }

    /** Whether this registry owns the id, so GeckoLib's caches must not be asked for it. */
    public static boolean ownsAnimations(ResourceLocation id) {
        return ANIMATIONS.containsKey(id) || isPending(id);
    }

    /** Bakes a model from its JSON; safe off the render thread, published by {@link #putModel}. */
    public static BakedGeoModel bakeModel(ResourceLocation id, JsonObject json) {
        //? if >=26 {
        /*return new com.geckolib.loading.loader.GeckoLibGsonLoader().bakeGeckoLibModelFile(id, json);
        *///? } elif >=1.21 {
        /*software.bernie.geckolib.loading.json.raw.Model model = software.bernie.geckolib.loading.json.typeadapter
                .KeyFramesAdapter.GEO_GSON.fromJson(json, software.bernie.geckolib.loading.json.raw.Model.class);
        return software.bernie.geckolib.loading.object.BakedModelFactory.getForNamespace(id.getNamespace())
                .constructGeoModel(software.bernie.geckolib.loading.object.GeometryTree.fromModel(model));
        *///? } else {
        software.bernie.geckolib.loading.json.raw.Model model = software.bernie.geckolib.util.JsonUtil.GEO_GSON
                .fromJson(json, software.bernie.geckolib.loading.json.raw.Model.class);
        return software.bernie.geckolib.loading.object.BakedModelFactory.getForNamespace(id.getNamespace())
                .constructGeoModel(software.bernie.geckolib.loading.object.GeometryTree.fromModel(model));
        //? }
    }

    /** Bakes an animation file from its JSON; safe off the render thread. */
    public static BakedAnimations bakeAnimations(ResourceLocation id, JsonObject json) {
        //? if >=26 {
        /*return new com.geckolib.loading.loader.GeckoLibGsonLoader().bakeGeckoLibAnimationsFile(id, json,
                com.geckolib.loading.math.MathParser.create());
        *///? } elif >=1.21 {
        /*return software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter.GEO_GSON.fromJson(
                json.getAsJsonObject("animations"), BakedAnimations.class);
        *///? } else {
        return software.bernie.geckolib.util.JsonUtil.GEO_GSON.fromJson(
                json.getAsJsonObject("animations"), BakedAnimations.class);
        //? }
    }

    public static void putModel(ResourceLocation id, BakedGeoModel model) {
        MODELS.put(id, model);
        PENDING.remove(id);
    }

    /** Publishes a loaded model together with the JSON it was baked from. */
    public static void putModel(ResourceLocation id, BakedGeoModel model, JsonObject source) {
        SOURCES.put(id, source);
        putModel(id, model);
    }

    public static void putAnimations(ResourceLocation id, BakedAnimations animations) {
        ANIMATIONS.put(id, animations);
        PENDING.remove(id);
    }

    /** Forgets everything, as when leaving a server whose collections these were. */
    public static void clear() {
        clear(id -> true);
    }

    /** Forgets the models, animations and pending ids that match, leaving every other owner's. */
    public static void clear(Predicate<ResourceLocation> which) {
        MODELS.keySet().removeIf(which);
        ANIMATIONS.keySet().removeIf(which);
        SOURCES.keySet().removeIf(which);
        PENDING.removeIf(which);
    }
}
