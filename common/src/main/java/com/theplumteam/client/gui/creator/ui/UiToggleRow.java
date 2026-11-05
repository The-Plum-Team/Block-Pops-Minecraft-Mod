package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.ToggleRow;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * An on/off setting as a row of a settings list, drawn as {@link ToggleRow} draws its own: the
 * label on the left with an optional faint hint after it, the toggle at the right, a hairline
 * under the row and a lighter row under the mouse. What the setting does is the label's tooltip,
 * never a second line. A click on the row, or Space or Enter while it has the keyboard, flips it
 * with a click.
 *
 * <p>It is a node rather than the vanilla widget, so it scrolls in a {@link UiScrollPane}, draws
 * above the screen's other nodes in order and is left alone by a dialog. It can hold content
 * that unfolds under it, such as the steppers of rarity weights set by hand: the nodes added to
 * it, placed from {@link #contentTop}, and an optional note under them. Whether that content is
 * open is a field of the row, which turning the toggle on opens and turning it off closes, and
 * which the screen sets itself when it builds the row ({@link #setOpen}), never derived from the
 * value on every rebuild. The row's bounds take the open content in, with the hairline under it.
 */
public class UiToggleRow extends UiNode.Box {
    public static final int HEIGHT = ToggleRow.HEIGHT;
    private static final int ROW_HOVER = 0xFF35363A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int INSET = 6;
    private static final int DOT_ROOM = 10;
    private static final int HINT_GAP = 6;
    private static final int TOGGLE_WIDTH = 30;
    private static final int TOGGLE_INSET = 10;
    private static final int MIN_HINT = 20;
    // The room under the open content, above the hairline
    private static final int CONTENT_BOTTOM = 6;
    private static final int NOTE_LINES = 2;

    private final Component label;
    private boolean value;
    private final Consumer<Boolean> onChange;
    @Nullable
    private Component hint;
    @Nullable
    private Component tooltip;
    @Nullable
    private Component disabledReason;
    private int dot;
    @Nullable
    private Component dotTooltip;
    private boolean lineAbove;
    private boolean open;
    private final List<UiNode> content = new ArrayList<>();
    @Nullable
    private Component note;
    @Nullable
    private UiNode pressedChild;
    @Nullable
    private UiNode focusedChild;

    /**
     * @param onChange told the new value each time the operator flips the toggle
     */
    public UiToggleRow(Component label, boolean value, Consumer<Boolean> onChange) {
        this.label = label;
        this.value = value;
        this.onChange = onChange;
        this.height = HEIGHT;
    }

    /** The faint text after the label, such as the value the setting falls back to. */
    public UiToggleRow hint(@Nullable Component hint) {
        this.hint = hint;
        return this;
    }

    /** What the setting does, shown while the pointer is on the label. */
    public UiToggleRow tooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** A small dot before the label in a colour, with a tooltip, as a change waiting; 0 for none. */
    public UiToggleRow dot(int colour, @Nullable Component tooltip) {
        this.dot = colour;
        this.dotTooltip = tooltip;
        return this;
    }

    /** Draws a hairline above the row too, for the first row of a list. */
    public UiToggleRow lineAbove(boolean lineAbove) {
        this.lineAbove = lineAbove;
        return this;
    }

    /** Disables the toggle and says why in its tooltip; null enables it again. */
    public UiToggleRow disable(@Nullable Component reason) {
        this.disabledReason = reason;
        return this;
    }

    public boolean enabled() {
        return disabledReason == null;
    }

    public boolean value() {
        return value;
    }

    /** Sets the toggle without telling the listener, as when the setting changed elsewhere. */
    public void setValue(boolean value) {
        this.value = value;
    }

    // The content that unfolds

    /** Whether the content under the row is shown. */
    public boolean isOpen() {
        return open;
    }

    /** Shows or hides the content under the row, without touching the value. */
    public void setOpen(boolean open) {
        this.open = open;
    }

    /** Where the content under the row starts, for the screen to place its nodes from. */
    public int contentTop() {
        return y + HEIGHT;
    }

    /** Adds a node to the content that unfolds under the row, placed by the screen. */
    public <T extends UiNode> T add(T node) {
        content.add(node);
        return node;
    }

    /** A muted note under the content, wrapped to two lines, such as what the values mean; null for none. */
    public UiToggleRow note(@Nullable Component note) {
        this.note = note;
        return this;
    }

    private boolean showsContent() {
        return open && (!content.isEmpty() || note != null);
    }

    // The bottom of the content's nodes, before the note
    private int nodesBottom() {
        int bottom = contentTop();
        for (UiNode node : content) {
            if (node.visible()) {
                bottom = Math.max(bottom, node.bounds().bottom());
            }
        }
        return bottom;
    }

    private int noteLines(Font font) {
        return note == null ? 0 : Math.min(NOTE_LINES, Draw.wrap(font, note.getString(), noteWidth()).size());
    }

    private int noteWidth() {
        return Math.max(1, width - 2 * INSET);
    }

    /** The row's height with its content when that is open. */
    public int totalHeight() {
        if (!showsContent()) {
            return HEIGHT;
        }
        Font font = Minecraft.getInstance().font;
        int bottom = nodesBottom();
        int lines = noteLines(font);
        if (lines > 0) {
            bottom += 4 + lines * Theme.LINE_HEIGHT - 2;
        }
        return bottom + CONTENT_BOTTOM - y;
    }

    @Override
    public Rect bounds() {
        return new Rect(x, y, width, totalHeight());
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean enabled = enabled();
        boolean overRow = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + HEIGHT;
        boolean lit = overRow && enabled;
        int total = totalHeight();
        if (lit) {
            graphics.fill(x, y, x + width, y + HEIGHT - (showsContent() ? 0 : 1), ROW_HOVER);
        }
        if (lineAbove) {
            graphics.fill(x, y - 1, x + width, y, Surfaces.ROW_LINE);
        }
        graphics.fill(x, y + total - 1, x + width, y + total, Surfaces.ROW_LINE);

        int middle = y + (HEIGHT - 1) / 2;
        int trackLeft = x + width - TOGGLE_INSET - TOGGLE_WIDTH;
        ToggleRow.drawToggle(graphics, trackLeft, middle, value, enabled, lit);
        int textX = x + INSET;
        boolean overDot = false;
        if (dot != 0) {
            Draw.dot(graphics, textX + 2, middle, 5, enabled ? dot : Theme.TEXT_DISABLED);
            overDot = overRow && mouseX < textX + DOT_ROOM - 2;
            textX += DOT_ROOM;
        }
        int end = trackLeft - 8;
        String whole = label.getString();
        String shown = UiText.fit(font, whole, Math.max(0, end - textX));
        UiText.draw(graphics, font, shown, textX, middle - 4,
                !enabled ? Theme.TEXT_DISABLED : lit ? Theme.TEXT : TEXT);
        int labelRight = textX + UiText.width(font, shown);
        if (hint != null) {
            int hintX = labelRight + HINT_GAP;
            if (hintX < end - MIN_HINT) {
                UiText.drawFitted(graphics, font, hint.getString(), hintX, middle - 4, end - hintX,
                        enabled ? Theme.FAINT : Theme.TEXT_DISABLED);
            }
        }
        if (overRow) {
            Component tip = !enabled ? disabledReason : overDot && dotTooltip != null ? dotTooltip
                    : tooltip != null ? tooltip : !shown.equals(whole) ? label : null;
            if (tip != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(tip));
            }
        }

        if (showsContent()) {
            for (UiNode node : content) {
                if (node.visible()) {
                    node.draw(graphics, mouseX, mouseY, partialTick);
                }
            }
            int lines = noteLines(font);
            if (lines > 0) {
                Draw.lines(graphics, font, note.getString(), x + INSET, nodesBottom() + 4, noteWidth(), lines,
                        Theme.MUTED);
            }
        }
    }

    // Input

    /** Flips the toggle as a click would, if it can be used, opening or closing its content with it. */
    public boolean flip() {
        if (!visible() || !enabled()) {
            return false;
        }
        UiSounds.play(ModSounds.CLICK);
        value = !value;
        open = value;
        onChange.accept(value);
        return true;
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressedChild = null;
        if (!visible() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (click.y() < y + HEIGHT) {
            focusedChild = null;
            if (click.left()) {
                // A disabled row still takes its click, so nothing under it reacts
                flip();
            }
            return true;
        }
        if (showsContent()) {
            for (int i = content.size() - 1; i >= 0; i--) {
                UiNode node = content.get(i);
                if (node.visible() && node.click(click)) {
                    pressedChild = node;
                    focusedChild = node;
                    return true;
                }
            }
        }
        focusedChild = null;
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        return pressedChild != null && pressedChild.drag(click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        UiNode pressed = pressedChild;
        pressedChild = null;
        return pressed != null && pressed.release(click);
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (!showsContent()) {
            return false;
        }
        for (int i = content.size() - 1; i >= 0; i--) {
            UiNode node = content.get(i);
            if (node.isMouseOver(mouseX, mouseY) && node.scroll(mouseX, mouseY, amount)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean key(UiInput.Key key) {
        UiNode focused = focusedChild();
        if (focused != null && focused.key(key)) {
            return true;
        }
        if (focused == null && CreatorScreen.hasKeyboard(this)
                && (key.enter() || key.is(InputConstants.KEY_SPACE))) {
            return flip();
        }
        return false;
    }

    @Override
    public boolean typed(String text) {
        UiNode focused = focusedChild();
        return focused != null && focused.typed(text);
    }

    @Override
    @Nullable
    public UiNode focusedChild() {
        return showsContent() && focusedChild != null && content.contains(focusedChild) && focusedChild.visible()
                ? focusedChild : null;
    }

    @Override
    public void tick() {
        for (UiNode node : List.copyOf(content)) {
            node.tick();
        }
    }

    @Override
    @Nullable
    public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        if (!showsContent()) {
            return null;
        }
        UiDropZone under = null;
        for (int i = content.size() - 1; i >= 0; i--) {
            UiNode node = content.get(i);
            if (!node.visible()) {
                continue;
            }
            UiDropZone found = node.dropZones(mouseX, mouseY, zones);
            if (under == null) {
                under = found;
            }
        }
        return under;
    }
}
