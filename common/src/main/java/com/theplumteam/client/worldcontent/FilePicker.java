package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.PngValidator;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldContentException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Lets an operator choose a local file for a slot of the World collection editor, then
 * reads, canonicalizes and validates it off the render thread.
 *
 * <p>The native dialog is tinyfd until 26.2 and SDL3 from 26.3, which ships no tinyfd.
 * tinyfd blocks, so it runs on its own daemon thread and is only ever given constant
 * ASCII titles and patterns and never a default path, because on Linux and macOS it
 * builds shell commands from them. SDL answers through a callback on a thread of its
 * choosing; the callback only records the answer, and the render thread frees what the
 * dialog held on its next tick, never inside the callback. Any failure to load or show
 * either dialog marks the native picker unavailable for the session, and dropping the
 * file onto the window or typing its path, which are always offered, take over.
 *
 * <p>A result reaches its receiver on the render thread, and only while the screen that
 * asked is still the current one and no {@link #forgetPending()} came in between. The
 * local path never leaves this class: a result carries the file name and the checked
 * bytes only.
 *
 * Render thread only, except where noted.
 */
public final class FilePicker {
    /** The longest typed path, which is also the path box's maximum length. */
    public static final int MAX_TYPED_PATH = 1024;
    /** What SDL3 accepts as a filter pattern: bare extensions, separated by semicolons. */
    public static final Pattern SDL_PATTERN_GRAMMAR = Pattern.compile("[A-Za-z0-9_.-]+(;[A-Za-z0-9_.-]+)*");
    /** Shown wherever a slot can be filled, and when no native dialog is available. */
    public static final String FALLBACK_HINT = "You can also drag the file onto this window or type its path";
    public static final String NO_DIALOG_HINT =
            "No file dialog is available here. Drag the file onto this window or type its path";
    public static final String HIDDEN_DIALOG_HINT =
            "If no dialog appears, leave fullscreen, or drag the file onto this window";

    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    /** The kinds of file the editor asks for, each with its type, PNG role and dialog filter. */
    public enum Slot {
        SKIN(BlobType.PNG, PngValidator.Role.SKIN, "Choose a skin - 64x64 PNG"),
        BOX(BlobType.PNG, PngValidator.Role.BOX, "Choose a box texture - 64x64 PNG"),
        LOGO(BlobType.PNG, PngValidator.Role.LOGO, "Choose a logo - PNG"),
        MODEL_TEXTURE(BlobType.PNG, PngValidator.Role.MODEL_TEXTURE, "Choose a model texture - PNG"),
        MODEL(BlobType.GEO, null, "Choose a model - geo.json"),
        ANIMATION(BlobType.ANIMATION, null, "Choose an animation - animation.json");

        private final BlobType type;
        @Nullable
        private final PngValidator.Role role;
        private final String title;

        Slot(BlobType type, @Nullable PngValidator.Role role, String title) {
            this.type = type;
            this.role = role;
            this.title = title;
        }

        public BlobType type() {
            return type;
        }

        /** The dialog's title: constant ASCII, because tinyfd puts it in a shell command. */
        public String title() {
            return title;
        }

        /** The name of the dialog's single filter. */
        public String filterName() {
            return type == BlobType.PNG ? "PNG image" : "JSON file";
        }

        /** The tinyfd pattern, a glob. */
        public String tinyfdPattern() {
            return "*." + sdlPattern();
        }

        /**
         * The SDL3 pattern, a bare extension. SDL cannot filter on {@code .geo.json}, so the
         * validators decide after the pick.
         */
        public String sdlPattern() {
            return type == BlobType.PNG ? "png" : "json";
        }
    }

    /**
     * A local file that passed the checks for its slot. A PNG is already in the canonical
     * form the server stores, and the sha is that of {@code bytes}.
     *
     * @param name the file's name for display, bounded and without control characters
     */
    public record LocalFile(String name, BlobType type, byte[] bytes, String sha, BlobInfo info) {
    }

    /** What came of a pick. */
    public record PickResult(Kind kind, @Nullable LocalFile file, String message) {
        public enum Kind {
            /** {@link #file()} holds the checked file. */
            ACCEPTED,
            /** The operator closed the dialog without choosing. */
            CANCELLED,
            /** The file was refused; {@link #message()} says why. */
            REFUSED,
            /** The native dialog cannot be used; {@link #message()} offers the fallbacks. */
            UNAVAILABLE
        }

        static PickResult accepted(LocalFile file) {
            return new PickResult(Kind.ACCEPTED, file, "");
        }

        static PickResult cancelled() {
            return new PickResult(Kind.CANCELLED, null, "");
        }

        static PickResult refused(String message) {
            return new PickResult(Kind.REFUSED, null, message);
        }

        static PickResult unavailable() {
            return new PickResult(Kind.UNAVAILABLE, null, NO_DIALOG_HINT);
        }
    }

    /** Who asked, for which slot, and before which {@link #forgetPending()}. */
    private record Request(Screen owner, Slot slot, Consumer<PickResult> receiver, int generation, boolean dialog) {
    }

    private static boolean nativeAvailable = true;
    private static boolean dialogOpen;
    private static boolean reading;
    private static int generation;

    private FilePicker() {
    }

    /** Whether a native dialog is open, during which the slot buttons stay disabled. */
    public static boolean isDialogOpen() {
        return dialogOpen;
    }

    /** Whether a dropped or typed file is being read. */
    public static boolean isReading() {
        return reading;
    }

    /** Whether the native dialog can still be offered this session. */
    public static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    /**
     * Opens the native dialog for a slot.
     *
     * @return null once the dialog is opening, otherwise why it is not
     */
    @Nullable
    public static String open(Screen owner, Slot slot, Consumer<PickResult> receiver) {
        if (!nativeAvailable) {
            return NO_DIALOG_HINT;
        }
        if (dialogOpen) {
            return "A file dialog is already open";
        }
        dialogOpen = true;
        Request request = new Request(owner, slot, receiver, generation, true);
        //? if >=26.3 {
        /*openSdl(request);
        *///? } else {
        openTinyfd(request);
        //? }
        return null;
    }

    /**
     * Reads the path an operator typed into a slot.
     *
     * @return null once the file is being read, otherwise why it is not
     */
    @Nullable
    public static String readTyped(Screen owner, Slot slot, String typed, Consumer<PickResult> receiver) {
        String text = typed.strip();
        // A path copied from a file manager often arrives quoted
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return "Type the path to a file";
        }
        if (text.length() > MAX_TYPED_PATH) {
            return "The path is longer than " + MAX_TYPED_PATH + " characters";
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            return "That is not a valid path";
        }
        if (!path.isAbsolute()) {
            return "Type the full path to the file";
        }
        return startRead(owner, slot, path, receiver);
    }

    /**
     * Reads a file dropped onto an editor screen into the armed slot.
     *
     * @return null once the file is being read, otherwise why it is not
     */
    @Nullable
    public static String readDropped(Screen owner, Slot slot, List<Path> paths, Consumer<PickResult> receiver) {
        if (paths.size() != 1) {
            return "Drop one file at a time";
        }
        return startRead(owner, slot, paths.get(0), receiver);
    }

    /**
     * Drops every result still on its way, which then reaches no receiver. A dialog that is
     * open stays open until the operator closes it. Runs when the client leaves or joins a
     * server.
     */
    public static void forgetPending() {
        generation++;
    }

    /** Takes the native dialog's answer once it has one. Every client tick. */
    public static void tick() {
        //? if >=26.3 {
        /*SdlAnswer answer = SDL_ANSWER.getAndSet(null);
        if (answer == null || sdlDialog == null) {
            return;
        }
        SdlDialog dialog = sdlDialog;
        sdlDialog = null;
        // SDL is done with the callback and the filters once it has answered
        dialog.free();
        if (answer.error() != null) {
            BlockPopsMod.LOGGER.warn("The file dialog failed: {}", WorldContentException.excerpt(answer.error()));
            post(dialog.request(), PickResult.unavailable());
        } else if (answer.path() == null) {
            post(dialog.request(), PickResult.cancelled());
        } else {
            readOnWorker(dialog.request(), answer.path());
        }
        *///? }
    }

    @Nullable
    private static String startRead(Screen owner, Slot slot, Path path, Consumer<PickResult> receiver) {
        if (reading) {
            return "Another file is still being read";
        }
        reading = true;
        Request request = new Request(owner, slot, receiver, generation, false);
        Thread worker = new Thread(() -> post(request, read(path, slot)), "BlockPops File Reader");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    //? if >=26.3 {
    /*// The SDL callback's answer: an error, a cancel (no path) or the chosen path
    private record SdlAnswer(@Nullable String path, @Nullable String error) {
    }

    // What one open SDL dialog holds natively until it has answered
    private record SdlDialog(Request request, org.lwjgl.sdl.SDL_DialogFileCallback callback,
                             org.lwjgl.sdl.SDL_DialogFileFilter.Buffer filters,
                             java.nio.ByteBuffer name, java.nio.ByteBuffer pattern) {
        void free() {
            callback.free();
            filters.free();
            org.lwjgl.system.MemoryUtil.memFree(name);
            org.lwjgl.system.MemoryUtil.memFree(pattern);
        }
    }

    // Written by the SDL callback on whichever thread SDL calls it from
    private static final java.util.concurrent.atomic.AtomicReference<SdlAnswer> SDL_ANSWER =
            new java.util.concurrent.atomic.AtomicReference<>();
    @Nullable
    private static SdlDialog sdlDialog;

    private static void openSdl(Request request) {
        java.nio.ByteBuffer name = null;
        java.nio.ByteBuffer pattern = null;
        org.lwjgl.sdl.SDL_DialogFileFilter.Buffer filters = null;
        org.lwjgl.sdl.SDL_DialogFileCallback callback = null;
        try {
            name = org.lwjgl.system.MemoryUtil.memUTF8(request.slot().filterName());
            pattern = org.lwjgl.system.MemoryUtil.memUTF8(request.slot().sdlPattern());
            filters = org.lwjgl.sdl.SDL_DialogFileFilter.calloc(1);
            filters.get(0).set(name, pattern);
            // Only record the answer here: SDL may call this on another thread, or before
            // SDL_ShowOpenFileDialog returns, so nothing may be freed or delivered yet
            callback = org.lwjgl.sdl.SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
                SdlAnswer answer;
                try {
                    if (filelist == 0L) {
                        answer = new SdlAnswer(null, String.valueOf(org.lwjgl.sdl.SDLError.SDL_GetError()));
                    } else {
                        long first = org.lwjgl.system.MemoryUtil.memGetAddress(filelist);
                        answer = new SdlAnswer(first == 0L ? null : org.lwjgl.system.MemoryUtil.memUTF8(first), null);
                    }
                } catch (Throwable t) {
                    answer = new SdlAnswer(null, String.valueOf(t));
                }
                SDL_ANSWER.set(answer);
            });
            sdlDialog = new SdlDialog(request, callback, filters, name, pattern);
            Minecraft minecraft = Minecraft.getInstance();
            org.lwjgl.sdl.SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, minecraft.getWindow().handle(), filters,
                    (CharSequence) null, false);
        } catch (Throwable t) {
            // Thrown while loading or linking, before SDL was given anything to call
            BlockPopsMod.LOGGER.warn("The file dialog is not available: {}", t.toString());
            sdlDialog = null;
            SDL_ANSWER.set(null);
            if (callback != null) {
                callback.free();
            }
            if (filters != null) {
                filters.free();
            }
            org.lwjgl.system.MemoryUtil.memFree(name);
            org.lwjgl.system.MemoryUtil.memFree(pattern);
            post(request, PickResult.unavailable());
        }
    }

    private static void readOnWorker(Request request, String chosen) {
        Thread worker = new Thread(() -> post(request, readChosen(chosen, request.slot())), "BlockPops File Reader");
        worker.setDaemon(true);
        worker.start();
    }
    *///? } else {
    /** Runs tinyfd, which blocks until the operator answers, one dialog at a time. */
    private static final java.util.concurrent.ExecutorService TINYFD =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BlockPops File Picker");
                thread.setDaemon(true);
                return thread;
            });
    /** A Linux dialog that answers faster than this was never shown: no backend is installed. */
    private static final long NO_BACKEND_NANOS = 250_000_000L;

    private static void openTinyfd(Request request) {
        TINYFD.execute(() -> {
            PickResult result;
            try {
                long started = System.nanoTime();
                String chosen;
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.PointerBuffer patterns = stack.mallocPointer(1);
                    patterns.put(0, stack.UTF8(request.slot().tinyfdPattern()));
                    chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                            request.slot().title(), null, patterns, request.slot().filterName(), false);
                }
                if (chosen != null) {
                    result = readChosen(chosen, request.slot());
                } else if (LINUX && System.nanoTime() - started < NO_BACKEND_NANOS) {
                    result = PickResult.unavailable();
                } else {
                    result = PickResult.cancelled();
                }
            } catch (Throwable t) {
                // Most often the native library failing to load
                BlockPopsMod.LOGGER.warn("The file dialog is not available: {}", t.toString());
                result = PickResult.unavailable();
            }
            post(request, result);
        });
    }
    //? }

    /** Reads a path the native dialog returned. Off the render thread. */
    private static PickResult readChosen(String chosen, Slot slot) {
        try {
            return read(Path.of(chosen), slot);
        } catch (InvalidPathException e) {
            return PickResult.refused("The chosen file has a path this game cannot open");
        }
    }

    /** Reads and checks a file for a slot. Off the render thread. */
    static PickResult read(Path path, Slot slot) {
        Path fileName = path.getFileName();
        String name = WorldContentException.excerpt(fileName == null ? path.toString() : fileName.toString());
        int cap = slot.type().maxBytes();
        try {
            if (!Files.isRegularFile(path)) {
                return PickResult.refused(name + " is not a file");
            }
            long size = Files.size(path);
            if (size == 0) {
                return PickResult.refused(name + " is empty");
            }
            if (size > cap) {
                return PickResult.refused(name + " is larger than " + cap / 1024 + " KiB");
            }
            byte[] raw;
            try (InputStream in = Files.newInputStream(path)) {
                raw = in.readNBytes(cap + 1);
            }
            if (raw.length > cap) {
                return PickResult.refused(name + " is larger than " + cap / 1024 + " KiB");
            }
            byte[] bytes;
            BlobInfo info;
            if (slot.type() == BlobType.PNG) {
                // Metadata and every other ancillary chunk stay on this computer
                bytes = PngValidator.canonicalize(raw);
                info = PngValidator.validateCanonical(bytes, slot.role);
            } else {
                bytes = raw;
                info = slot.type().validate(bytes);
            }
            return PickResult.accepted(new LocalFile(name, slot.type(), bytes, Sha256.of(bytes), info));
        } catch (WorldContentException e) {
            return PickResult.refused(e.getMessage());
        } catch (IOException | SecurityException e) {
            BlockPopsMod.logDebug("Could not read a World collection file: {}", e.toString());
            return PickResult.refused("Could not read " + name);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Checking a World collection file failed", e);
            return PickResult.refused(name + " could not be checked");
        }
    }

    /** Hands a result to the render thread. From any thread. */
    private static void post(Request request, PickResult result) {
        Minecraft.getInstance().execute(() -> deliver(request, result));
    }

    private static void deliver(Request request, PickResult result) {
        if (request.dialog()) {
            dialogOpen = false;
            if (result.kind() == PickResult.Kind.UNAVAILABLE) {
                nativeAvailable = false;
            }
        } else {
            reading = false;
        }
        if (request.generation() != generation) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != request.owner()) {
            return;
        }
        request.receiver().accept(result);
    }
}
