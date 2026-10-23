package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A slider for a figure's size that stops only at the sizes a World collection allows,
 * {@link WorldCollectionRecord#SCALES}: a notch for each, labelled "Mini 0.5x", "0.75x", "1x",
 * "1.25x", "1.5x" and "Giant 2x" under the track. Clicking or dragging moves the knob to the
 * nearest notch, and the left and right arrow keys step it while the slider has the keyboard.
 * When the labels do not fit side by side, only the chosen one shows. It can be disabled with a
 * reason in its tooltip.
 */
public class UiSizeSlider extends UiNode.Box {
    public static final int HEIGHT = 24;
    private static final int KNOB_WIDTH = 7;
    private static final int KNOB_HEIGHT = 11;
    private static final int LABEL_GAP = 4;
    private static final int NOTCH = 0xFF6A6A6A;

    private final IntConsumer onChange;
    private int selected;
    private boolean pressed;
    @Nullable
    private Component disabledReason;

    /**
     * @param selected the index of the size in {@link WorldCollectionRecord#SCALES}
     * @param onChange told the index of each newly chosen size, while a drag goes on as well
     */
    public UiSizeSlider(int selected, IntConsumer onChange) {
        this.selected = clampIndex(selected);
        this.onChange = onChange;
        this.height = HEIGHT;
    }

    private static List<Float> scales() {
        return WorldCollectionRecord.SCALES;
    }

    private static int clampIndex(int index) {
        return Math.max(0, Math.min(scales().size() - 1, index));
    }

    public int selected() {
        return selected;
    }

    /** The chosen size itself. */
    public float scale() {
        return scales().get(selected);
    }

    /** Chooses a size by its index without telling the screen. */
    public void select(int index) {
        selected = clampIndex(index);
    }

    /** Disables the slider and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
        if (reason != null) {
            pressed = false;
        }
    }

    /** A size for people, such as "1.25x", the smallest called Mini and the largest Giant. */
    public static Component label(int index) {
        String text = number(scales().get(index)) + "x";
        if (index == 0) {
            return CreatorScreen.tr("size.mini", text);
        }
        if (index == scales().size() - 1) {
            return CreatorScreen.tr("size.giant", text);
        }
        return Component.literal(text);
    }

    // 0.50 reads 0.5 and 1.00 reads 1, while 0.75 and 1.25 keep both decimals
    private static String number(float scale) {
        String text = String.format(Locale.ROOT, "%.2f", scale);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    // The track keeps half a knob free at each end, so the end knobs stay inside the slider
    private int notchX(int index) {
        int left = x + KNOB_WIDTH / 2;
        int span = Math.max(1, width - KNOB_WIDTH);
        return left + Math.round(span * index / (float) (scales().size() - 1));
    }

    private int nearest(double mouseX) {
        int best = 0;
        for (int i = 1; i < scales().size(); i++) {
            if (Math.abs(mouseX - notchX(i)) < Math.abs(mouseX - notchX(best))) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean enabled = disabledReason == null;
        boolean hovered = hovered(mouseX, mouseY);
        int trackY = y + KNOB_HEIGHT / 2;
        int count = scales().size();
        graphics.fill(notchX(0), trackY, notchX(count - 1) + 1, trackY + 2, Theme.TRACK);
        for (int i = 0; i < count; i++) {
            int notchX = notchX(i);
            graphics.fill(notchX, trackY - 2, notchX + 1, trackY + 4, NOTCH);
        }
        int knobX = notchX(selected) - KNOB_WIDTH / 2;
        int knobOutline = pressed ? Theme.TEXT : hovered && enabled ? Theme.GOLD_LIGHT : 0xFF000000;
        Draw.panel(graphics, knobX, y, KNOB_WIDTH, KNOB_HEIGHT, enabled ? Theme.GOLD : Theme.CONTROL_OUTLINE,
                knobOutline);
        drawLabels(graphics, font, enabled);
        if (hovered && disabledReason != null) {
            UiTooltip.set(disabledReason);
        }
    }

    private void drawLabels(GuiGraphics graphics, Font font, boolean enabled) {
        int count = scales().size();
        int labelY = y + KNOB_HEIGHT + LABEL_GAP;
        int[] lefts = new int[count];
        int[] widths = new int[count];
        boolean fits = true;
        for (int i = 0; i < count; i++) {
            widths[i] = font.width(label(i));
            lefts[i] = labelLeft(i, widths[i]);
            if (i > 0 && lefts[i] < lefts[i - 1] + widths[i - 1] + 4) {
                fits = false;
            }
        }
        for (int i = 0; i < count; i++) {
            if (!fits && i != selected) {
                continue;
            }
            int colour = !enabled ? Theme.FAINT : i == selected ? Theme.GOLD : Theme.MUTED;
            graphics.drawString(font, label(i), lefts[i], labelY, colour, false);
        }
    }

    // The end labels line up with the slider's edges; the others are centred on their notch
    private int labelLeft(int index, int labelWidth) {
        int left;
        if (index == 0) {
            left = x;
        } else if (index == scales().size() - 1) {
            left = x + width - labelWidth;
        } else {
            left = notchX(index) - labelWidth / 2;
        }
        return Math.max(x, Math.min(x + width - labelWidth, left));
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressed = false;
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (disabledReason == null) {
            pressed = true;
            choose(nearest(click.x()));
        }
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (!pressed) {
            return false;
        }
        choose(nearest(click.x()));
        return true;
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean used = pressed;
        pressed = false;
        return used;
    }

    @Override
    public boolean key(UiInput.Key key) {
        // Keys reach every node when none has the keyboard; arrows move only a slider that has it
        if (disabledReason != null || !CreatorScreen.hasKeyboard(this)) {
            return false;
        }
        if (key.is(InputConstants.KEY_LEFT)) {
            choose(selected - 1);
            return true;
        }
        if (key.is(InputConstants.KEY_RIGHT)) {
            choose(selected + 1);
            return true;
        }
        return false;
    }

    private void choose(int index) {
        int next = clampIndex(index);
        if (next != selected) {
            selected = next;
            onChange.accept(next);
        }
    }
}
