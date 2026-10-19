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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <p>Besides one file for one slot, it reads many at once for the bulk add: several files
 * chosen in the dialog or dropped together, and the files directly inside a dropped
 * folder. Each is told apart by its extension, gets the checks every file of its type
 * gets, and is refused on its own without stopping the others; whether it suits a skin,
 * a box or a logo is left to the screen, which shows why it does not. A folder is read one
 * level deep, and only regular files in it: links and anything below are skipped.
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

    /** The most files one bulk pick, drop or folder hands over, and the most files and folders one drop may hold. */
    public static final int MAX_FILES = 256;
    // Entries of a dropped folder looked at, so a huge folder cannot stall the reader
    private static final int MAX_FOLDER_ENTRIES = 4096;
    private static final String MANY_TITLE = "Choose figure files - PNG, geo.json, animation.json";
    private static final String MANY_FILTER_NAME = "PNG or JSON file";
    private static final String MANY_SDL_PATTERN = "png;json";
    private static final OpenOption[] NO_OPTIONS = {};
    private static final OpenOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};

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

    /**
     * What came of a pick.
     *
     * @param name the file's name for display when the result is about one file, otherwise
     *             empty
     */
    public record PickResult(Kind kind, @Nullable LocalFile file, String name, String message) {
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
            return new PickResult(Kind.ACCEPTED, file, file.name(), "");
        }

        static PickResult cancelled() {
            return new PickResult(Kind.CANCELLED, null, "", "");
        }

        static PickResult refused(String name, String message) {
            return new PickResult(Kind.REFUSED, null, name, message);
        }

        static PickResult unavailable() {
            return new PickResult(Kind.UNAVAILABLE, null, "", NO_DIALOG_HINT);
        }
    }

    /**
     * Who asked, for which slot (none for a bulk pick), and before which
     * {@link #forgetPending()}. A single slot's receiver gets a list of one.
     */
    private record Request(Screen owner, @Nullable Slot slot, Consumer<List<PickResult>> receiver, int generation,
                           boolean dialog) {
        boolean many() {
            return slot == null;
        }
    }

    /** Reads dropped, typed and SDL-chosen files, one at a time, in the order asked. */
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops File Reader");
        thread.setDaemon(true);
        return thread;
    });

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

    /** Whether dropped or typed files are being read, one or many. */
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
        return openDialog(new Request(owner, slot, single(receiver), generation, true));
    }

    /**
     * Opens the native dialog to choose many figure files at once, which are read one after
     * another and handed over together, each accepted or refused on its own.
     *
     * @return null once the dialog is opening, otherwise why it is not
     */
    @Nullable
    public static String openMany(Screen owner, Consumer<List<PickResult>> receiver) {
        return openDialog(new Request(owner, null, receiver, generation, true));
    }

    @Nullable
    private static String openDialog(Request request) {
        if (!nativeAvailable) {
            return NO_DIALOG_HINT;
        }
        if (dialogOpen) {
            return "A file dialog is already open";
        }
        dialogOpen = true;
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

    private static Consumer<List<PickResult>> single(Consumer<PickResult> receiver) {
        return results -> receiver.accept(results.get(0));
    }

    /**
     * Reads files and folders dropped onto a bulk slot: every file, and the regular files
     * directly inside every folder, up to {@link #MAX_FILES}. A dropped file of a type no
     * figure uses is refused; such a file inside a folder is skipped.
     *
     * @return null once the files are being read, otherwise why they are not
     */
    @Nullable
    public static String readMany(Screen owner, List<Path> paths, Consumer<List<PickResult>> receiver) {
        if (paths.isEmpty()) {
            return "Nothing was dropped";
        }
        if (reading) {
            return "Other files are still being read";
        }
        reading = true;
        Request request = new Request(owner, null, receiver, generation, false);
        List<Path> dropped = List.copyOf(paths);
        READER.execute(() -> post(request, readAll(dropped)));
        return null;
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
            post(dialog.request(), List.of(PickResult.unavailable()));
        } else if (answer.paths().isEmpty()) {
            post(dialog.request(), List.of(PickResult.cancelled()));
        } else {
            Request request = dialog.request();
            List<String> chosen = answer.paths();
            READER.execute(() -> post(request, readChosen(chosen, request)));
        }
        *///? }
    }

    @Nullable
    private static String startRead(Screen owner, Slot slot, Path path, Consumer<PickResult> receiver) {
        if (reading) {
            return "Another file is still being read";
        }
        reading = true;
        Request request = new Request(owner, slot, single(receiver), generation, false);
        READER.execute(() -> post(request, List.of(read(path, slot))));
        return null;
    }

    //? if >=26.3 {
    /*// The SDL callback's answer: an error, a cancel (no path) or the chosen paths
    private record SdlAnswer(List<String> paths, @Nullable String error) {
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
            Slot slot = request.slot();
            name = org.lwjgl.system.MemoryUtil.memUTF8(slot != null ? slot.filterName() : MANY_FILTER_NAME);
            pattern = org.lwjgl.system.MemoryUtil.memUTF8(slot != null ? slot.sdlPattern() : MANY_SDL_PATTERN);
            filters = org.lwjgl.sdl.SDL_DialogFileFilter.calloc(1);
            filters.get(0).set(name, pattern);
            // Only record the answer here: SDL may call this on another thread, or before
            // SDL_ShowOpenFileDialog returns, so nothing may be freed or delivered yet
            callback = org.lwjgl.sdl.SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
                SdlAnswer answer;
                try {
                    if (filelist == 0L) {
                        answer = new SdlAnswer(List.of(), String.valueOf(org.lwjgl.sdl.SDLError.SDL_GetError()));
                    } else {
                        // A list of paths ended by a null pointer; empty when cancelled
                        List<String> paths = new ArrayList<>();
                        for (int i = 0; i <= MAX_FILES; i++) {
                            long entry = org.lwjgl.system.MemoryUtil.memGetAddress(
                                    filelist + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
                            if (entry == 0L) {
                                break;
                            }
                            paths.add(org.lwjgl.system.MemoryUtil.memUTF8(entry));
                        }
                        answer = new SdlAnswer(paths, null);
                    }
                } catch (Throwable t) {
                    answer = new SdlAnswer(List.of(), String.valueOf(t));
                }
                SDL_ANSWER.set(answer);
            });
            sdlDialog = new SdlDialog(request, callback, filters, name, pattern);
            Minecraft minecraft = Minecraft.getInstance();
            org.lwjgl.sdl.SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, minecraft.getWindow().handle(), filters,
                    (CharSequence) null, request.many());
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
            post(request, List.of(PickResult.unavailable()));
        }
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
            List<PickResult> results;
            Slot slot = request.slot();
            try {
                long started = System.nanoTime();
                String chosen;
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.PointerBuffer patterns;
                    if (slot != null) {
                        patterns = stack.mallocPointer(1);
                        patterns.put(0, stack.UTF8(slot.tinyfdPattern()));
                    } else {
                        patterns = stack.mallocPointer(2);
                        patterns.put(0, stack.UTF8("*.png"));
                        patterns.put(1, stack.UTF8("*.json"));
                    }
                    chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                            slot != null ? slot.title() : MANY_TITLE, null, patterns,
                            slot != null ? slot.filterName() : MANY_FILTER_NAME, slot == null);
                }
                if (chosen != null) {
                    // Several chosen files come back as one string, separated by '|'
                    results = readChosen(slot != null ? List.of(chosen) : List.of(chosen.split("\\|")), request);
                } else if (LINUX && System.nanoTime() - started < NO_BACKEND_NANOS) {
                    results = List.of(PickResult.unavailable());
                } else {
                    results = List.of(PickResult.cancelled());
                }
            } catch (Throwable t) {
                // Most often the native library failing to load
                BlockPopsMod.LOGGER.warn("The file dialog is not available: {}", t.toString());
                results = List.of(PickResult.unavailable());
            }
            post(request, results);
        });
    }
    //? }

    /** Reads the paths the native dialog returned, one for a slot, any number in bulk. Off the render thread. */
    private static List<PickResult> readChosen(List<String> chosen, Request request) {
        List<Path> paths = new ArrayList<>();
        for (String text : chosen) {
            if (text.isEmpty()) {
                continue;
            }
            try {
                paths.add(Path.of(text));
            } catch (InvalidPathException e) {
                if (!request.many()) {
                    return List.of(PickResult.refused("", "The chosen file has a path this game cannot open"));
                }
            }
        }
        if (paths.isEmpty()) {
            return List.of(PickResult.refused("", "The chosen file has a path this game cannot open"));
        }
        Slot slot = request.slot();
        return slot != null ? List.of(read(paths.get(0), slot)) : readAll(paths);
    }

    /** Reads and checks a file for a slot. Off the render thread. */
    static PickResult read(Path path, Slot slot) {
        return read(path, slot.type(), slot.role, NO_OPTIONS);
    }

    /**
     * Reads every file and the files directly inside every folder, in the order given and a
     * folder's in name order, up to {@link #MAX_FILES}. Off the render thread.
     */
    static List<PickResult> readAll(List<Path> paths) {
        List<PickResult> results = new ArrayList<>();
        int files = 0;
        boolean full = false;
        for (int i = 0; i < paths.size() && !full; i++) {
            Path path = paths.get(i);
            // Folders count too, so a drop of thousands of empty folders is not listed one by one
            if (i == MAX_FILES) {
                full = true;
                break;
            }
            String name = displayName(path);
            if (Files.isDirectory(path)) {
                for (Path entry : folderFiles(path, results)) {
                    if (files == MAX_FILES) {
                        full = true;
                        break;
                    }
                    files++;
                    BlobType type = typeOf(entry);
                    results.add(read(entry, type, null, NO_LINKS));
                }
                continue;
            }
            if (files == MAX_FILES) {
                full = true;
                break;
            }
            files++;
            BlobType type = typeOf(path);
            results.add(type == null
                    ? PickResult.refused(name, name + " is not a PNG, .geo.json or .animation.json file")
                    : read(path, type, null, NO_OPTIONS));
        }
        if (full) {
            results.add(PickResult.refused("", "Only the first " + MAX_FILES + " files were read"));
        }
        return results;
    }

    /**
     * The regular files directly inside a folder whose type a figure uses, in name order.
     * Links, folders and other files are skipped. A folder that cannot be listed, holds no
     * such file, or is too large to look through whole adds a refusal saying so.
     */
    private static List<Path> folderFiles(Path folder, List<PickResult> results) {
        List<Path> found = new ArrayList<>();
        String name = displayName(folder);
        int seen = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (++seen > MAX_FOLDER_ENTRIES) {
                    results.add(PickResult.refused(name, "Only the first " + MAX_FOLDER_ENTRIES
                            + " entries of the folder " + name + " were looked at"));
                    break;
                }
                if (typeOf(entry) == null) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    found.add(entry);
                }
            }
        } catch (IOException | SecurityException | java.nio.file.DirectoryIteratorException e) {
            BlockPopsMod.logDebug("Could not list a dropped folder: {}", e.toString());
            results.add(PickResult.refused(name, "Could not read the folder " + name));
            return List.of();
        }
        if (found.isEmpty()) {
            // Otherwise a folder of the wrong files would come back as nothing at all
            results.add(PickResult.refused(name, "The folder " + name
                    + " has no PNG, .geo.json or .animation.json file in it"));
        }
        found.sort(Comparator.comparing(entry -> displayName(entry).toLowerCase(Locale.ROOT)));
        return found;
    }

    /** The type a figure file has by its name's ending, or null for any other file. */
    @Nullable
    public static BlobType typeOf(Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return BlobType.PNG;
        }
        if (name.endsWith(".geo.json")) {
            return BlobType.GEO;
        }
        if (name.endsWith(".animation.json")) {
            return BlobType.ANIMATION;
        }
        return null;
    }

    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        return WorldContentException.excerpt(fileName == null ? path.toString() : fileName.toString());
    }

    /**
     * Reads and checks a file of a type, for a PNG role when one is given. The size is
     * checked before anything is read. Off the render thread.
     */
    private static PickResult read(Path path, BlobType type, @Nullable PngValidator.Role role, OpenOption[] options) {
        String name = displayName(path);
        int cap = type.maxBytes();
        try {
            if (!Files.isRegularFile(path)) {
                return PickResult.refused(name, name + " is not a file");
            }
            long size = Files.size(path);
            if (size == 0) {
                return PickResult.refused(name, name + " is empty");
            }
            if (size > cap) {
                return PickResult.refused(name, name + " is larger than " + cap / 1024 + " KiB");
            }
            byte[] raw;
            try (InputStream in = Files.newInputStream(path, options)) {
                raw = in.readNBytes(cap + 1);
            }
            return check(name, raw, type, role);
        } catch (IOException | SecurityException e) {
            BlockPopsMod.logDebug("Could not read a World collection file: {}", e.toString());
            return PickResult.refused(name, "Could not read " + name);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Checking a World collection file failed", e);
            return PickResult.refused(name, name + " could not be checked");
        }
    }

    /**
     * Gives bytes that did not come from a file the operator chose, such as the player's own
     * skin, the checks a file read for the slot gets, so they become a {@link LocalFile} just
     * like a picked one. Any thread.
     *
     * @param name the name to show for them, bounded here
     */
    public static PickResult check(String name, byte[] raw, Slot slot) {
        String shown = WorldContentException.excerpt(name);
        try {
            return check(shown, raw, slot.type(), slot.role);
        } catch (RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Checking a World collection file failed", e);
            return PickResult.refused(shown, shown + " could not be checked");
        }
    }

    // The checks of a file's bytes once read: its size, then its type's validator
    private static PickResult check(String name, byte[] raw, BlobType type, @Nullable PngValidator.Role role) {
        int cap = type.maxBytes();
        if (raw.length == 0) {
            return PickResult.refused(name, name + " is empty");
        }
        if (raw.length > cap) {
            return PickResult.refused(name, name + " is larger than " + cap / 1024 + " KiB");
        }
        try {
            byte[] bytes;
            BlobInfo info;
            if (type == BlobType.PNG) {
                // Metadata and every other ancillary chunk stay on this computer
                bytes = PngValidator.canonicalize(raw);
                info = role != null ? PngValidator.validateCanonical(bytes, role) : PngValidator.validateCanonical(bytes);
            } else {
                bytes = raw;
                info = type.validate(bytes);
            }
            return PickResult.accepted(new LocalFile(name, type, bytes, Sha256.of(bytes), info));
        } catch (WorldContentException e) {
            return PickResult.refused(name, e.getMessage());
        }
    }

    /** Hands results to the render thread. From any thread. */
    private static void post(Request request, List<PickResult> results) {
        Minecraft.getInstance().execute(() -> deliver(request, results));
    }

    private static void deliver(Request request, List<PickResult> results) {
        if (request.dialog()) {
            dialogOpen = false;
            if (results.size() == 1 && results.get(0).kind() == PickResult.Kind.UNAVAILABLE) {
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
        request.receiver().accept(results);
    }
}
