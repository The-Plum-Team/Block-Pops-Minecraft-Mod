package com.theplumteam.client.remote;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.GreyedTextures;
import com.theplumteam.client.runtime.RuntimeGeo;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JdkSha256;
import com.theplumteam.worldcontent.JsonFields;
import com.theplumteam.worldcontent.PlayerAssetIds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Loads the files of player collections into the runtime registries, under the ids
 * {@link PlayerAssetIds} gives them, and releases them again.
 *
 * <p>A file is loaded only after its bytes have the SHA-256 that names it, pass the whole
 * check a published file passes (the same code the upload service ran before signing the
 * index), and come to exactly what the signed index says of it. Checking, decoding and baking
 * run on a background thread of their own; publishing runs on the render thread. An id a
 * bundled resource already has is refused. A file that fails leaves only its own asset
 * missing, which draws as its fallback. Results that land after {@link #forget()} are dropped.
 */
public final class ClientPlayerAssets {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static ExecutorService executor;

    private ClientPlayerAssets() {
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BlockPops Player Assets");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /** The runtime asset id a file of this type is loaded as. */
    public static ResourceLocation id(PlayerAssetIds ids, String sha, BlobType type) {
        return switch (type) {
            case PNG -> ids.texture(sha);
            case GEO -> ids.model(sha);
            case ANIMATION -> ids.animation(sha);
        };
    }

    /** Every runtime asset id the files of a collection are loaded as. */
    public static Set<ResourceLocation> ids(PlayerAssetIds ids, Map<String, BlobInfo> blobs) {
        Set<ResourceLocation> all = new HashSet<>();
        blobs.forEach((sha, info) -> all.add(id(ids, sha, info.type())));
        return all;
    }

    /**
     * Marks a collection's files as coming, so what names them draws its fallback until they
     * are loaded, or forever if they never are. Client thread.
     */
    public static void expect(PlayerAssetIds ids, Map<String, BlobInfo> blobs) {
        Set<ResourceLocation> textures = new HashSet<>();
        Set<ResourceLocation> geo = new HashSet<>();
        blobs.forEach((sha, info) -> (info.type() == BlobType.PNG ? textures : geo).add(id(ids, sha, info.type())));
        RuntimeTextures.expect(textures);
        RuntimeGeo.expect(geo);
    }

    /**
     * Checks, decodes and publishes one file in the background.
     *
     * @param fact   what the signed index says of the file
     * @param wanted asked on the render thread whether the file is still to be published
     */
    public static void load(PlayerAssetIds ids, String sha, BlobInfo fact, byte[] bytes, BooleanSupplier wanted) {
        int generation = GENERATION.get();
        try {
            executor().execute(() -> loadOne(ids, sha, fact, bytes, wanted, generation));
        } catch (RejectedExecutionException e) {
            BlockPopsMod.LOGGER.warn("Could not queue player collection file {}: {}", sha, e.toString());
        }
    }

    private static void loadOne(PlayerAssetIds ids, String sha, BlobInfo fact, byte[] bytes, BooleanSupplier wanted,
                                int generation) {
        ResourceLocation id = id(ids, sha, fact.type());
        try {
            if (!JdkSha256.of(bytes).equals(sha)) {
                throw new IllegalStateException("its bytes do not match its SHA-256");
            }
            // The whole check of a published file, made again here: nothing is decoded on the index's word
            BlobInfo actual = fact.type().validatePlayer(bytes);
            if (!actual.sameFile(fact)) {
                throw new IllegalStateException("it is not what its index says it is");
            }
            switch (fact.type()) {
                case PNG -> {
                    NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                    if (image.getWidth() != fact.width() || image.getHeight() != fact.height()) {
                        image.close();
                        throw new IllegalStateException("it decoded to " + image.getWidth() + "x" + image.getHeight());
                    }
                    publish(generation, () -> {
                        if (!wanted.getAsBoolean() || bundled(id)) {
                            image.close();
                        } else {
                            RuntimeTextures.register(id, image);
                        }
                    }, image::close);
                }
                case GEO -> {
                    JsonObject json = JsonFields.parse(bytes, fact.type().maxBytes());
                    var model = RuntimeGeo.bakeModel(id, json);
                    publish(generation, () -> {
                        if (wanted.getAsBoolean() && !bundled(GeoAssets.modelFile(id))) {
                            RuntimeGeo.putModel(id, model);
                        }
                    }, () -> { });
                }
                case ANIMATION -> {
                    JsonObject json = JsonFields.parse(bytes, fact.type().maxBytes());
                    var animations = RuntimeGeo.bakeAnimations(id, json);
                    publish(generation, () -> {
                        if (wanted.getAsBoolean() && !bundled(GeoAssets.animationFile(id))) {
                            RuntimeGeo.putAnimations(id, animations);
                        }
                    }, () -> { });
                }
            }
        } catch (Exception e) {
            // The id stays pending, so its fallback keeps drawing
            BlockPopsMod.LOGGER.warn("Could not load player collection file {}: {}", id, e.getMessage());
        }
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

    /** Releases the assets of one collection, with their greyed copies. Render thread only. */
    public static void release(PlayerAssetIds ids) {
        RuntimeGeo.clear(ids::owns);
        RuntimeTextures.clear(ids::owns);
        GreyedTextures.forget(ids::owns);
    }

    /**
     * Releases every player collection's assets and drops whatever is still being loaded, as
     * when leaving a server. Never touches other owners' assets. Render thread only.
     */
    public static void forget() {
        GENERATION.incrementAndGet();
        RuntimeGeo.clear(PlayerAssetIds::ownsAny);
        RuntimeTextures.clear(PlayerAssetIds::ownsAny);
        GreyedTextures.forget(PlayerAssetIds::ownsAny);
    }
}
