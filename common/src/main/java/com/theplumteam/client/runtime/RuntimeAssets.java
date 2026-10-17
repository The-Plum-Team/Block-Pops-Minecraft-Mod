package com.theplumteam.client.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads files laid out like a resource pack's {@code assets/blockpops} tree into the
 * runtime registries: GeckoLib models and animations into {@link RuntimeGeo}, PNGs into
 * {@link RuntimeTextures}.
 *
 * <p>Reading, decoding and baking run on one background thread; publishing runs on the
 * render thread. A file whose id a bundled resource already has is refused, so loaded
 * content can never replace the mod's own or vanilla's. Results that land after
 * {@link #clear()} are dropped.
 *
 * <p>The registries also hold the assets of World collections, which a server sends by
 * another route and {@code ClientWorldAssets} loads and releases. Their ids belong to
 * {@link WorldContentIds}, and {@link #clear()} leaves them alone, so a Custom sync never
 * takes a World collection's textures or models away.
 */
public final class RuntimeAssets {
    private static final String ROOT = "assets/" + BlockPopsMod.MOD_ID + "/";
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static ExecutorService executor;

    private RuntimeAssets() {
    }

    private enum Kind { MODEL, ANIMATION, TEXTURE }

    private record Asset(Kind kind, ResourceLocation id, Path file) {
    }

    /** The single background thread that reads and bakes runtime assets. */
    public static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BlockPops Runtime Assets");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /** The current generation, which {@link #clear()} advances; pass it to {@link #load}. */
    public static int generation() {
        return GENERATION.get();
    }

    /**
     * Marks the files at these asset paths as coming, before anything that names them is
     * registered, so they are drawn by their fallbacks until {@link #load} delivers them.
     */
    public static void expect(Iterable<String> paths) {
        List<ResourceLocation> geo = new ArrayList<>();
        List<ResourceLocation> textures = new ArrayList<>();
        for (String path : paths) {
            Asset asset = classify(path, null);
            if (asset != null) {
                (asset.kind() == Kind.TEXTURE ? textures : geo).add(asset.id());
            }
        }
        RuntimeGeo.expect(geo);
        RuntimeTextures.expect(textures);
    }

    /**
     * Loads files keyed by their path in the asset tree, such as
     * {@code assets/blockpops/geckolib/models/figure/x/y.geo.json}. Paths outside the
     * mod's models, animations and textures are ignored. The ids become pending at once.
     */
    public static CompletableFuture<Void> load(Map<String, Path> files) {
        return load(files, GENERATION.get());
    }

    /** Loads files for a generation taken earlier; nothing is published if it has passed. */
    public static CompletableFuture<Void> load(Map<String, Path> files, int generation) {
        if (generation != GENERATION.get()) {
            return CompletableFuture.completedFuture(null);
        }
        expect(files.keySet());
        List<Asset> assets = new ArrayList<>();
        files.forEach((path, file) -> {
            Asset asset = classify(path, file);
            if (asset != null) {
                assets.add(asset);
            }
        });
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (Asset asset : assets) {
            tasks.add(CompletableFuture.runAsync(() -> loadOne(asset, generation), executor()));
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
    }

    private static void loadOne(Asset asset, int generation) {
        try {
            switch (asset.kind()) {
                case MODEL -> {
                    JsonObject json = readJson(asset.file());
                    var model = RuntimeGeo.bakeModel(asset.id(), json.deepCopy());
                    publish(generation, () -> {
                        if (!bundled(GeoAssets.modelFile(asset.id()))) {
                            RuntimeGeo.putModel(asset.id(), model, json);
                        }
                    });
                }
                case ANIMATION -> {
                    var animations = RuntimeGeo.bakeAnimations(asset.id(), readJson(asset.file()));
                    publish(generation, () -> {
                        if (!bundled(GeoAssets.animationFile(asset.id()))) {
                            RuntimeGeo.putAnimations(asset.id(), animations);
                        }
                    });
                }
                case TEXTURE -> {
                    NativeImage image;
                    try (InputStream stream = Files.newInputStream(asset.file())) {
                        image = NativeImage.read(stream);
                    }
                    publish(generation, () -> {
                        if (bundled(asset.id())) {
                            image.close();
                        } else {
                            RuntimeTextures.register(asset.id(), image);
                        }
                    }, image::close);
                }
            }
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not load {}: {}", asset.id(), e.toString());
        }
    }

    private static void publish(int generation, Runnable action) {
        publish(generation, action, () -> { });
    }

    private static void publish(int generation, Runnable action, Runnable dropped) {
        Minecraft.getInstance().execute(() -> {
            if (generation == GENERATION.get()) {
                action.run();
            } else {
                dropped.run();
            }
        });
    }

    private static boolean bundled(ResourceLocation file) {
        return Minecraft.getInstance().getResourceManager().getResource(file).isPresent();
    }

    private static JsonObject readJson(Path file) throws java.io.IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Nullable
    private static Asset classify(String path, Path file) {
        if (!path.startsWith(ROOT)) {
            return null;
        }
        String rest = path.substring(ROOT.length());
        String model = strip(rest, new String[] {"geckolib/models/", "geo/"}, ".geo.json");
        if (model != null) {
            return new Asset(Kind.MODEL, GeoAssets.model(BlockPopsMod.MOD_ID, model), file);
        }
        String animation = strip(rest, new String[] {"geckolib/animations/", "animations/"}, ".animation.json");
        if (animation != null) {
            return new Asset(Kind.ANIMATION, GeoAssets.animation(BlockPopsMod.MOD_ID, animation), file);
        }
        if (rest.startsWith("textures/") && rest.endsWith(".png")) {
            return new Asset(Kind.TEXTURE, ResourceLocations.of(BlockPopsMod.MOD_ID, rest), file);
        }
        return null;
    }

    @Nullable
    private static String strip(String path, String[] prefixes, String suffix) {
        if (!path.endsWith(suffix)) {
            return null;
        }
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length(), path.length() - suffix.length());
            }
        }
        return null;
    }

    /**
     * Forgets everything loaded so far and drops what is still in flight, except World
     * content, which has its own owner. Render thread only.
     */
    public static void clear() {
        GENERATION.incrementAndGet();
        RuntimeGeo.clear(id -> !WorldContentIds.owns(id));
        RuntimeTextures.clear(id -> !WorldContentIds.owns(id));
        FigureVariants.clear();
    }
}
