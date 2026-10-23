package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A row of options of which exactly one is chosen, as the view and pose switches: each option is
 * a small raised button in the menus' style, and the chosen one stands on the slate of a selected
 * tab inside Quick Skin's blue selection border. An option can be
 * disabled with a reason shown in its tooltip, such as Sit for a model that has no sitting pose.
 * When the row is given more width than its labels need, the options share it equally; given
 * less, they narrow in proportion and their labels are cut.
 */
public class UiSegmented extends UiNode.Box {
    public static final int HEIGHT = 16;
    private static final int PADDING = 6;
    private static final int GAP = 3;
    private static final int OFF_TEXT = 0xFF6A6A6A;

    private final List<Component> options;
    private final List<Component> disabled = new ArrayList<>();
    private final IntConsumer onSelect;
    private int selected;

    public UiSegmented(List<Component> options, int selected, IntConsumer onSelect) {
        this.options = new ArrayList<>(options);
        this.selected = selected;
        this.onSelect = onSelect;
        this.height = HEIGHT;
        for (int i = 0; i < options.size(); i++) {
            disabled.add(null);
        }
    }

    public UiSegmented(int selected, IntConsumer onSelect, Component... options) {
        this(Arrays.asList(options), selected, onSelect);
    }

    public int selected() {
        return selected;
    }

    /** Chooses an option without telling the screen. */
    public void select(int index) {
        if (index >= 0 && index < options.size()) {
            selected = index;
        }
    }

    /** Disables an option and says why; null enables it again. */
    public void disable(int index, @Nullable Component reason) {
        if (index >= 0 && index < options.size()) {
            disabled.set(index, reason);
        }
    }

    /** The width the options need side by side. */
    public int naturalWidth() {
        Font font = Minecraft.getInstance().font;
        int total = 0;
        for (int i = 0; i < options.size(); i++) {
            total += font.width(options.get(i)) + 2 * PADDING + (i > 0 ? GAP : 0);
        }
        return total;
    }

    // Wider than the options need, they share the room equally; narrower, as in a small preview,
    // each gives up room in proportion and its label is cut
    private int[] widths(Font font) {
        int[] widths = new int[options.size()];
        int natural = naturalWidth();
        boolean share = width > natural && !options.isEmpty();
        int each = share ? (width - GAP * (options.size() - 1)) / options.size() : 0;
        int gaps = GAP * Math.max(0, options.size() - 1);
        boolean shrink = width > gaps && width < natural;
        for (int i = 0; i < options.size(); i++) {
            int own = font.width(options.get(i)) + 2 * PADDING;
            widths[i] = share ? each : shrink ? Math.max(1, own * (width - gaps) / (natural - gaps)) : own;
        }
        return widths;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!visible() || mouseY < y || mouseY >= y + height) {
            return -1;
        }
        int[] widths = widths(Minecraft.getInstance().font);
        int left = x;
        for (int i = 0; i < widths.length; i++) {
            if (mouseX >= left && mouseX < left + widths[i]) {
                return i;
            }
            left += widths[i] + GAP;
        }
        return -1;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return indexAt(mouseX, mouseY) >= 0;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int[] widths = widths(font);
        int hoveredIndex = indexAt(mouseX, mouseY);
        int left = x;
        for (int i = 0; i < widths.length; i++) {
            boolean on = i == selected;
            Component reason = disabled.get(i);
            boolean hovered = i == hoveredIndex;
            boolean lit = hovered && reason == null && !on;
            int border = on ? Theme.SELECTED : reason != null ? Surfaces.BORDER_DISABLED
                    : lit ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
            int face = on ? Theme.HIGHLIGHT : reason != null ? Surfaces.FACE_DISABLED
                    : lit ? Surfaces.FACE_HOVER : Surfaces.FACE;
            Draw.raised(graphics, left, y, widths[i], height, border, face, 1);
            int colour = on ? Theme.TEXT : reason != null ? OFF_TEXT : lit ? Theme.TEXT : Theme.CONTROL_TEXT;
            Draw.centred(graphics, font, options.get(i).getString(), left + widths[i] / 2, y + (height - 9) / 2,
                    widths[i] - 4, colour);
            if (hovered && reason != null) {
                UiTooltip.set(reason);
            }
            left += widths[i] + GAP;
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left()) {
            return false;
        }
        int index = indexAt(click.x(), click.y());
        if (index < 0) {
            return false;
        }
        if (index != selected && disabled.get(index) == null) {
            selected = index;
            onSelect.accept(index);
        }
        return true;
    }
}
