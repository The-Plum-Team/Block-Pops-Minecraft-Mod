package com.theplumteam.client.worldcontent;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.runtime.RuntimeGeo;
import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.worldcontent.JsonFields;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the creator's local files into the runtime registries before they are uploaded, so the
 * previews can draw a texture, model or animation the server does not have yet.
 *
 * <p>A file is loaded under the same content-addressed id a synced copy gets
 * ({@link com.theplumteam.worldcontent.WorldContentIds}), so a preview stays right after the file
 * is uploaded, and the sync that brings it back replaces it in place. A file the World
 * collections already loaded is not loaded twice. Decoding and baking run on a background
 * thread of their own; registering runs on the render thread, and results that land after
 * {@link #release()} or {@link #forget()} are dropped. The files were checked when the picker
 * read them, so they are only decoded here.
 *
 * <p>A file the creator uses stays loaded while it does, even when a sync stops naming it: the
 * World collections hand it over ({@link #adopt}) instead of releasing it. {@link #release()}
 * frees every such file no synced collection names when the creator closes.
 *
 * Client thread only, except the decoding.
 */
public final class ClientPreviewAssets {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    // Every sha the creator showed since it opened, whoever loaded it
    private static final Set<String> USED = new LinkedHashSet<>();
    // The shas this class put in the runtime registries, or took over from the World collections
    private static final Set<String> REGISTERED = new HashSet<>();
    // The shas being decoded
    private static final Set<String> QUEUED = new HashSet<>();
    // The shas that could not be decoded, which are not tried again until the creator closes
    private static final Set<String> FAILED = new HashSet<>();
    private static ExecutorService executor;

    private ClientPreviewAssets() {
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BlockPops Creator Previews");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /** Loads a local file for the previews, unless it is loaded already or on its way. */
    public static void use(FilePicker.LocalFile file) {
        String sha = file.sha();
        USED.add(sha);
        if (REGISTERED.contains(sha) || QUEUED.contains(sha) || FAILED.contains(sha)
                || ClientWorldCollections.isLoaded(sha)) {
            return;
        }
        QUEUED.add(sha);
        int generation = GENERATION.get();
        try {
            executor().execute(() -> decode(file, generation));
        } catch (RejectedExecutionException e) {
            QUEUED.remove(sha);
            BlockPopsMod.LOGGER.warn("Could not queue the creator's file {}: {}", sha, e.toString());
        }
    }

    /** Whether the runtime registries hold the file because the creator loaded or kept it. */
    public static boolean holds(String sha) {
        return REGISTERED.contains(sha);
    }

    private static void decode(FilePicker.LocalFile file, int generation) {
        String sha = file.sha();
        ResourceLocation id = ClientWorldAssets.id(sha, file.type());
        try {
            switch (file.type()) {
                case PNG -> {
                    NativeImage image = NativeImage.read(new ByteArrayInputStream(file.bytes()));
                    publish(generation, file, () -> RuntimeTextures.register(id, image), image::close);
                }
                case GEO -> {
                    var model = RuntimeGeo.bakeModel(id, JsonFields.parse(file.bytes(), file.type().maxBytes()));
                    publish(generation, file, () -> RuntimeGeo.putModel(id, model), () -> { });
                }
                case ANIMATION -> {
                    var animations = RuntimeGeo.bakeAnimations(id,
                            JsonFields.parse(file.bytes(), file.type().maxBytes()));
                    publish(generation, file, () -> RuntimeGeo.putAnimations(id, animations), () -> { });
                }
            }
        } catch (Exception e) {
            // The id stays unregistered, so the previews keep drawing its fallback
            BlockPopsMod.LOGGER.warn("Could not load the creator's file {}: {}", file.name(), e.toString());
            Minecraft.getInstance().execute(() -> {
                if (generation == GENERATION.get()) {
                    QUEUED.remove(sha);
                    FAILED.add(sha);
                }
            });
        }
    }

    private static void publish(int generation, FilePicker.LocalFile file, Runnable register, Runnable dropped) {
        Minecraft.getInstance().execute(() -> {
            String sha = file.sha();
            if (generation != GENERATION.get()) {
                dropped.run();
                return;
            }
            QUEUED.remove(sha);
            // A sync that loaded the same file meanwhile already shows it, and a bundled
            // resource with the id is never replaced, as the World collections refuse it too
            if (!USED.contains(sha) || ClientWorldCollections.isLoaded(sha)
                    || ClientWorldAssets.isBundled(sha, file.type())) {
                dropped.run();
                return;
            }
            register.run();
            REGISTERED.add(sha);
        });
    }

    /**
     * Takes over the files the World collections are about to release that the creator still
     * shows, and returns the rest for them to release.
     */
    static Set<String> adopt(Set<String> released) {
        Set<String> rest = new HashSet<>();
        for (String sha : released) {
            if (USED.contains(sha)) {
                REGISTERED.add(sha);
            } else {
                rest.add(sha);
            }
        }
        return rest;
    }

    /**
     * Frees every file the creator used that no synced collection names, and drops whatever is
     * still decoding, as when the creator closes. Render thread only.
     */
    public static void release() {
        GENERATION.incrementAndGet();
        Set<String> referenced = new HashSet<>();
        for (WorldCollectionRecord record : ClientWorldCollections.records()) {
            referenced.addAll(record.referencedShas());
        }
        Set<String> free = new HashSet<>(USED);
        free.addAll(REGISTERED);
        free.removeAll(referenced);
        USED.clear();
        REGISTERED.clear();
        QUEUED.clear();
        FAILED.clear();
        ClientWorldAssets.release(free);
    }

    /**
     * Forgets every file and drops whatever is still decoding, once the World collections have
     * released every World asset, as when leaving a server.
     */
    static void forget() {
        GENERATION.incrementAndGet();
        USED.clear();
        REGISTERED.clear();
        QUEUED.clear();
        FAILED.clear();
    }
}
