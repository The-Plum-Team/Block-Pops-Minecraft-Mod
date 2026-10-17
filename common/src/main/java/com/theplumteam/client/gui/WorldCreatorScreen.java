package com.theplumteam.client.gui;

import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the three World collection screens share: the look of the mod's other screens
 * (the star background and an opaque black panel with a thin outline), a status line, the server's answers to what they send, and the ways a file reaches a
 * slot (the native dialog, a typed path, or a file dropped onto the window).
 *
 * <p>Each screen follows the World collections the server syncs: a new sync rebuilds it,
 * and it goes back to the screen it came from once the player is no longer an operator
 * or the server no longer sends World collections. The server checks every request
 * again; these screens only ask.
 */
abstract class WorldCreatorScreen extends Screen {
    static final String KEY = "blockpops.world_creator.";
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFAAAAAA;
    static final int ERROR = 0xFFFF5555;
    static final int SUCCESS = 0xFF55FF55;
    static final int PENDING = 0xFFFFFF80;
    static final int WARNING = 0xFFFFAA00;
    // The panel and dimming of SettingsScreen, which opens these screens
    private static final int PANEL = 0xFF000000;
    private static final int PANEL_OUTLINE = 0x60FFFFFF;
    private static final int DIM = 0x70000000;
    static final int LINE_HEIGHT = 11;
    private static final String ELLIPSIS = "...";

    protected final Screen parent;
    private final int maxPanelWidth;
    private final int maxPanelHeight;
    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;

    private String status = "";
    private int statusColor = MUTED;
    // Whether this screen's own submission is still on its way, so the status line shows its progress
    private boolean waiting;
    private int shownRevision;

    // The slot a typed path or a dropped file goes to: the last one whose button was clicked
    @Nullable
    private Supplier<FilePicker.Slot> armed;
    private String typedPath = "";
    @Nullable
    private Button usePathButton;
    private final List<Button> slotButtons = new ArrayList<>();
    // Text fields by name, so a rebuild can give the focus back to the same field
    private final Map<String, EditBox> textFields = new LinkedHashMap<>();

    protected WorldCreatorScreen(Component title, Screen parent, int maxPanelWidth, int maxPanelHeight) {
        super(title);
        this.parent = parent;
        this.maxPanelWidth = maxPanelWidth;
        this.maxPanelHeight = maxPanelHeight;
        this.shownRevision = ClientWorldCollections.revision();
    }

    /** A translated line of these screens. */
    static String tr(String key, Object... args) {
        return Component.translatable(KEY + key, args).getString();
    }

    static Component label(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(maxPanelWidth, this.width - 24);
        this.panelHeight = Math.min(maxPanelHeight, this.height - 24);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.slotButtons.clear();
        this.textFields.clear();
        this.usePathButton = null;
        build();
        updateButtons();
    }

    /** Adds this screen's widgets; runs on every rebuild, so it reads the screen's own state. */
    protected abstract void build();

    /** Draws this screen's labels over its widgets. */
    protected abstract void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks);

    /** Sets which buttons can be used now. Runs every tick and after every change. */
    protected abstract void refreshButtons();

    /** Called before a rebuild when the server synced a new state of the World collections. */
    protected void onSynced() {
    }

    /** A file passed the checks for the slot it was asked for. */
    protected abstract void accepted(FilePicker.Slot slot, FilePicker.LocalFile file);

    /** A file was refused for the slot it was asked for. */
    protected void refused(FilePicker.Slot slot, String reason) {
    }

    /** The line at the top of the panel. */
    protected String heading() {
        return this.title.getString();
    }

    /** The line shown while nothing else has been said. */
    protected String idleStatus() {
        return "";
    }

    /** The centre of the status line; by default the panel's, above the Back and Save row. */
    protected int statusCenterX() {
        return this.panelX + this.panelWidth / 2;
    }

    protected int statusY() {
        return this.panelY + this.panelHeight - 42;
    }

    protected int statusWidth() {
        return this.panelWidth - 16;
    }

    /** How many lines the status line may wrap to; by default one, above Back and Save. */
    protected int statusLines() {
        return 1;
    }

    /** How many lines fit from a height down to a bottom edge, at least one. */
    static int linesBetween(int y, int bottom) {
        return Math.max(1, (bottom - y + 2) / LINE_HEIGHT);
    }

    protected final void updateButtons() {
        boolean free = !FilePicker.isDialogOpen();
        for (Button button : slotButtons) {
            button.active = free && button.visible;
        }
        if (usePathButton != null) {
            usePathButton.active = !FilePicker.isReading() && !typedPath.isBlank();
        }
        refreshButtons();
    }

    @Override
    public void tick() {
        super.tick();
        if (!canUse()) {
            // Not an operator any more, or the server stopped sending World collections
            this.minecraft.setScreen(this.parent);
            return;
        }
        if (ClientWorldCollections.revision() != this.shownRevision) {
            this.shownRevision = ClientWorldCollections.revision();
            onSynced();
            if (this.minecraft.screen != this) {
                return;
            }
            rebuild();
        }
        updateButtons();
    }

    /** Whether the player may use these screens: an operator on a server that sends World collections. */
    protected boolean canUse() {
        return this.minecraft != null && this.minecraft.player != null
                && ServerLevels.hasCommandLevel(this.minecraft.player, 2)
                && ClientWorldCollections.isSynced();
    }

    /** Builds the widgets again, keeping the focus in the text field that had it. */
    protected final void rebuild() {
        String focused = null;
        for (Map.Entry<String, EditBox> field : textFields.entrySet()) {
            if (getFocused() == field.getValue()) {
                focused = field.getKey();
            }
        }
        rebuildWidgets();
        EditBox field = focused != null ? textFields.get(focused) : null;
        if (field != null) {
            setFocused(field);
        }
    }

    // Widgets

    /**
     * Adds a text field. Every field sets its maximum length, because the default is 32.
     *
     * @param name a name unique on this screen, which keeps its focus across rebuilds
     */
    protected final EditBox textField(String name, int x, int y, int width, int maxLength, String value,
                                      Component hint, Consumer<String> responder) {
        EditBox field = new EditBox(this.font, x, y, width, 20, hint);
        field.setMaxLength(maxLength);
        field.setHint(hint);
        // The value goes in before the responder, which would otherwise see it as an edit
        field.setValue(value);
        field.setResponder(typed -> {
            responder.accept(typed);
            // What the operator types replaces the last answer, so the field's own problem shows
            setStatus("", MUTED);
            updateButtons();
        });
        this.addRenderableWidget(field);
        this.textFields.put(name, field);
        return field;
    }

    /** Adds a button that arms a slot and opens the native dialog for it. */
    protected final Button slotButton(Component label, Supplier<FilePicker.Slot> slot, int x, int y, int width) {
        Button button = Button.builder(label, pressed -> choose(slot)).bounds(x, y, width, 20).build();
        this.addRenderableWidget(button);
        this.slotButtons.add(button);
        return button;
    }

    /** Adds the typed-path row: a path field and a button that reads the file into the armed slot. */
    protected final void pathRow(int x, int y, int width) {
        textField("path", x, y, width - 58, FilePicker.MAX_TYPED_PATH, typedPath, label("path_hint"),
                value -> typedPath = value);
        this.usePathButton = Button.builder(label("use_path"), button -> useTypedPath())
                .bounds(x + width - 54, y, 54, 20)
                .build();
        this.addRenderableWidget(this.usePathButton);
    }

    // Files

    /** Forgets the armed slot when its button goes away, as when a figure becomes a skin. */
    protected final void disarm(FilePicker.Slot slot) {
        if (armed != null && armed.get() == slot) {
            armed = null;
        }
    }

    private void choose(Supplier<FilePicker.Slot> slot) {
        this.armed = slot;
        FilePicker.Slot chosen = slot.get();
        if (!FilePicker.isNativeAvailable()) {
            setStatus(tr("type_or_drop", slotName(chosen)), PENDING);
            EditBox path = textFields.get("path");
            if (path != null) {
                setFocused(path);
            }
            return;
        }
        String problem = FilePicker.open(this, chosen, result -> received(chosen, result));
        if (problem != null) {
            setStatus(problem, ERROR);
        } else {
            setStatus(tr("choosing", slotName(chosen)), PENDING);
        }
        updateButtons();
    }

    private void useTypedPath() {
        if (armed == null) {
            setStatus(tr("choose_slot_first"), ERROR);
            return;
        }
        FilePicker.Slot slot = armed.get();
        String problem = FilePicker.readTyped(this, slot, typedPath, result -> received(slot, result));
        setStatus(problem != null ? problem : tr("reading", slotName(slot)), problem != null ? ERROR : PENDING);
        updateButtons();
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (armed == null) {
            setStatus(tr("choose_slot_first"), ERROR);
            return;
        }
        FilePicker.Slot slot = armed.get();
        String problem = FilePicker.readDropped(this, slot, paths, result -> received(slot, result));
        setStatus(problem != null ? problem : tr("reading", slotName(slot)), problem != null ? ERROR : PENDING);
        updateButtons();
    }

    private void received(FilePicker.Slot slot, FilePicker.PickResult result) {
        switch (result.kind()) {
            case ACCEPTED -> {
                FilePicker.LocalFile file = result.file();
                setStatus(tr("file_ready", file.name(), slotName(slot)), SUCCESS);
                accepted(slot, file);
            }
            case CANCELLED -> setStatus(tr("cancelled"), MUTED);
            case REFUSED -> {
                setStatus(result.message(), ERROR);
                refused(slot, result.message());
            }
            case UNAVAILABLE -> {
                setStatus(result.message(), WARNING);
                EditBox path = textFields.get("path");
                if (path != null) {
                    setFocused(path);
                }
            }
        }
        updateButtons();
    }

    static String slotName(FilePicker.Slot slot) {
        return tr("slot." + slot.name().toLowerCase(Locale.ROOT));
    }

    // Requests

    /**
     * Uploads the files the server does not have, then sends the edit; the status line
     * follows its progress until the server answers.
     */
    protected final void submit(List<FilePicker.LocalFile> files, WorldCollectionEdit edit,
                                Consumer<ClientWorldUploads.Outcome> done) {
        this.waiting = true;
        boolean sent = ClientWorldUploads.submit(files, edit, outcome -> {
            this.waiting = false;
            done.accept(outcome);
            updateButtons();
        });
        if (!sent) {
            this.waiting = false;
            setStatus(tr("not_sent"), ERROR);
        } else if (this.waiting) {
            setStatus(tr("sending"), PENDING);
        }
        updateButtons();
    }

    protected final boolean isWaiting() {
        return waiting;
    }

    /** Shows how a submission ended: the given line when it worked, otherwise the server's reason. */
    protected final void showOutcome(ClientWorldUploads.Outcome outcome, String success) {
        if (outcome.disconnected()) {
            setStatus(tr("disconnected"), ERROR);
        } else if (outcome.ok()) {
            setStatus(success, SUCCESS);
        } else {
            setStatus(failure(outcome.status()), ERROR);
        }
    }

    /** The server's reason for a refusal, in the words the commands use. */
    static String failure(WorldContentStatus status) {
        if (status.result() == WorldContentStatus.Result.TIMED_OUT && !status.detail().isEmpty()) {
            return tr("timed_out", status.detail());
        }
        return Component.translatable("blockpops.world_collections." + status.result().name().toLowerCase(Locale.ROOT),
                status.detail()).getString();
    }

    protected final void setStatus(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    private String statusLine() {
        if (waiting) {
            ClientWorldUploads.Progress progress = ClientWorldUploads.progress();
            if (progress == null || progress.file().isEmpty()) {
                return tr("saving");
            }
            return tr("uploading", progress.file(), progress.filesDone() + 1, progress.filesTotal(),
                    kib(progress.sent()), kib(progress.total()));
        }
        return status.isEmpty() ? idleStatus() : status;
    }

    /** A size for people: bytes below a kibibyte, otherwise kibibytes with one decimal. */
    static String kib(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    // Drawing

    //? if >=1.21 && <1.21.6 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws its own background and panel before Screen.render, which on 1.21 to
        // 1.21.5 calls this vanilla pass and would blur the panel on top of it.
    }
    *///? }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, partialTicks);
        // The opaque panel and outline of SettingsScreen
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL);
        frame(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, PANEL_OUTLINE);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Labels are drawn after the widgets for the same reason as in SettingsScreen:
        // before 1.21.6 anything drawn earlier ends up under the screen background.
        int centerX = this.panelX + this.panelWidth / 2;
        graphics.drawCenteredString(this.font, cut(heading(), this.panelWidth - 16), centerX, this.panelY + 8, TEXT);
        drawContents(graphics, mouseX, mouseY, partialTicks);

        String line = statusLine();
        if (!line.isEmpty()) {
            int y = statusY();
            for (String part : fit(line, statusWidth(), statusLines())) {
                graphics.drawCenteredString(this.font, part, statusCenterX(), y, waiting ? PENDING : statusColor);
                y += LINE_HEIGHT;
            }
        }
    }

    /**
     * The background of the machine screen these screens open from, dimmed as SettingsScreen
     * dims it: its colour and scrolling stars from the client config.
     */
    private void drawBackground(GuiGraphics graphics, float partialTicks) {
        ClientConfig config = ClientConfig.getInstance();
        int background = 0xFF000000 | ((int) (config.backgroundColorR * 255) << 16)
                | ((int) (config.backgroundColorG * 255) << 8) | (int) (config.backgroundColorB * 255);
        graphics.fill(0, 0, this.width, this.height, background);
        //? if <1.21.5 {
        // Finish buffered base fills before the immediate textured quad draws.
        GuiPose.flush(graphics);
        //? }
        CollectionSelectionScreen.drawStarPattern(graphics, this.width, this.height, partialTicks);
        graphics.fill(0, 0, this.width, this.height, DIM);
        // Close the background layer so the panel and its text draw above it
        GuiPose.nextStratum(graphics);
    }

    /**
     * Draws where typed and dropped files go, and how else to choose one, each wrapped to the
     * width, in at most the given number of lines.
     */
    protected final void drawPickerHelp(GuiGraphics graphics, int x, int y, int width, int maxLines) {
        String target = armed != null ? tr("path_target", slotName(armed.get())) : tr("path_no_target");
        List<String> targetLines = fit(target, width, Math.max(1, maxLines - 1));
        for (String part : targetLines) {
            graphics.drawString(this.font, part, x, y, MUTED);
            y += LINE_HEIGHT;
        }
        int left = maxLines - targetLines.size();
        if (left <= 0) {
            return;
        }
        String hint = !FilePicker.isNativeAvailable() ? FilePicker.NO_DIALOG_HINT
                : FilePicker.isDialogOpen() ? FilePicker.HIDDEN_DIALOG_HINT
                : FilePicker.FALLBACK_HINT;
        for (String part : fit(hint, width, left)) {
            graphics.drawString(this.font, part, x, y, FilePicker.isNativeAvailable() ? MUTED : WARNING);
            y += LINE_HEIGHT;
        }
    }

    /** A line of text and its colour. */
    record Line(String text, int color) {
    }

    /**
     * Describes a slot's file: one chosen here and not saved yet, one the server stores,
     * or none.
     */
    static Line fileLine(@Nullable FilePicker.LocalFile pending, @Nullable String sha, String none) {
        if (pending != null) {
            return new Line(tr("file_pending", pending.name(), kib(pending.bytes().length), details(pending.info())),
                    PENDING);
        }
        if (sha == null) {
            return new Line(none, MUTED);
        }
        if (ClientWorldCollections.isMissingOnServer(sha)) {
            return new Line(tr("file_missing"), ERROR);
        }
        if (ClientWorldCollections.failedToLoad(sha)) {
            return new Line(tr("file_failed"), ERROR);
        }
        BlobInfo info = ClientWorldCollections.blob(sha);
        return info == null ? new Line(tr("file_unknown"), ERROR)
                : new Line(tr("file_stored", kib(info.size()), details(info)), MUTED);
    }

    /**
     * Describes a slot after a file was refused for it: the refusal, after the file chosen
     * earlier when one stays chosen, since Save still uploads that one.
     */
    static Line refusalLine(@Nullable String refusal, @Nullable FilePicker.LocalFile pending, @Nullable String sha,
                            String none) {
        if (refusal == null) {
            return fileLine(pending, sha, none);
        }
        return new Line(pending != null ? tr("refused_kept", pending.name(), refusal) : refusal, ERROR);
    }

    /** What validation learned about a file, for people. */
    static String details(BlobInfo info) {
        return switch (info.type()) {
            case PNG -> info.width() + "x" + info.height();
            case GEO -> tr("details_model", info.cubes(), info.texWidth(), info.texHeight());
            case ANIMATION -> tr(info.hasPose(BlobInfo.POSE_SIT) ? "details_poses_sit" : "details_poses",
                    info.keyframes());
        };
    }

    /** Draws text on one line, cut to a width as {@link #cut} cuts it. */
    protected final void drawCut(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        graphics.drawString(this.font, cut(text, width), x, y, color);
    }

    /**
     * Text that fits a width: the whole text when it fits, otherwise its start up to the
     * last whole word that fits with "..." after it. A text whose first word alone is too
     * wide, such as a long file path, is cut inside it.
     */
    protected final String cut(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        int room = width - this.font.width(ELLIPSIS);
        if (room <= 0) {
            return this.font.plainSubstrByWidth(text, width);
        }
        String start = this.font.plainSubstrByWidth(text, room);
        int space = start.lastIndexOf(' ');
        if (space > 0 && space >= start.length() / 2 && text.charAt(start.length()) != ' ') {
            // The cut fell inside a word: end at the space before it, unless that word is
            // most of what would show
            start = start.substring(0, space);
        }
        int end = start.length();
        while (end > 0 && " ,;:-".indexOf(start.charAt(end - 1)) >= 0) {
            end--;
        }
        return start.substring(0, end) + ELLIPSIS;
    }

    /**
     * Splits text into lines no wider than the width, breaking between words; a word wider
     * than a whole line, such as a file path, is broken where it has to be.
     */
    protected final List<String> wrap(String text, int width) {
        return wrap(text, width, new ArrayList<>());
    }

    /** {@link #wrap(String, int)}, adding to starts where each line begins in the text. */
    private List<String> wrap(String text, int width, List<Integer> starts) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineStart = 0;
        int next = 0;
        while (next < text.length()) {
            int space = text.indexOf(' ', next);
            int wordEnd = space < 0 ? text.length() : space;
            int wordStart = next;
            String word = text.substring(wordStart, wordEnd);
            next = wordEnd + 1;
            if (word.isEmpty()) {
                continue;
            }
            String joined = line.length() == 0 ? word : line + " " + word;
            if (this.font.width(joined) <= width) {
                if (line.length() == 0) {
                    lineStart = wordStart;
                }
                line.setLength(0);
                line.append(joined);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                starts.add(lineStart);
                line.setLength(0);
            }
            while (this.font.width(word) > width) {
                String part = this.font.plainSubstrByWidth(word, width);
                if (part.isEmpty()) {
                    // Narrower than one character: one per line rather than none
                    part = word.substring(0, word.offsetByCodePoints(0, 1));
                }
                lines.add(part);
                starts.add(wordStart);
                word = word.substring(part.length());
                wordStart += part.length();
            }
            line.append(word);
            lineStart = wordStart;
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
            starts.add(lineStart);
        }
        return lines;
    }

    /**
     * Text wrapped to a width in at most the given number of lines; when it needs more, the
     * last line ends in "..." as {@link #cut} ends it.
     */
    protected final List<String> fit(String text, int width, int maxLines) {
        List<Integer> starts = new ArrayList<>();
        List<String> lines = wrap(text, width, starts);
        int max = Math.max(1, maxLines);
        if (lines.size() <= max) {
            return lines;
        }
        List<String> fitted = new ArrayList<>(lines.subList(0, max - 1));
        // The rest of the text itself, so a word broken across lines gains no space
        fitted.add(cut(text.substring(starts.get(max - 1)), width));
        return fitted;
    }

    /** Draws a one-pixel frame. */
    protected static void frame(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        // A paused integrated server stops ticking, and with it syncs, file sends and upload sweeps
        return false;
    }
}
