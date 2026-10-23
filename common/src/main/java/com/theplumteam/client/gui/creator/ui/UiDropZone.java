package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.runtime.RuntimeTextures;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A place to put a file: a dashed box that says what it accepts ("Skin (PNG 64×64)", "Model
 * (.geo.json)"), takes a file dropped onto it, and opens the file dialog when clicked. Once
 * filled it shows a thumbnail, the file's name and a chip for each check (see
 * {@link FileChecks}), with "Change" and "Remove" links; a refused file shows why in red, keeping
 * the file it had. Where no file dialog can be shown, a click asks for the file's path instead.
 *
 * <p>A zone fills one slot, or, made with {@link #many}, takes any number of files and folders
 * at once for the bulk add. {@link CreatorScreen} routes a file dropped anywhere on the window:
 * to the zone under the pointer, else to the only zone that takes that kind of file, else it
 * asks which slot. A zone lights up while the pointer is over it, which on platforms that report
 * the pointer during a drag from the file manager includes the drag, and flashes when a file
 * lands.
 *
 * <p>A picked file reaches the screen through the zone's receiver even when the screen was
 * built again meanwhile, so the screen keeps the file in its own state and shows it again with
 * {@link #showFile} when it builds its zones. A thumbnail is the texture loaded under the file's
 * content-addressed id, which a synced file always has and a local one has once the preview
 * pipeline has loaded it; until then the zone shows the file's kind in its place.
 */
public class UiDropZone extends UiNode.Box {
    /** The height of a zone with a thumbnail. */
    public static final int HEIGHT = 42;
    /** The height of a one-line zone, as the figure inspector lists its files. */
    public static final int COMPACT_HEIGHT = 16;
    private static final int PADDING = 5;
    private static final int CHIP_HEIGHT = 11;
    private static final long FLASH_MS = 450;
    private static final int BACKGROUND = 0xFF111111;
    private static final int THUMB_BACKGROUND = 0xFF1D1D1D;
    private static final int EDGE = 0xFF6A6A6A;
    private static final int EDGE_MANY = 0xFF8A7A3A;
    private static final int EDGE_ERROR = 0xFFB34A4A;
    private static final int REMOVE = 0xFFFF9A9A;
    private static final int REMOVE_HOVER = 0xFFFFC8C8;
    private static final int ERROR_TEXT = 0xFFFF8080;

    private final CreatorScreen host;
    @Nullable
    private final FilePicker.Slot slot;
    private final Component label;
    @Nullable
    private final Consumer<FilePicker.LocalFile> onFile;
    @Nullable
    private final Consumer<List<FilePicker.PickResult>> onFiles;
    @Nullable
    private Component help;
    @Nullable
    private Runnable onRemove;
    private Function<BlobInfo, List<FileChecks.Check>> checker;
    private int labelWidth = -1;
    private String fileName = "";
    @Nullable
    private String sha;
    @Nullable
    private BlobInfo info;
    private List<FileChecks.Check> checks = List.of();
    @Nullable
    private Component error;
    private boolean waiting;
    // Whether the screen's status line shows this zone's dialog hint, to clear once answered
    private boolean hintShown;
    private long flashUntil;
    @Nullable
    private Component disabledReason;

    private UiDropZone(CreatorScreen host, @Nullable FilePicker.Slot slot, Component label,
                       @Nullable Consumer<FilePicker.LocalFile> onFile,
                       @Nullable Consumer<List<FilePicker.PickResult>> onFiles) {
        this.host = host;
        this.slot = slot;
        this.label = label;
        this.onFile = onFile;
        this.onFiles = onFiles;
        this.checker = slot != null ? info -> FileChecks.forSlot(slot, info) : info -> List.of();
        this.height = HEIGHT;
    }

    /** A zone for one slot, which hands each accepted file to the receiver. */
    public static UiDropZone single(CreatorScreen host, FilePicker.Slot slot, Component label,
                                    Consumer<FilePicker.LocalFile> onFile) {
        return new UiDropZone(host, slot, label, onFile, null);
    }

    /** A zone for one slot with the slot's usual label. */
    public static UiDropZone single(CreatorScreen host, FilePicker.Slot slot, Consumer<FilePicker.LocalFile> onFile) {
        return single(host, slot, defaultLabel(slot), onFile);
    }

    /** A zone for the bulk add, which hands over every file read from a drop or a pick at once. */
    public static UiDropZone many(CreatorScreen host, Component label, Consumer<List<FilePicker.PickResult>> onFiles) {
        return new UiDropZone(host, null, label, null, onFiles);
    }

    /** What a zone of a slot says it accepts. */
    public static Component defaultLabel(FilePicker.Slot slot) {
        return CreatorScreen.tr("drop.label." + slot.name().toLowerCase(Locale.ROOT));
    }

    /** The slot's name inside a sentence, such as "model texture". */
    public static Component slotName(FilePicker.Slot slot) {
        return CreatorScreen.tr("slot." + slot.name().toLowerCase(Locale.ROOT));
    }

    /** The grey line under the label of an empty zone; by default how to fill it. */
    public UiDropZone help(@Nullable Component help) {
        this.help = help;
        return this;
    }

    /** Shows a "Remove" link on a filled zone that runs this. */
    public UiDropZone onRemove(@Nullable Runnable onRemove) {
        this.onRemove = onRemove;
        return this;
    }

    /** Replaces the slot's checks, as a model texture's do once the model is known. */
    public UiDropZone checks(Function<BlobInfo, List<FileChecks.Check>> checker) {
        this.checker = checker;
        if (info != null) {
            this.checks = checker.apply(info);
        }
        return this;
    }

    /** The width of the label column of a one-line zone, so a list of zones lines up. */
    public UiDropZone labelWidth(int width) {
        this.labelWidth = width;
        return this;
    }

    /** Stops the zone taking files, saying why in its tooltip; null lets it take them again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
    }

    public Component label() {
        return label;
    }

    @Nullable
    public FilePicker.Slot slot() {
        return slot;
    }

    /** Shows a file as the zone's content: a local file just picked, or one already on the server. */
    public void showFile(String name, @Nullable String fileSha, BlobInfo fileInfo) {
        this.fileName = name;
        this.sha = fileSha;
        this.info = fileInfo;
        this.checks = checker.apply(fileInfo);
        this.error = null;
    }

    public void showFile(FilePicker.LocalFile file) {
        showFile(file.name(), file.sha(), file.info());
    }

    public void showEmpty() {
        this.fileName = "";
        this.sha = null;
        this.info = null;
        this.checks = List.of();
        this.error = null;
    }

    /** Shows why a file was refused, in place of the checks of the file the zone keeps. */
    public void showError(@Nullable Component message) {
        this.error = message;
    }

    public boolean filled() {
        return info != null;
    }

    /** Whether every check of the zone's file passes; an empty zone has none. */
    public boolean checksPass() {
        return FileChecks.allOk(checks);
    }

    // Drops

    /**
     * Whether the zone would take these dropped paths: one file of its type, or for the bulk
     * add any figure files and folders.
     */
    public boolean accepts(List<Path> paths) {
        if (disabledReason != null || paths.isEmpty()) {
            return false;
        }
        if (slot == null) {
            for (Path path : paths) {
                if (FilePicker.typeOf(path) == null && !Files.isDirectory(path)) {
                    return false;
                }
            }
            return true;
        }
        return paths.size() == 1 && acceptsName(slot.type(), paths.get(0));
    }

    // A model or animation chosen from a dialog may be any .json, as SDL cannot filter on more
    private static boolean acceptsName(BlobType type, Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        return switch (type) {
            case PNG -> name.endsWith(".png");
            case GEO -> name.endsWith(".json") && !name.endsWith(".animation.json");
            case ANIMATION -> name.endsWith(".json") && !name.endsWith(".geo.json");
        };
    }

    /** Reads dropped paths into the zone; {@link CreatorScreen} calls this for the zone it routed them to. */
    public void receive(List<Path> paths) {
        flash();
        if (disabledReason != null) {
            host.status(disabledReason, Theme.RED);
            return;
        }
        String problem = slot != null
                ? FilePicker.readDropped(host, slot, paths, this::picked)
                : FilePicker.readMany(host, paths, this::pickedMany);
        started(problem);
    }

    /** Lights the zone up briefly, as when a file lands on it. */
    public void flash() {
        this.flashUntil = Util.getMillis() + FLASH_MS;
    }

    /** Opens the file dialog, or, where none can be shown, asks for the file's path. */
    public void choose() {
        if (disabledReason != null) {
            return;
        }
        if (!FilePicker.isNativeAvailable()) {
            askForPath();
            return;
        }
        String problem = slot != null
                ? FilePicker.open(host, slot, this::picked)
                : FilePicker.openMany(host, this::pickedMany);
        started(problem);
        if (problem == null) {
            host.status(Component.literal(FilePicker.HIDDEN_DIALOG_HINT), Theme.MUTED);
            hintShown = true;
        }
    }

    private void started(@Nullable String problem) {
        if (problem != null) {
            this.waiting = false;
            this.error = Component.literal(problem);
        } else {
            this.waiting = true;
            this.error = null;
        }
    }

    /** A dialog with a path field, for systems with no file dialog; dropping always works too. */
    private void askForPath() {
        UiModal dialog = new UiModal(CreatorScreen.tr("drop.path_title"), Component.literal(FilePicker.NO_DIALOG_HINT));
        EditBox field = dialog.field(CreatorScreen.tr("path_hint"), FilePicker.MAX_TYPED_PATH, "",
                CreatorScreen.tr("path_hint"), text -> { });
        dialog.cancelButton(CommonComponents.GUI_CANCEL);
        dialog.button(CreatorScreen.tr("use_path"), UiButton.Style.PRIMARY, () -> readTyped(field.getValue()));
        host.openModal(dialog);
    }

    private void readTyped(String typed) {
        if (slot != null) {
            started(FilePicker.readTyped(host, slot, typed, this::picked));
            return;
        }
        String text = typed.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            started("That is not a valid path");
            return;
        }
        started(text.isEmpty() || !path.isAbsolute() ? "Type the full path to the file or folder"
                : FilePicker.readMany(host, List.of(path), this::pickedMany));
    }

    private void picked(FilePicker.PickResult result) {
        this.waiting = false;
        switch (result.kind()) {
            case ACCEPTED -> {
                FilePicker.LocalFile file = result.file();
                showFile(file);
                flash();
                if (onFile != null) {
                    onFile.accept(file);
                }
            }
            case REFUSED -> this.error = Component.literal(result.message());
            case UNAVAILABLE -> askForPath();
            case CANCELLED -> {
            }
        }
        clearHint();
    }

    private void clearHint() {
        if (hintShown) {
            hintShown = false;
            host.status(Component.empty());
        }
    }

    private void pickedMany(List<FilePicker.PickResult> results) {
        this.waiting = false;
        clearHint();
        if (results.size() == 1 && results.get(0).kind() == FilePicker.PickResult.Kind.UNAVAILABLE) {
            askForPath();
            return;
        }
        if (results.size() == 1 && results.get(0).kind() == FilePicker.PickResult.Kind.CANCELLED) {
            return;
        }
        flash();
        if (onFiles != null) {
            onFiles.accept(results);
        }
    }

    @Override
    @Nullable
    public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        if (!visible()) {
            return null;
        }
        zones.add(this);
        return isMouseOver(mouseX, mouseY) ? this : null;
    }

    // Drawing

    private boolean compact() {
        return height < 30;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hovered = hovered(mouseX, mouseY);
        boolean flashing = Util.getMillis() < flashUntil;
        boolean usable = disabledReason == null;
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        if (flashing || (hovered && usable)) {
            graphics.fill(x, y, x + width, y + height, flashing ? 0x40FFD84A : 0x18FFD84A);
        }
        int edge = error != null ? EDGE_ERROR
                : (hovered && usable) || flashing ? Theme.GOLD
                : slot == null ? EDGE_MANY : EDGE;
        Draw.dashed(graphics, x, y, width, height, edge);

        List<Component> tooltip = new ArrayList<>();
        if (compact()) {
            drawCompact(graphics, font, mouseX, mouseY, tooltip);
        } else {
            drawFull(graphics, font, mouseX, mouseY, tooltip);
        }
        if (!usable) {
            graphics.fill(x, y, x + width, y + height, 0x90000000);
        }
        if (hovered) {
            if (!usable) {
                UiTooltip.set(disabledReason);
            } else if (!tooltip.isEmpty()) {
                UiTooltip.set(tooltip);
            }
        }
    }

    private void drawFull(GuiGraphics graphics, Font font, int mouseX, int mouseY, List<Component> tooltip) {
        int thumb = height - 2 * PADDING;
        int thumbX = x + PADDING;
        int thumbY = y + PADDING;
        drawThumbnail(graphics, font, thumbX, thumbY, thumb);

        int textX = thumbX + thumb + 8;
        int right = x + width - PADDING;
        if (info != null) {
            Rect change = changeLink(font);
            Rect remove = removeLink(font);
            drawLink(graphics, font, CreatorScreen.tr("drop.change"), change, change.contains(mouseX, mouseY),
                    Theme.GOLD, Theme.GOLD_LIGHT);
            if (remove != null) {
                drawLink(graphics, font, CreatorScreen.tr("drop.remove"), remove, remove.contains(mouseX, mouseY),
                        REMOVE, REMOVE_HOVER);
            }
            right = Math.min(change.x(), remove != null ? remove.x() : change.x()) - 8;
        }
        int room = Math.max(10, right - textX);
        int line = y + PADDING;
        if (info == null) {
            graphics.drawString(font, Draw.cut(font, label.getString(), room), textX, line, Theme.TEXT, false);
            if (error != null) {
                Draw.lines(graphics, font, error.getString(), textX, line + 12, room, 2, ERROR_TEXT);
                tooltip.add(error);
            } else {
                Draw.lines(graphics, font, emptyHint().getString(), textX, line + 12, room, 2, Theme.MUTED);
            }
            return;
        }
        graphics.drawString(font, Draw.cut(font, fileName, room), textX, line, Theme.TEXT, false);
        if (error != null) {
            Draw.text(graphics, font, error.getString(), textX, line + 13, room, ERROR_TEXT);
            tooltip.add(error);
        } else if (waiting) {
            Draw.text(graphics, font, waitingText().getString(), textX, line + 13, room, Theme.PENDING);
        } else if (!drawChecks(graphics, font, textX, line + 11, right)) {
            addChecks(tooltip);
        }
        Draw.text(graphics, font, label.getString(), textX, line + 24, room, Theme.FAINT);
    }

    private void drawCompact(GuiGraphics graphics, Font font, int mouseX, int mouseY, List<Component> tooltip) {
        int textY = y + (height - 8) / 2;
        int left = x + PADDING;
        int column = labelWidth >= 0 ? labelWidth : font.width(label) + 8;
        graphics.drawString(font, Draw.cut(font, label.getString(), column - 4), left, textY, Theme.CONTROL_TEXT, false);
        left += column;
        int right = x + width - PADDING;
        if (info != null && onRemove != null) {
            Rect remove = removeLink(font);
            drawLink(graphics, font, CreatorScreen.tr("drop.remove"), remove, remove.contains(mouseX, mouseY),
                    REMOVE, REMOVE_HOVER);
            right = remove.x() - 6;
        }
        if (info == null || error != null || waiting) {
            Component text = error != null ? error : waiting ? waitingText() : emptyHint();
            Draw.text(graphics, font, text.getString(), left, textY, Math.max(0, right - left),
                    error != null ? ERROR_TEXT : waiting ? Theme.PENDING : Theme.MUTED);
            if (error != null) {
                tooltip.add(error);
            }
            return;
        }
        // The chips keep the right side; the name has what is left
        int chipsWidth = 0;
        for (FileChecks.Check check : checks) {
            chipsWidth += chipWidth(font, check) + 3;
        }
        chipsWidth = Math.min(chipsWidth, Math.max(0, (right - left) * 2 / 3));
        int nameRoom = Math.max(0, right - left - chipsWidth - 6);
        Draw.text(graphics, font, fileName, left, textY, nameRoom, Theme.TEXT);
        if (!drawChecks(graphics, font, right - chipsWidth, y + (height - CHIP_HEIGHT) / 2, right)) {
            addChecks(tooltip);
        }
    }

    private Component emptyHint() {
        if (waiting) {
            return waitingText();
        }
        if (help != null) {
            return help;
        }
        return CreatorScreen.tr(slot == null ? "drop.click_many" : "drop.click");
    }

    private Component waitingText() {
        if (slot == null) {
            return CreatorScreen.tr("drop.reading_many");
        }
        return FilePicker.isDialogOpen() ? CreatorScreen.tr("choosing", slotName(slot))
                : CreatorScreen.tr("reading", slotName(slot));
    }

    private void drawThumbnail(GuiGraphics graphics, Font font, int left, int top, int size) {
        graphics.fill(left, top, left + size, top + size, THUMB_BACKGROUND);
        if (slot == null) {
            drawFolder(graphics, left + (size - 17) / 2, top + (size - 14) / 2);
            return;
        }
        if (info == null) {
            Draw.centred(graphics, font, "+", left + size / 2, top + (size - 8) / 2, size, Theme.GOLD);
            return;
        }
        ResourceLocation texture = info.type() == BlobType.PNG && sha != null ? WorldContentIds.texture(sha) : null;
        if (texture != null && !RuntimeTextures.isPending(texture)) {
            int[] fitted = Draw.fitInside(info.width(), info.height(), size, size);
            Draw.texture(graphics, texture, left + (size - fitted[0]) / 2, top + (size - fitted[1]) / 2, fitted[0],
                    fitted[1], info.width(), info.height());
            return;
        }
        // No pixels to show yet, or a JSON file: its kind instead
        String kind = switch (info.type()) {
            case PNG -> "PNG";
            case GEO -> "3D";
            case ANIMATION -> "▶";
        };
        Draw.centred(graphics, font, kind, left + size / 2, top + (size - 8) / 2, size, Theme.MUTED);
    }

    /** The folder of the mockup's bulk zone, 17 by 14, in gold. */
    private static void drawFolder(GuiGraphics graphics, int left, int top) {
        graphics.fill(left, top + 2, left + 6, top + 4, Theme.GOLD);
        graphics.fill(left + 6, top + 3, left + 8, top + 4, Theme.GOLD);
        graphics.fill(left, top + 4, left + 17, top + 5, Theme.GOLD);
        graphics.fill(left, top + 5, left + 17, top + 14, 0xFFC9A52A);
    }

    /**
     * Draws a chip per check from the left until the room runs out.
     *
     * @return whether every chip fitted
     */
    private boolean drawChecks(GuiGraphics graphics, Font font, int left, int top, int right) {
        for (FileChecks.Check check : checks) {
            int chip = chipWidth(font, check);
            if (left + chip > right) {
                return false;
            }
            left += drawCheck(graphics, font, check, left, top) + 3;
        }
        return true;
    }

    private void addChecks(List<Component> tooltip) {
        for (FileChecks.Check check : checks) {
            tooltip.add(Component.literal(check.label()));
        }
    }

    /** The width of a check's chip. */
    public static int chipWidth(Font font, FileChecks.Check check) {
        return font.width(check.label()) + 6;
    }

    /**
     * A check as the mockup's small chip: its text and symbol in its outcome's colour, in a
     * border of that colour.
     *
     * @return the chip's width
     */
    public static int drawCheck(GuiGraphics graphics, Font font, FileChecks.Check check, int left, int top) {
        int chip = chipWidth(font, check);
        Draw.outline(graphics, left, top, chip, CHIP_HEIGHT, check.mark().border());
        graphics.drawString(font, check.label(), left + 3, top + 2, check.mark().text(), false);
        return chip;
    }

    private Rect changeLink(Font font) {
        int linkWidth = font.width(CreatorScreen.tr("drop.change"));
        return new Rect(x + width - PADDING - linkWidth, y + PADDING, linkWidth, 10);
    }

    @Nullable
    private Rect removeLink(Font font) {
        if (onRemove == null) {
            return null;
        }
        int linkWidth = font.width(CreatorScreen.tr("drop.remove"));
        int top = compact() ? y + (height - 8) / 2 : y + PADDING + 14;
        return new Rect(x + width - PADDING - linkWidth, top, linkWidth, 10);
    }

    private static void drawLink(GuiGraphics graphics, Font font, Component text, Rect at, boolean hovered,
                                 int colour, int hoverColour) {
        int shown = hovered ? hoverColour : colour;
        graphics.drawString(font, text, at.x(), at.y(), shown, false);
        graphics.fill(at.x(), at.y() + 9, at.right(), at.y() + 10, shown);
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (disabledReason != null) {
            return true;
        }
        Font font = Minecraft.getInstance().font;
        Rect remove = info != null ? removeLink(font) : null;
        if (remove != null && remove.contains(click.x(), click.y())) {
            showEmpty();
            onRemove.run();
            return true;
        }
        // Anywhere else, "Change" included, chooses a file
        choose();
        return true;
    }
}
