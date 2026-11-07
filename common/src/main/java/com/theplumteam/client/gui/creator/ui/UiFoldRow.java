package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A row of a settings list that opens something, as Quick Skin's settings rows do: the label, a
 * faint hint after it (such as the value it holds) and a chevron at the right, a hairline under
 * the row, lighter under the mouse. An optional dot before the label carries a colour, such as a
 * rarity's or a change waiting. A row that runs an action at once has no chevron, and one that
 * takes something away has its label in red. A hint that does not fit gives way to its short
 * form, when it has one, or is cut; a row that keeps its hint, such as a count, cuts its label
 * instead. Whatever is cut or shortened shows whole in the row's tooltip.
 *
 * <p>A click or Enter runs the row's action; an action told the row's place on the screen can
 * anchor a menu to it, even inside a {@link UiScrollPane}. A disabled row is drawn in the
 * disabled grey, says why in its tooltip and still takes its click, so nothing under it reacts.
 */
public class UiFoldRow extends UiNode.Box {
    public static final int HEIGHT = 24;
    private static final int ROW_HOVER = 0xFF35363A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int INSET = 6;
    private static final int DOT_ROOM = 10;
    private static final int HINT_GAP = 6;
    private static final int CHEVRON_INSET = 10;
    // A hint is only worth showing with this much room
    private static final int MIN_HINT = 20;

    private Component label;
    @Nullable
    private Component hint;
    @Nullable
    private Component shortHint;
    private boolean keepHint;
    private int hintColour = Theme.FAINT;
    private final Consumer<Rect> action;
    private boolean chevron = true;
    private boolean danger;
    private boolean lineAbove;
    private int dot;
    @Nullable
    private Component dotTooltip;
    @Nullable
    private Component tooltip;
    @Nullable
    private Component disabledReason;

    /** A row whose action needs nothing but to run. */
    public UiFoldRow(Component label, @Nullable Component hint, Runnable action) {
        this(label, hint, (Consumer<Rect>) anchor -> action.run());
    }

    /**
     * A row whose action is told the row's place on the screen, to anchor a menu to it.
     */
    public UiFoldRow(Component label, @Nullable Component hint, Consumer<Rect> action) {
        this.label = label;
        this.hint = hint;
        this.action = action;
        this.height = HEIGHT;
    }

    public UiFoldRow setLabel(Component label) {
        this.label = label;
        return this;
    }

    /** The faint text after the label, such as its value; null for none. */
    public UiFoldRow hint(@Nullable Component hint) {
        return hint(hint, Theme.FAINT);
    }

    /** A hint in a colour of its own, such as red for a problem the sub-view fixes. */
    public UiFoldRow hint(@Nullable Component hint, int colour) {
        this.hint = hint;
        this.shortHint = null;
        this.hintColour = colour;
        return this;
    }

    /**
     * A hint with a short form shown where the whole does not fit, such as "12 %" for "12 % per
     * draw"; an empty short form leaves the hint out there.
     */
    public UiFoldRow hint(@Nullable Component hint, @Nullable Component shortHint) {
        this.hint = hint;
        this.shortHint = shortHint;
        this.hintColour = Theme.FAINT;
        return this;
    }

    /** Whether the hint keeps its room and the label is cut instead, as for a short value such as a count. */
    public UiFoldRow keepHint(boolean keep) {
        this.keepHint = keep;
        return this;
    }

    /** Whether the row opens a sub-view, with a chevron; false for an action that runs at once. */
    public UiFoldRow chevron(boolean chevron) {
        this.chevron = chevron;
        return this;
    }

    /** Draws the label in red, for an action that takes something away. */
    public UiFoldRow danger(boolean danger) {
        this.danger = danger;
        return this;
    }

    /** Draws a hairline above the row too, for the first row of a list. */
    public UiFoldRow lineAbove(boolean lineAbove) {
        this.lineAbove = lineAbove;
        return this;
    }

    /** A small dot before the label in a colour, with a tooltip; 0 for none. */
    public UiFoldRow dot(int colour, @Nullable Component tooltip) {
        this.dot = colour;
        this.dotTooltip = tooltip;
        return this;
    }

    /** A tooltip for the row while it can be used, such as a note on what it does. */
    public UiFoldRow tooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** Disables the row and says why in its tooltip; null enables it again. */
    public UiFoldRow disable(@Nullable Component reason) {
        this.disabledReason = reason;
        return this;
    }

    public boolean enabled() {
        return disabledReason == null;
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean over = hovered(mouseX, mouseY);
        boolean enabled = enabled();
        boolean lit = over && enabled;
        if (lit) {
            graphics.fill(x, y, x + width, y + height - 1, ROW_HOVER);
        }
        if (lineAbove) {
            graphics.fill(x, y - 1, x + width, y, Surfaces.ROW_LINE);
        }
        graphics.fill(x, y + height - 1, x + width, y + height, Surfaces.ROW_LINE);
        int middle = y + (height - 1) / 2;
        int textX = x + INSET;
        boolean overDot = false;
        if (dot != 0) {
            Draw.dot(graphics, textX + 2, middle, 5, enabled ? dot : Theme.TEXT_DISABLED);
            overDot = over && mouseX < textX + DOT_ROOM - 2;
            textX += DOT_ROOM;
        }
        int end = x + width - CHEVRON_INSET;
        if (chevron) {
            int chevronX = end - PixelIcon.width(UiSideRow.CHEVRON_RIGHT);
            PixelIcon.draw(graphics, UiSideRow.CHEVRON_RIGHT, chevronX, middle - 2,
                    !enabled ? Theme.TEXT_DISABLED : lit ? Theme.TEXT : Theme.MUTED);
            end = chevronX - 8;
        }
        int labelColour = !enabled ? Theme.TEXT_DISABLED : danger ? Theme.RED : lit ? Theme.TEXT : TEXT;
        String whole = label.getString();
        String wholeHint = hint != null ? hint.getString() : "";
        int labelWidth = UiText.width(font, whole);
        // The hint to show: the whole where it fits after the whole label, else the short form
        String hintText = wholeHint;
        if (hint != null && shortHint != null
                && textX + labelWidth + HINT_GAP + UiText.width(font, wholeHint) > end) {
            hintText = shortHint.getString();
        }
        int labelRoom = Math.max(0, end - textX);
        if (keepHint && !hintText.isEmpty()) {
            labelRoom = Math.max(0, Math.min(labelRoom, end - textX - HINT_GAP - UiText.width(font, hintText)));
        }
        String shown = UiText.fit(font, whole, labelRoom);
        UiText.draw(graphics, font, shown, textX, middle - 4, labelColour);
        boolean hintWhole = hintText.equals(wholeHint);
        if (!hintText.isEmpty()) {
            int hintX = textX + UiText.width(font, shown) + HINT_GAP;
            if (hintX < end - MIN_HINT || keepHint) {
                String hintShown = UiText.fit(font, hintText, Math.max(0, end - hintX));
                hintWhole &= hintShown.equals(hintText);
                UiText.draw(graphics, font, hintShown, hintX, middle - 4, enabled ? hintColour : Theme.TEXT_DISABLED);
            } else {
                hintWhole = false;
            }
        }
        if (over) {
            Component cut = !shown.equals(whole) || !hintWhole
                    ? (hint != null && !wholeHint.isEmpty() ? CreatorScreen.tr("card.joined", label, hint) : label) : null;
            Component tip = !enabled ? disabledReason : overDot && dotTooltip != null ? dotTooltip
                    : tooltip != null ? tooltip : cut;
            if (tip != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(tip));
            }
        }
    }

    // Input

    /** Runs the row's action as a click would, if it can be used; for Enter. */
    public boolean press() {
        if (!visible() || !enabled()) {
            return false;
        }
        UiSounds.play(ModSounds.CLICK);
        action.accept(UiScrollPane.onScreen(bounds()));
        return true;
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        // A disabled row still takes its click, so nothing under it reacts
        press();
        return true;
    }

    @Override
    public boolean key(UiInput.Key key) {
        return key.enter() && CreatorScreen.hasKeyboard(this) && press();
    }
}
