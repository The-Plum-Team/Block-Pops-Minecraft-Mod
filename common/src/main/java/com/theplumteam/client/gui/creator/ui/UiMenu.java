package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A popup list of actions under the node that opened it, as a card's "..." menu or a dropdown's
 * choices. An item can be shown in a warning colour, carry a grey hint after its label, be
 * disabled with a reason in its tooltip, or be locked behind a "SOON" badge. Separators group
 * items, and a group of radio items says which of several views is the one shown with a check
 * before it; a radio item may carry a hint too, or be disabled with its reason, and any item may
 * carry a tooltip that says more about it, or a faint note on a second line of its own, which
 * nothing covers. It is drawn as the menus' dialogs are, a charcoal face in a grey border and a
 * black outline, and the row under the mouse or the arrow keys is on the slate of a selected tab.
 * {@link CreatorScreen#openMenu} shows it above everything but a modal; any click outside
 * it closes it, and so do Escape and choosing an item. A menu taller than the screen, or than the
 * panel it is kept {@link #within}, such as a dropdown of many choices, is cut to it and scrolls
 * with the wheel and the arrow keys.
 */
public class UiMenu implements UiNode {
    private static final int ROW = 15;
    // The second line of an item with a note
    private static final int NOTE_ROW = 10;
    private static final int SEPARATOR = 5;
    private static final int PADDING = 6;
    private static final int MIN_WIDTH = 100;
    // The outline and the border around the rows
    private static final int EDGE = 2;
    private static final int LOCK = 0xFF9A9A9A;
    // The check of a chosen radio item, and the room it leaves before every radio item's label
    private static final String CHECK = "\u2714";
    private static final int CHECK_GAP = 4;

    /** Whether an item is a radio item, and whether it is the chosen one. */
    private enum Radio {
        NONE, CHOSEN, OTHER
    }

    private record Item(Component label, @Nullable Component hint, int colour, @Nullable Runnable action,
                        @Nullable Component disabledReason, boolean locked, boolean separator, Radio radio,
                        @Nullable Component tooltip, @Nullable Component note) {
        Item(Component label, @Nullable Component hint, int colour, @Nullable Runnable action,
             @Nullable Component disabledReason, boolean locked, boolean separator) {
            this(label, hint, colour, action, disabledReason, locked, separator, Radio.NONE, null, null);
        }

        Item withTooltip(@Nullable Component text) {
            return new Item(label, hint, colour, action, disabledReason, locked, separator, radio, text, note);
        }

        Item withNote(@Nullable Component text) {
            return new Item(label, hint, colour, action, disabledReason, locked, separator, radio, tooltip, text);
        }

        int height() {
            return separator ? SEPARATOR : note != null ? ROW + NOTE_ROW : ROW;
        }

        boolean usable() {
            return !separator && action != null && disabledReason == null && !locked;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int x;
    private int y;
    private int width;
    private int height;
    // The height of every row together, more than the menu's own when it scrolls
    private int contentHeight;
    private int scroll;
    private int highlighted = -1;
    @Nullable
    private Rect within;

    public UiMenu item(Component label, Runnable action) {
        items.add(new Item(label, null, Theme.TEXT, action, null, false, false));
        return this;
    }

    /** An item with a grey hint after its label, such as "for testing". */
    public UiMenu item(Component label, Component hint, Runnable action) {
        items.add(new Item(label, hint, Theme.TEXT, action, null, false, false));
        return this;
    }

    /**
     * An item of a group of which one is chosen, such as the view a preview shows: the chosen one
     * is led by a check in the text's colour, the others are indented as far, so the labels line
     * up. Choosing one runs its action, which picks it.
     */
    public UiMenu radio(Component label, boolean selected, Runnable action) {
        return radio(label, null, selected, action);
    }

    /** A radio item with a grey hint after its label, such as the chance a rarity would give. */
    public UiMenu radio(Component label, @Nullable Component hint, boolean selected, Runnable action) {
        items.add(new Item(label, hint, Theme.TEXT, action, null, false, false,
                selected ? Radio.CHOSEN : Radio.OTHER, null, null));
        return this;
    }

    /**
     * A radio item that cannot be chosen now, saying why in its tooltip, lined up with the others
     * of its group, such as a pose the model has not got; it still shows the check when chosen.
     */
    public UiMenu radioDisabled(Component label, boolean selected, Component reason) {
        items.add(new Item(label, null, Theme.FAINT, null, reason, false, false,
                selected ? Radio.CHOSEN : Radio.OTHER, null, null));
        return this;
    }

    /**
     * A tooltip for the item added last, shown while the pointer is over it, such as a note on
     * what a choice means; a disabled item shows its reason instead.
     */
    public UiMenu tooltip(@Nullable Component text) {
        if (!items.isEmpty()) {
            items.set(items.size() - 1, items.get(items.size() - 1).withTooltip(text));
        }
        return this;
    }

    /**
     * A faint note on a second line of the item added last, under its label, such as what a choice
     * means: unlike a tooltip it covers none of the menu's items.
     */
    public UiMenu note(@Nullable Component text) {
        if (!items.isEmpty()) {
            items.set(items.size() - 1, items.get(items.size() - 1).withNote(text));
        }
        return this;
    }

    /**
     * Keeps the menu inside a panel, such as the content panel a page's menus stay in: it opens
     * under its anchor or above it where the panel has room, over its anchor where it has room
     * only so, and is cut to the panel, scrolling, where it has none. Null keeps it on the screen
     * only.
     */
    public UiMenu within(@Nullable Rect panel) {
        this.within = panel;
        return this;
    }

    /** How tall the menu is with every row shown, as it would be with room for all of them. */
    public int fullHeight() {
        int total = 2 * EDGE;
        for (Item item : items) {
            total += item.height();
        }
        return total;
    }

    /** An item in the warning colour, for an action that takes something away. */
    public UiMenu warning(Component label, Runnable action) {
        items.add(new Item(label, null, Theme.ORANGE, action, null, false, false));
        return this;
    }

    /** An item that cannot be chosen now, saying why in its tooltip. */
    public UiMenu disabled(Component label, Component reason) {
        items.add(new Item(label, null, Theme.FAINT, null, reason, false, false));
        return this;
    }

    /** An item that is not available yet, with a lock and "SOON" after its label. */
    public UiMenu locked(Component label) {
        items.add(new Item(label, null, Theme.FAINT, null, null, true, false));
        return this;
    }

    /** A grey line that cannot be chosen, such as the question a menu answers. */
    public UiMenu heading(Component label) {
        items.add(new Item(label, null, Theme.MUTED, null, null, false, false));
        return this;
    }

    public UiMenu separator() {
        items.add(new Item(Component.empty(), null, 0, null, null, false, true));
        return this;
    }

    /**
     * Highlights an item, counted from 0 over every item, heading and separator added, so Enter
     * chooses it and a scrolling menu opens with it in sight; a dropdown highlights its value.
     */
    public UiMenu highlight(int index) {
        if (index >= 0 && index < items.size() && items.get(index).usable()) {
            highlighted = index;
        }
        return this;
    }

    /**
     * Places the menu under an anchor, or above it when there is no room below, kept on the
     * screen and inside its panel when it has one ({@link #within}); where neither side has room,
     * on the side with more, cut to it. {@link CreatorScreen#openMenu} calls this.
     */
    public void place(Rect anchor, int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        int widest = MIN_WIDTH;
        for (Item item : items) {
            int indent = item.radio() != Radio.NONE ? checkRoom(font) : 0;
            int itemWidth = font.width(item.label()) + 2 * PADDING + indent;
            if (item.hint() != null) {
                itemWidth += 8 + font.width(item.hint());
            }
            if (item.locked()) {
                itemWidth += 8 + lockBadgeWidth(font);
            }
            if (item.note() != null) {
                itemWidth = Math.max(itemWidth, font.width(item.note()) + 2 * PADDING + indent);
            }
            widest = Math.max(widest, itemWidth);
        }
        int total = fullHeight();
        this.contentHeight = total;
        this.scroll = 0;
        if (within == null) {
            this.width = Math.min(widest, screenWidth - 8);
            this.height = Math.min(total, Math.max(ROW + 2 * EDGE, screenHeight - 8));
            revealHighlighted();
            this.x = Math.max(4, Math.min(anchor.x(), screenWidth - width - 4));
            int below = anchor.bottom() + 2;
            this.y = below + height <= screenHeight - 4 ? below : Math.max(4, anchor.y() - height - 2);
            return;
        }
        int left = Math.max(4, within.x() + 4);
        int top = Math.max(4, within.y() + 4);
        int right = Math.min(screenWidth - 4, within.right() - 4);
        int bottom = Math.min(screenHeight - 4, within.bottom() - 4);
        this.width = Math.max(0, Math.min(widest, right - left));
        int roomBelow = bottom - (anchor.bottom() + 2);
        int roomAbove = anchor.y() - 2 - top;
        this.x = Math.max(left, Math.min(anchor.x(), right - width));
        if (total > roomBelow && total > roomAbove && total <= bottom - top) {
            // Room on neither side but in the panel: as low as it fits, over its anchor
            this.height = total;
            this.y = Math.max(top, bottom - total);
            revealHighlighted();
            return;
        }
        boolean downward = total <= roomBelow || total > roomAbove && roomBelow >= roomAbove;
        this.height = Math.min(total, Math.max(ROW + 2 * EDGE, downward ? roomBelow : roomAbove));
        revealHighlighted();
        this.y = downward ? Math.max(top, Math.min(anchor.bottom() + 2, bottom - height))
                : Math.max(top, anchor.y() - height - 2);
    }

    @Override
    public Rect bounds() {
        return new Rect(x, y, width, height);
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!bounds().contains(mouseX, mouseY) || mouseY < y + EDGE || mouseY >= y + height - EDGE) {
            return -1;
        }
        int top = y + EDGE - scroll;
        for (int i = 0; i < items.size(); i++) {
            int rowHeight = items.get(i).height();
            if (mouseY >= top && mouseY < top + rowHeight) {
                return items.get(i).separator() ? -1 : i;
            }
            top += rowHeight;
        }
        return -1;
    }

    /** The action of the usable item under the pointer, or null. */
    @Nullable
    Runnable actionAt(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);
        return index >= 0 && items.get(index).usable() ? items.get(index).action() : null;
    }

    /** The action of the item chosen with the arrow keys, or null. */
    @Nullable
    Runnable highlightedAction() {
        return highlighted >= 0 && items.get(highlighted).usable() ? items.get(highlighted).action() : null;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + width, y + height, Surfaces.OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, Theme.MENU_OUTLINE);
        graphics.fill(x + EDGE, y + EDGE, x + width - EDGE, y + height - EDGE, Theme.MENU);
        int hoveredIndex = indexAt(mouseX, mouseY);
        if (hoveredIndex >= 0) {
            highlighted = hoveredIndex;
        }
        boolean scrolls = contentHeight > height;
        if (scrolls) {
            // The menu is drawn unmoved over everything, so the clip is in screen units
            graphics.enableScissor(x + EDGE, y + EDGE, x + width - EDGE, y + height - EDGE);
        }
        int top = y + EDGE - scroll;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.separator()) {
                graphics.fill(x + EDGE + 3, top + 2, x + width - EDGE - 3, top + 3, Theme.SEPARATOR);
                top += SEPARATOR;
                continue;
            }
            int rowHeight = item.height();
            if (i == highlighted && item.usable()) {
                graphics.fill(x + EDGE, top, x + width - EDGE, top + rowHeight, Theme.MENU_HOVER);
                graphics.fill(x + EDGE, top, x + EDGE + 2, top + rowHeight, Theme.HIGHLIGHT_BAR);
            }
            int textY = top + (ROW - 8) / 2;
            int right = x + width - PADDING;
            if (item.locked()) {
                right -= lockBadgeWidth(font);
                drawLockBadge(graphics, font, right, top + (ROW - 11) / 2);
                right -= 8;
            }
            if (item.hint() != null) {
                int hintWidth = font.width(item.hint());
                Draw.string(graphics, font, item.hint(), right - hintWidth, textY, Theme.FAINT);
                right -= hintWidth + 8;
            }
            int labelX = x + PADDING;
            if (item.radio() != Radio.NONE) {
                if (item.radio() == Radio.CHOSEN) {
                    Draw.string(graphics, font, CHECK, labelX, textY, Theme.TEXT);
                }
                labelX += checkRoom(font);
            }
            Draw.text(graphics, font, item.label().getString(), labelX, textY, Math.max(0, right - labelX),
                    item.colour());
            if (item.note() != null) {
                Draw.text(graphics, font, item.note().getString(), labelX, textY + NOTE_ROW,
                        Math.max(0, x + width - PADDING - labelX), Theme.FAINT);
            }
            if (i == hoveredIndex && item.disabledReason() != null) {
                UiTooltip.set(item.disabledReason());
            } else if (i == hoveredIndex && item.tooltip() != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(item.tooltip()));
            }
            top += rowHeight;
        }
        if (scrolls) {
            graphics.disableScissor();
            int track = height - 2 * EDGE;
            int thumb = Math.max(8, track * track / contentHeight);
            int thumbY = y + EDGE + (track - thumb) * scroll / Math.max(1, contentHeight - height);
            graphics.fill(x + width - EDGE - 2, thumbY, x + width - EDGE, thumbY + thumb, Theme.MENU_OUTLINE);
        }
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (contentHeight <= height) {
            return false;
        }
        scrollTo(scroll - (int) Math.round(amount * ROW));
        return true;
    }

    private void scrollTo(int position) {
        scroll = Math.max(0, Math.min(contentHeight - height, position));
    }

    // Scrolls as little as it takes to show the highlighted item
    private void revealHighlighted() {
        if (highlighted < 0) {
            return;
        }
        int top = 0;
        for (int i = 0; i < highlighted; i++) {
            top += items.get(i).height();
        }
        int rowHeight = items.get(highlighted).height();
        int room = height - 2 * EDGE;
        if (top < scroll) {
            scrollTo(top);
        } else if (top + rowHeight > scroll + room) {
            scrollTo(top + rowHeight - room);
        }
    }

    private static int checkRoom(Font font) {
        return font.width(CHECK) + CHECK_GAP;
    }

    private static Component soon() {
        return CreatorScreen.tr("soon");
    }

    private static int lockBadgeWidth(Font font) {
        return 7 + 3 + font.width(soon()) + 6;
    }

    /** A padlock made of fills and the word "SOON", in the mockup's small grey badge. */
    private static void drawLockBadge(GuiGraphics graphics, Font font, int left, int top) {
        int width = lockBadgeWidth(font);
        Draw.panel(graphics, left, top, width, 11, Theme.BADGE_SOON_BG, Surfaces.BORDER_DISABLED);
        int lockX = left + 3;
        // Shackle, then body
        graphics.fill(lockX + 1, top + 2, lockX + 4, top + 3, LOCK);
        graphics.fill(lockX, top + 3, lockX + 1, top + 5, LOCK);
        graphics.fill(lockX + 4, top + 3, lockX + 5, top + 5, LOCK);
        graphics.fill(lockX - 1, top + 5, lockX + 6, top + 9, LOCK);
        Draw.string(graphics, font, soon(), lockX + 7 + 3 - 1, top + 2, Theme.BADGE_SOON_FG);
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (key.is(InputConstants.KEY_DOWN) || key.is(InputConstants.KEY_UP)) {
            int step = key.is(InputConstants.KEY_DOWN) ? 1 : -1;
            for (int n = 1; n <= items.size(); n++) {
                int candidate = Math.floorMod(highlighted + step * n, items.size());
                if (items.get(candidate).usable()) {
                    highlighted = candidate;
                    break;
                }
            }
            revealHighlighted();
            return true;
        }
        return false;
    }
}
