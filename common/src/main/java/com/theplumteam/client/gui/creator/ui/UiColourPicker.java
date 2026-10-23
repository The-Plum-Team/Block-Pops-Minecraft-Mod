package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A colour picker for any colour, opened by "+ Custom" beside the swatches: a square of
 * saturation (left to right) and brightness (top to bottom) for the chosen hue, a hue bar beside
 * it, the new colour over the old one, and a hex field. Dragging in the square or the bar
 * changes the colour and the hex field follows; typing a hex colour moves the markers, and a hex
 * that is not six hex digits outlines the field in red and changes nothing.
 *
 * <p>The square is drawn as two-pixel columns, each a gradient from that column's fully bright
 * colour down to black, which is exactly how brightness scales a colour; the bar is six
 * gradients between the primary and secondary colours. Only fills are used, so it looks the same
 * on every version. {@link #open} shows it in a dialog and hands the chosen colour over as
 * 0xRRGGBB.
 */
public class UiColourPicker extends UiNode.Box {
    private static final int MAX_SIDE = 160;
    private static final int MIN_SIDE = 80;
    private static final int HUE_WIDTH = 12;
    private static final int GAP = 8;
    private static final int SWATCH_WIDTH = 44;
    private static final int HEX_WIDTH = 64;
    private static final int[] HUES = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000};
    // The dialog's frame around the picker: its header strip and lip (31), padding (20), row gap
    // (6) and button row (30), with a little to spare
    private static final int DIALOG_CHROME = 90;
    // The dialog's padding on both sides of its rows
    private static final int DIALOG_SIDES = 20;

    private enum Part { NONE, SQUARE, HUE }

    private final int original;
    private final UiField hexBox;
    private float hue;
    private float saturation;
    private float brightness;
    private boolean hexValid = true;
    // Set while the picker writes the hex field itself, so its responder ignores the change
    private boolean syncing;
    private Part pressed = Part.NONE;

    public UiColourPicker(int rgb) {
        this.original = rgb & 0xFFFFFF;
        Font font = Minecraft.getInstance().font;
        this.hexBox = new UiField(font, 0, 0, HEX_WIDTH, UiField.HEIGHT, CreatorScreen.tr("colour.hex"));
        this.hexBox.setMaxLength(7);
        this.hexBox.setValue(hex(original));
        this.hexBox.setResponder(this::hexTyped);
        setFromRgb(original, true);
    }

    /**
     * Opens the picker in a dialog over a screen, starting at a colour.
     *
     * @param onPick receives the colour as 0xRRGGBB when the operator confirms it
     */
    public static UiColourPicker open(CreatorScreen screen, int rgb, IntConsumer onPick) {
        UiModal dialog = new UiModal(CreatorScreen.tr("colour.title"), Component.empty())
                .accent(DialogFrame.Accent.EDIT, PixelIcon.PALETTE);
        UiColourPicker picker = new UiColourPicker(rgb);
        int beside = GAP + HUE_WIDTH + 2 * GAP + Math.max(SWATCH_WIDTH, HEX_WIDTH) + DIALOG_SIDES;
        // The card never grows past the screen, so on a narrow one the square shrinks instead
        int room = Math.min(screen.height - DIALOG_CHROME, screen.width - 2 * Theme.SPACE_L - beside);
        int side = Math.max(MIN_SIDE, Math.min(MAX_SIDE, room)) & ~1;
        dialog.width(side + beside);
        dialog.node(picker, side);
        dialog.ownField(picker.hexBox);
        dialog.cancelButton(CommonComponents.GUI_CANCEL);
        dialog.button(CreatorScreen.tr("colour.use"), UiButton.Style.PRIMARY, () -> onPick.accept(picker.rgb()));
        screen.openModal(dialog);
        return picker;
    }

    /** The colour now chosen, as 0xRRGGBB. */
    public int rgb() {
        return hsvToRgb(hue, saturation, brightness);
    }

    @Override
    public Box at(int x, int y, int width, int height) {
        super.at(x, y, width, height);
        hexBox.place(columnX(), y + 54, Math.max(40, Math.min(HEX_WIDTH, x + width - columnX())));
        return this;
    }

    // The square takes the full height; its width is a whole number of two-pixel columns
    private int side() {
        return height & ~1;
    }

    private int hueX() {
        return x + side() + GAP;
    }

    private int columnX() {
        return hueX() + HUE_WIDTH + 2 * GAP;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int side = side();
        int columns = side / 2;
        for (int column = 0; column < columns; column++) {
            int top = 0xFF000000 | hsvToRgb(hue, column / (float) (columns - 1), 1.0f);
            graphics.fillGradient(x + 2 * column, y, x + 2 * column + 2, y + side, top, 0xFF000000);
        }
        int hueX = hueX();
        for (int i = 0; i < 6; i++) {
            int from = y + i * side / 6;
            int to = y + (i + 1) * side / 6;
            graphics.fillGradient(hueX, from, hueX + HUE_WIDTH, to, 0xFF000000 | HUES[i], 0xFF000000 | HUES[i + 1]);
        }
        // Framed as the menus' wells are: a grey border inside a black outline
        Draw.outline(graphics, x - 2, y - 2, side + 4, side + 4, Surfaces.OUTLINE);
        Draw.outline(graphics, x - 1, y - 1, side + 2, side + 2, Surfaces.BORDER);
        Draw.outline(graphics, hueX - 2, y - 2, HUE_WIDTH + 4, side + 4, Surfaces.OUTLINE);
        Draw.outline(graphics, hueX - 1, y - 1, HUE_WIDTH + 2, side + 2, Surfaces.BORDER);

        // A hollow square on the colour, and a line across the bar at the hue
        int markX = x + Math.round(saturation * (side - 1));
        int markY = y + Math.round((1.0f - brightness) * (side - 1));
        Draw.outline(graphics, markX - 3, markY - 3, 7, 7, 0xFF000000);
        Draw.outline(graphics, markX - 2, markY - 2, 5, 5, 0xFFFFFFFF);
        int hueY = y + Math.round(hue * (side - 1));
        graphics.fill(hueX - 2, hueY - 2, hueX + HUE_WIDTH + 2, hueY + 3, 0xFF000000);
        graphics.fill(hueX - 1, hueY - 1, hueX + HUE_WIDTH + 1, hueY + 2, 0xFFFFFFFF);
        graphics.fill(hueX, hueY, hueX + HUE_WIDTH, hueY + 1, 0xFF000000 | hsvToRgb(hue, 1.0f, 1.0f));

        int columnX = columnX();
        Draw.string(graphics, font, CreatorScreen.tr("colour.new"), columnX, y, Theme.MUTED);
        Draw.panel(graphics, columnX, y + 10, SWATCH_WIDTH, 16, 0xFF000000 | rgb(), Surfaces.OUTLINE);
        Draw.panel(graphics, columnX, y + 26, SWATCH_WIDTH, 12, 0xFF000000 | original, Surfaces.OUTLINE);
        if (oldSwatch().contains(mouseX, mouseY)) {
            UiTooltip.set(CreatorScreen.tr("colour.old", hex(original)));
        }
        Draw.string(graphics, font, CreatorScreen.tr("colour.hex"), columnX, y + 44, Theme.MUTED);
        hexBox.render(graphics, mouseX, mouseY, partialTick);
        if (!hexValid) {
            // The well's border in red
            Draw.outline(graphics, hexBox.wellX() + 1, hexBox.getY() + 1, hexBox.wellWidth() - 2,
                    hexBox.getHeight() - 2, Theme.RED);
            Draw.lines(graphics, font, CreatorScreen.tr("colour.hex_rule").getString(), columnX, hexBox.getY() + 24,
                    Math.max(20, x + width - columnX), 3, Theme.RED);
        }
    }

    private Rect oldSwatch() {
        return new Rect(columnX(), y + 26, SWATCH_WIDTH, 12);
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressed = Part.NONE;
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        int side = side();
        if (new Rect(x, y, side, side).contains(click.x(), click.y())) {
            pressed = Part.SQUARE;
        } else if (new Rect(hueX() - 2, y, HUE_WIDTH + 4, side).contains(click.x(), click.y())) {
            pressed = Part.HUE;
        } else if (oldSwatch().contains(click.x(), click.y())) {
            // Back to the colour the picker opened with
            setFromRgb(original, false);
            showHex();
            return true;
        }
        pointAt(click.x(), click.y());
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (pressed == Part.NONE) {
            return false;
        }
        pointAt(click.x(), click.y());
        return true;
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean used = pressed != Part.NONE;
        pressed = Part.NONE;
        return used;
    }

    // Moves the pressed part's marker to the pointer, kept inside it while the drag strays
    private void pointAt(double mouseX, double mouseY) {
        int side = side();
        float across = clamp((float) (mouseX - x) / (side - 1));
        float down = clamp((float) (mouseY - y) / (side - 1));
        if (pressed == Part.SQUARE) {
            saturation = across;
            brightness = 1.0f - down;
        } else if (pressed == Part.HUE) {
            hue = down;
        } else {
            return;
        }
        showHex();
    }

    private void showHex() {
        syncing = true;
        hexBox.setValue(hex(rgb()));
        syncing = false;
        hexValid = true;
    }

    private void hexTyped(String text) {
        if (syncing) {
            return;
        }
        Integer parsed = parseHex(text);
        hexValid = parsed != null;
        if (parsed != null) {
            setFromRgb(parsed, false);
        }
    }

    // A grey or black colour has no hue of its own, so it keeps the bar where it was
    private void setFromRgb(int rgb, boolean always) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        brightness = max;
        saturation = max == 0 ? 0 : delta / max;
        if (delta == 0) {
            if (always) {
                hue = 0;
            }
            return;
        }
        float h;
        if (max == r) {
            h = (g - b) / delta;
        } else if (max == g) {
            h = 2 + (b - r) / delta;
        } else {
            h = 4 + (r - g) / delta;
        }
        hue = (h / 6.0f + 1.0f) % 1.0f;
    }

    /** A colour from hue, saturation and brightness, each from 0 to 1, as 0xRRGGBB. */
    public static int hsvToRgb(float hue, float saturation, float value) {
        float h = (hue % 1.0f) * 6.0f;
        int sector = Math.min(5, (int) h);
        float f = h - sector;
        float p = value * (1 - saturation);
        float q = value * (1 - saturation * f);
        float t = value * (1 - saturation * (1 - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /** A colour as "#RRGGBB". */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Reads "#RRGGBB" or "RRGGBB" as 0xRRGGBB, or returns null. */
    @Nullable
    public static Integer parseHex(String text) {
        String digits = text.strip();
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        return Integer.parseInt(digits, 16);
    }
}
