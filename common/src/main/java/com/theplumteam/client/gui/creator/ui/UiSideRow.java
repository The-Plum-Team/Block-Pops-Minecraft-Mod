package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * One row of a creator sidebar, drawn as the settings dialog's tabs are ({@code TabButton}): the
 * slate of a selected tab with a brighter bar on its left while selected, lighter under the
 * mouse. Before its label comes a pixel icon, or a collection's own box, and at its right end one
 * of a count, a status (a small dot in a state's colour and a word), a change dot or a chevron. A
 * label too long for the row is cut, and its tooltip gives the whole of it; a status whose word
 * would leave the label too little room, as in a narrow sidebar, shows its dot alone and says its
 * word in the dot's tooltip.
 *
 * <p>A click selects the row with the tab sound, a double click is the row's {@link #onActivate}
 * and a right click opens its {@link #onMenu} at the pointer. In a {@link UiSideList} the list
 * keeps which row is selected and moves through the rows with the keyboard; a row on its own,
 * such as an editor's tab, is selected by the screen.
 *
 * <p>A box takes one of the screen's models ({@link SidebarScreen.ModelBudget}); a row given none
 * draws a swatch of the collection's colour in its place. A faint row, as an archived
 * collection's, is drawn in grey with its box washed out, and a locked row in the disabled grey,
 * saying why in its tooltip, while it swallows its clicks; a locked row that is selected keeps a
 * dimmed slate, so the selection stays in sight while it cannot change. The list's keyboard
 * cursor on a row that is not selected, such as a fold's, is a thin frame in the selection's bar
 * colour, apart from the lighter face the mouse gives.
 */
public class UiSideRow extends UiNode.Box {
    public static final int HEIGHT = 20;
    /** The chevrons of a fold: folded, then unfolded. */
    public static final String[] CHEVRON_RIGHT = {"#..", ".#.", "..#", ".#.", "#.."};
    public static final String[] CHEVRON_DOWN = {"#...#", ".#.#.", "..#.."};
    private static final int TEXT_HOVER = 0xFFCCCCCC;
    private static final int BOX_SIZE = 15;
    private static final int BOX_ROOM = 19;
    private static final int GLYPH_WIDTH = 11;
    private static final int GLYPH_ROOM = 17;
    private static final int TEXT_INSET = 8;
    private static final int END_INSET = 6;
    // A status leaves its label at least this much room, or shows its dot alone
    private static final int MIN_LABEL = 40;
    // Washes a box toward the sidebar's face, as an archived collection's band is washed out
    private static final int GREYED_WASH = 0xA0262729;
    // The selection's slate and bar at half their alpha, for a selected row of a locked list
    private static final int LOCKED_HIGHLIGHT = 0x80000000 | (Theme.HIGHLIGHT & 0x00FFFFFF);
    private static final int LOCKED_BAR = 0x80000000 | (Theme.HIGHLIGHT_BAR & 0x00FFFFFF);

    /** What the row's right end shows. */
    private enum End {
        NONE, COUNT, STATUS, DOT, CHEVRON
    }

    private final Object key;
    private Component label;
    @Nullable
    private String[] glyph;
    private ItemStack box = ItemStack.EMPTY;
    private int boxColour = Surfaces.FACE;
    private boolean greyed;
    private boolean selected;
    private boolean faint;
    // The list's keyboard cursor on a row that is not selected, such as a fold
    private boolean cursor;
    @Nullable
    private Component lockReason;
    private boolean modelAllowed = true;
    private End end = End.NONE;
    private Component endText = Component.empty();
    private int endColour = Theme.FAINT;
    // Whether a count keeps its own colour, rather than the faint one that brightens when selected
    private boolean countColoured;
    @Nullable
    private Component endTooltip;
    private boolean open;
    @Nullable
    private Component tooltip;
    @Nullable
    private Runnable onSelect;
    @Nullable
    private Runnable onActivate;
    @Nullable
    private Consumer<Rect> onMenu;
    // The list the row is in, which keeps the selection, or null for a row on its own
    @Nullable
    UiSideList list;

    /**
     * @param key what tells the row apart across a rebuild, such as a collection's id
     */
    public UiSideRow(Object key, Component label) {
        this.key = key;
        this.label = label;
        this.height = HEIGHT;
    }

    public Object key() {
        return key;
    }

    public Component label() {
        return label;
    }

    public UiSideRow setLabel(Component label) {
        this.label = label;
        return this;
    }

    // Leading icon

    /** A pixel icon before the label, centred in its own room. */
    public UiSideRow glyph(@Nullable String[] glyph) {
        this.glyph = glyph;
        return this;
    }

    /**
     * A collection's box before the label, from {@link UiViewport#boxStack}, and the colour of
     * the swatch drawn in its place when the screen has no model to spare for it.
     */
    public UiSideRow box(ItemStack box, int colour) {
        this.box = box;
        this.boxColour = colour;
        return this;
    }

    /** Whether the row has a box, which takes one of the screen's models. */
    public boolean hasBox() {
        return !box.isEmpty();
    }

    /** Lets the row draw its box, or only its swatch; {@link UiSideList} decides as it draws. */
    public void allowModel(boolean allowed) {
        this.modelAllowed = allowed;
    }

    // Right end

    /** A count at the right end, faint, or a little brighter while selected. */
    public UiSideRow count(Component count) {
        count(count, Theme.FAINT, null);
        this.countColoured = false;
        return this;
    }

    /** A count in a colour of its own, such as a red warning, with a tooltip. */
    public UiSideRow count(Component count, int colour, @Nullable Component tooltip) {
        this.end = End.COUNT;
        this.endText = count;
        this.endColour = colour;
        this.countColoured = true;
        this.endTooltip = tooltip;
        return this;
    }

    /** A status at the right end: a small dot in its colour and a grey word, such as a draft's. */
    public UiSideRow status(Component word, int dotColour, @Nullable Component tooltip) {
        this.end = End.STATUS;
        this.endText = word;
        this.endColour = dotColour;
        this.endTooltip = tooltip;
        return this;
    }

    /** A change dot at the right end, amber for changes waiting, red for one that cannot be sent. */
    public UiSideRow dot(int colour, @Nullable Component tooltip) {
        this.end = End.DOT;
        this.endColour = colour;
        this.endTooltip = tooltip;
        return this;
    }

    /** A chevron at the right end, pointing down while open. */
    public UiSideRow chevron(boolean open) {
        this.end = End.CHEVRON;
        this.open = open;
        return this;
    }

    /** Takes whatever the right end showed away. */
    public UiSideRow noEnd() {
        this.end = End.NONE;
        return this;
    }

    // State

    public boolean selected() {
        return selected;
    }

    public UiSideRow setSelected(boolean selected) {
        this.selected = selected;
        return this;
    }

    /** Draws the row in grey, its box washed out, as an archived collection's. */
    public UiSideRow faint(boolean faint) {
        this.faint = faint;
        this.greyed = faint;
        return this;
    }

    /** Locks the row, saying why in its tooltip, or unlocks it again with null. */
    public UiSideRow lock(@Nullable Component reason) {
        this.lockReason = reason;
        return this;
    }

    /** Whether the row is locked, by itself or by the list it is in. */
    public boolean locked() {
        return lockReason() != null;
    }

    // The row's own reason, else its list's
    @Nullable
    private Component lockReason() {
        return lockReason != null ? lockReason : list != null ? list.lockReason() : null;
    }

    /** A tooltip for the row, in place of the whole label a cut row shows. */
    public UiSideRow tooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    void setCursor(boolean cursor) {
        this.cursor = cursor;
    }

    // Actions

    /** What selecting the row does, by a click or the list's arrow keys. */
    public UiSideRow onSelect(@Nullable Runnable onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    /** What a double click or Enter on the row does, such as opening the collection. */
    public UiSideRow onActivate(@Nullable Runnable onActivate) {
        this.onActivate = onActivate;
        return this;
    }

    /** What a right click or the menu key does, told where on the screen to anchor a menu. */
    public UiSideRow onMenu(@Nullable Consumer<Rect> onMenu) {
        this.onMenu = onMenu;
        return this;
    }

    /** Selects the row as a click does, with the tab sound; a row in a list lets the list do it. */
    public void select() {
        if (locked()) {
            return;
        }
        if (list != null) {
            list.choose(this);
            return;
        }
        UiSounds.play(ModSounds.TAB);
        runSelect();
    }

    void runSelect() {
        if (onSelect != null) {
            onSelect.run();
        }
    }

    /** Runs the row's {@link #onActivate}, as a double click does. */
    public void activate() {
        if (!locked() && onActivate != null) {
            onActivate.run();
        }
    }

    /** Opens the row's menu at a point or rectangle on the screen, as a right click does. */
    public void openMenu(Rect anchor) {
        if (!locked() && onMenu != null) {
            onMenu.accept(anchor);
        }
    }

    boolean hasMenu() {
        return onMenu != null;
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean over = hovered(mouseX, mouseY);
        boolean locked = locked();
        boolean lit = !selected && !locked && over;
        if (selected) {
            graphics.fill(x, y, x + width, y + height, locked ? LOCKED_HIGHLIGHT : Theme.HIGHLIGHT);
            graphics.fill(x, y, x + 2, y + height, locked ? LOCKED_BAR : Theme.HIGHLIGHT_BAR);
        } else if (lit) {
            graphics.fill(x, y, x + width, y + height, Theme.HOVER);
        }
        if (cursor && !selected && !locked) {
            Draw.outline(graphics, x, y, width, height, Theme.HIGHLIGHT_BAR);
        }
        // A locked row is greyed, the selected one less so, on its dimmed slate
        int colour = locked ? (selected ? Theme.MUTED : Theme.TEXT_DISABLED) : selected ? Theme.TEXT
                : faint ? Theme.FAINT : lit ? TEXT_HOVER : Surfaces.TEXT_DIM;
        int middle = y + height / 2;
        int textX = x + TEXT_INSET;
        if (!box.isEmpty()) {
            drawBox(graphics, textX, middle);
            textX += BOX_ROOM;
        } else if (glyph != null) {
            PixelIcon.draw(graphics, glyph, textX + (GLYPH_WIDTH - PixelIcon.width(glyph)) / 2,
                    middle - glyph.length / 2, colour);
            textX += GLYPH_ROOM;
        }
        boolean dotOnly = end == End.STATUS && statusLeft(font) - textX < MIN_LABEL;
        int endLeft = drawEnd(graphics, font, middle, colour, over, dotOnly);
        String whole = label.getString();
        String cut = UiText.fit(font, whole, Math.max(0, endLeft - textX));
        UiText.draw(graphics, font, cut, textX, middle - 4, colour);
        if (over) {
            Component tip = locked() ? lockReason() : tooltip != null ? tooltip : !cut.equals(whole) ? label : null;
            Component endTip = dotOnly && endTooltip == null ? endText : endTooltip;
            if (endTip != null && mouseX >= endLeft) {
                UiTooltip.set(SidebarScreen.tooltipLines(endTip));
            } else if (tip != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(tip));
            }
        }
    }

    // Where the label's room ends before a status drawn whole
    private int statusLeft(Font font) {
        return x + width - END_INSET - UiText.width(font, endText.getString()) - 4 - 4 - END_INSET;
    }

    // The box, or the swatch the budget leaves in its place, washed out when faint
    private void drawBox(GuiGraphics graphics, int left, int middle) {
        int top = middle - BOX_SIZE / 2;
        if (!modelAllowed) {
            int colour = greyed ? grey(boxColour) : boxColour;
            graphics.fill(left, top, left + BOX_SIZE, top + BOX_SIZE, colour);
            Draw.outline(graphics, left, top, BOX_SIZE, BOX_SIZE, Surfaces.BORDER);
            if (greyed) {
                graphics.fill(left, top, left + BOX_SIZE, top + BOX_SIZE, GREYED_WASH);
            }
            return;
        }
        UiViewport.drawBoxFront(graphics, box, 0.0f, 0, left + BOX_SIZE / 2.0f, middle, BOX_SIZE);
        if (greyed) {
            // Above the model: a later stratum from 1.21.6, depth before it
            GuiPose.flush(graphics);
            GuiPose.nextStratum(graphics);
            GuiPose.push(graphics);
            GuiPose.lift(graphics, 400.0f);
            graphics.fill(left - 1, top - 1, left + BOX_SIZE + 1, top + BOX_SIZE + 1, GREYED_WASH);
            GuiPose.pop(graphics);
        }
    }

    /** Draws the right end and returns where the label's room ends, 6 before it. */
    private int drawEnd(GuiGraphics graphics, Font font, int middle, int textColour, boolean over, boolean dotOnly) {
        int right = x + width - END_INSET;
        switch (end) {
            case COUNT -> {
                int colour = countColoured ? endColour : selected ? Theme.TEXT_SOFT : Theme.FAINT;
                int countWidth = UiText.width(font, endText.getString());
                Draw.string(graphics, font, endText, right - countWidth, middle - 4, lockedColour(colour));
                return right - countWidth - END_INSET;
            }
            case STATUS -> {
                if (dotOnly) {
                    Draw.dot(graphics, right - 2, middle, 4, lockedColour(endColour));
                    return right - 4 - END_INSET;
                }
                int wordWidth = UiText.width(font, endText.getString());
                Draw.string(graphics, font, endText, right - wordWidth, middle - 4, lockedColour(Theme.FAINT));
                int dotRight = right - wordWidth - 4;
                Draw.dot(graphics, dotRight - 2, middle, 4, lockedColour(endColour));
                return dotRight - 4 - END_INSET;
            }
            case DOT -> {
                Draw.dot(graphics, right - 3, middle, 5, endColour);
                return right - 5 - END_INSET;
            }
            case CHEVRON -> {
                String[] chevron = open ? CHEVRON_DOWN : CHEVRON_RIGHT;
                int chevronX = right - PixelIcon.width(chevron);
                PixelIcon.draw(graphics, chevron, chevronX, middle - chevron.length / 2, over ? Theme.TEXT : textColour);
                return chevronX - END_INSET;
            }
            default -> {
                return right;
            }
        }
    }

    // A colour of the right end, greyed while locked: a little less on the selected row's slate
    private int lockedColour(int colour) {
        if (!locked()) {
            return colour;
        }
        return selected ? Theme.FAINT : Theme.TEXT_DISABLED;
    }

    private static int grey(int colour) {
        int value = (((colour >> 16) & 0xFF) * 30 + ((colour >> 8) & 0xFF) * 59 + (colour & 0xFF) * 11) / 100;
        return 0xFF000000 | value << 16 | value << 8 | value;
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !isMouseOver(click.x(), click.y()) || !(click.left() || click.right())) {
            return false;
        }
        if (locked()) {
            // A locked row still takes its click, so nothing under it reacts
            return true;
        }
        if (click.right()) {
            openMenu(UiScrollPane.onScreen(new Rect((int) click.x(), (int) click.y(), 0, 0)));
            return true;
        }
        if (click.doubleClick()) {
            if (list != null) {
                list.activateRow(this);
            } else {
                activate();
            }
            return true;
        }
        select();
        return true;
    }
}
