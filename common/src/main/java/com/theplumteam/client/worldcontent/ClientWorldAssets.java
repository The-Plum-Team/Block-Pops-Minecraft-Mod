package com.theplumteam.client.worldcontent;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.GreyedTextures;
import com.theplumteam.client.runtime.RuntimeGeo;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.JsonFields;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Loads the files of World collections into the runtime registries, under the ids
 * {@link WorldContentIds} gives them, and releases them again.
 *
 * <p>Checking, decoding and baking run on a background thread of their own, so World
 * content never waits behind Custom collection assets or holds them up; publishing runs
 * on the render thread. A file is loaded only after its bytes match its sha and pass the
 * same validator the server ran, and their description matches the file table. An id a
 * bundled resource already has is refused. Results that land after {@link #forget()}
 * are dropped.
 *
 * <p>Nothing is cached on disk: what one server sent is never offered to another.
 */
public final class ClientWorldAssets {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static ExecutorService executor;

    private ClientWorldAssets() {
    }

    /** What became of a file handed to {@link #load}. Called on the render thread. */
    public interface Listener {
        /** The bytes do not match the sha or the file table, so another copy may fix it. */
        void unverified(String sha, String reason);

        /** The file was verified but could not be loaded; another copy would fail the same way. */
        void failed(String sha, String reason);

        /** The file is in the runtime registries, or was refused because a bundled resource has its id. */
        void loaded(String sha);

        /** Whether the file is still wanted when it is ready to be published. */
        boolean wants(String sha);
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BlockPops World Assets");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /** The runtime asset id a file of this type is loaded as. */
    public static ResourceLocation id(String sha, BlobType type) {
        return switch (type) {
            case PNG -> WorldContentIds.texture(sha);
            case GEO -> WorldContentIds.model(sha);
            case ANIMATION -> WorldContentIds.animation(sha);
        };
    }

    /**
     * Marks the files as coming, so what names them draws its fallback until they are
     * loaded or forever if they never are. Client thread.
     */
    public static void expect(Collection<String> shas, Function<String, BlobInfo> table) {
        Set<ResourceLocation> textures = new HashSet<>();
        Set<ResourceLocation> geo = new HashSet<>();
        for (String sha : shas) {
            BlobInfo info = table.apply(sha);
            if (info != null) {
                (info.type() == BlobType.PNG ? textures : geo).add(id(sha, info.type()));
            }
        }
        RuntimeTextures.expect(textures);
        RuntimeGeo.expect(geo);
    }

    /**
     * Verifies, decodes and publishes one complete file in the background. The listener
     * hears the outcome on the render thread, unless {@link #forget()} ran meanwhile.
     */
    public static void load(String sha, BlobInfo info, byte[] bytes, Listener listener) {
        int generation = GENERATION.get();
        try {
            executor().execute(() -> loadOne(sha, info, bytes, listener, generation));
        } catch (RejectedExecutionException e) {
            BlockPopsMod.LOGGER.warn("Could not queue World collection file {}: {}", sha, e.toString());
        }
    }

    private static void loadOne(String sha, BlobInfo info, byte[] bytes, Listener listener, int generation) {
        // The same checks the server made, so a damaged or swapped copy is never decoded
        try {
            if (!Sha256.of(bytes).equals(sha)) {
                throw new IllegalStateException("its bytes do not match its SHA-256");
            }
            BlobInfo actual = info.type().validate(bytes);
            // Bounds are left out: the collection is fitted with the server's, as the server fits it
            if (!actual.sameFile(info)) {
                throw new IllegalStateException("it does not match the server's description of it");
            }
        } catch (Exception e) {
            String reason = e.getMessage();
            publish(generation, () -> listener.unverified(sha, reason), () -> { });
            return;
        }
        ResourceLocation id = id(sha, info.type());
        try {
            switch (info.type()) {
                case PNG -> {
                    NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                    if (image.getWidth() != info.width() || image.getHeight() != info.height()) {
                        image.close();
                        throw new IllegalStateException("it decoded to " + image.getWidth() + "x" + image.getHeight());
                    }
                    publish(generation, () -> {
                        if (!listener.wants(sha) || bundled(id)) {
                            image.close();
                        } else {
                            RuntimeTextures.register(id, image);
                        }
                        listener.loaded(sha);
                    }, image::close);
                }
                case GEO -> {
                    // Already checked above; the JSON is not kept, as World models never have variants
                    JsonObject json = JsonFields.parse(bytes, info.type().maxBytes());
                    var model = RuntimeGeo.bakeModel(id, json);
                    publish(generation, () -> {
                        if (listener.wants(sha) && !bundled(GeoAssets.modelFile(id))) {
                            RuntimeGeo.putModel(id, model);
                        }
                        listener.loaded(sha);
                    }, () -> { });
                }
                case ANIMATION -> {
                    JsonObject json = JsonFields.parse(bytes, info.type().maxBytes());
                    var animations = RuntimeGeo.bakeAnimations(id, json);
                    publish(generation, () -> {
                        if (listener.wants(sha) && !bundled(GeoAssets.animationFile(id))) {
                            RuntimeGeo.putAnimations(id, animations);
                        }
                        listener.loaded(sha);
                    }, () -> { });
                }
            }
        } catch (Exception e) {
            // The id stays pending, so its fallback keeps drawing
            String reason = e.toString();
            publish(generation, () -> listener.failed(sha, reason), () -> { });
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

    /**
     * Releases the files no collection names any more, with their greyed copies. Render
     * thread only.
     */
    public static void release(Collection<String> shas) {
        if (shas.isEmpty()) {
            return;
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (String sha : shas) {
            for (BlobType type : BlobType.values()) {
                ids.add(id(sha, type));
            }
        }
        RuntimeGeo.clear(ids::contains);
        RuntimeTextures.clear(ids::contains);
        GreyedTextures.forget(ids::contains);
    }

    /**
     * Releases every World asset and drops whatever is still being loaded, as when
     * leaving a server. Never touches other owners' assets. Render thread only.
     */
    public static void forget() {
        GENERATION.incrementAndGet();
        RuntimeGeo.clear(WorldContentIds::owns);
        RuntimeTextures.clear(WorldContentIds::owns);
        GreyedTextures.forget(WorldContentIds::owns);
    }
}
