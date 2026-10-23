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
 * items. It is drawn as the menus' dialogs are, a charcoal face in a grey border and a black
 * outline, and the row under the mouse or the arrow keys is on the slate of a selected tab.
 * {@link CreatorScreen#openMenu} shows it above everything but a modal; any click outside
 * it closes it, and so do Escape and choosing an item. A menu taller than the screen, such as a
 * dropdown of many choices, is cut to the screen and scrolls with the wheel and the arrow keys.
 */
public class UiMenu implements UiNode {
    private static final int ROW = 15;
    private static final int SEPARATOR = 5;
    private static final int PADDING = 6;
    private static final int MIN_WIDTH = 100;
    // The outline and the border around the rows
    private static final int EDGE = 2;
    private static final int LOCK = 0xFF9A9A9A;

    private record Item(Component label, @Nullable Component hint, int colour, @Nullable Runnable action,
                        @Nullable Component disabledReason, boolean locked, boolean separator) {
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

    public UiMenu item(Component label, Runnable action) {
        items.add(new Item(label, null, Theme.TEXT, action, null, false, false));
        return this;
    }

    /** An item with a grey hint after its label, such as "for testing". */
    public UiMenu item(Component label, Component hint, Runnable action) {
        items.add(new Item(label, hint, Theme.TEXT, action, null, false, false));
        return this;
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
     * screen. {@link CreatorScreen#openMenu} calls this.
     */
    public void place(Rect anchor, int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        int widest = MIN_WIDTH;
        int total = 2 * EDGE;
        for (Item item : items) {
            total += item.separator() ? SEPARATOR : ROW;
            int itemWidth = font.width(item.label()) + 2 * PADDING;
            if (item.hint() != null) {
                itemWidth += 8 + font.width(item.hint());
            }
            if (item.locked()) {
                itemWidth += 8 + lockBadgeWidth(font);
            }
            widest = Math.max(widest, itemWidth);
        }
        this.width = Math.min(widest, screenWidth - 8);
        this.contentHeight = total;
        this.height = Math.min(total, Math.max(ROW + 2 * EDGE, screenHeight - 8));
        this.scroll = 0;
        revealHighlighted();
        this.x = Math.max(4, Math.min(anchor.x(), screenWidth - width - 4));
        int below = anchor.bottom() + 2;
        this.y = below + height <= screenHeight - 4 ? below : Math.max(4, anchor.y() - height - 2);
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
            int rowHeight = items.get(i).separator() ? SEPARATOR : ROW;
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
            if (i == highlighted && item.usable()) {
                graphics.fill(x + EDGE, top, x + width - EDGE, top + ROW, Theme.MENU_HOVER);
                graphics.fill(x + EDGE, top, x + EDGE + 2, top + ROW, Theme.HIGHLIGHT_BAR);
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
            Draw.text(graphics, font, item.label().getString(), x + PADDING, textY, Math.max(0, right - x - PADDING),
                    item.colour());
            if (i == hoveredIndex && item.disabledReason() != null) {
                UiTooltip.set(item.disabledReason());
            }
            top += ROW;
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
            top += items.get(i).separator() ? SEPARATOR : ROW;
        }
        int room = height - 2 * EDGE;
        if (top < scroll) {
            scrollTo(top);
        } else if (top + ROW > scroll + room) {
            scrollTo(top + ROW - room);
        }
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
