package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A row of tabs. Folder tabs stand on a panel's top edge with an item icon before each label,
 * in the look of the machine screen's tabs: the selected one is filled like the panel and leaves
 * its bottom open, and the panel leaves its own edge out under it (see {@link #selectedLeft()}).
 * Flat tabs are Quick Skin's settings tabs laid side by side: plain labels that lighten under the
 * mouse, the selected one on a slate highlight with a brighter bar along its bottom. A label may
 * carry a count, as "Drafts (2)", and a green tick for a finished step. Given less width than
 * they need, the tabs narrow in proportion and cut their labels.
 */
public class UiTabs extends UiNode.Box {
    /** How the tabs look. */
    public enum Style {
        FOLDER,
        FLAT
    }

    /** One tab: a count below zero shows none, and an empty icon leaves the icon out. */
    public record Tab(Component label, ItemStack icon, int count, boolean done) {
    }

    public static final int FOLDER_HEIGHT = 20;
    public static final int FLAT_HEIGHT = 19;
    // An unselected folder tab sits lower, behind the panel
    private static final int UNSELECTED_DROP = 3;
    private static final int ICON_SIZE = 16;
    private static final float UNSELECTED_ICON_SCALE = 0.75f;
    private static final int PADDING = 5;
    private static final int FLAT_PADDING = 7;
    private static final int GAP = 4;
    private static final String TICK = "✔ ";

    private static final int FOLDER_OUTLINE = Surfaces.BORDER;
    private static final int UNSELECTED_BG = Surfaces.FACE_DISABLED;
    private static final int HOVERED_BG = Surfaces.FACE;
    private static final int UNSELECTED_TEXT = Surfaces.TEXT_DIM;
    private static final int HOVERED_TEXT = 0xFFCCCCCC;
    /** The bar along a selected flat tab's bottom edge. */
    private static final int BAR_HEIGHT = 2;

    private final Style style;
    private final IntConsumer onSelect;
    private final List<Tab> tabs = new ArrayList<>();
    private int selected;
    private int selectedBg = Theme.PANEL;

    public UiTabs(Style style, IntConsumer onSelect) {
        this.style = style;
        this.onSelect = onSelect;
        this.height = style == Style.FOLDER ? FOLDER_HEIGHT : FLAT_HEIGHT;
    }

    public UiTabs add(Component label, ItemStack icon, int count, boolean done) {
        tabs.add(new Tab(label, icon, count, done));
        return this;
    }

    public void setTabs(List<Tab> newTabs) {
        tabs.clear();
        tabs.addAll(newTabs);
        selected = Math.min(selected, Math.max(0, tabs.size() - 1));
    }

    /** The panel colour the selected folder tab continues. */
    public void setSelectedBackground(int colour) {
        this.selectedBg = colour;
    }

    /** Selects a tab without telling the screen, as when it restores its own state. */
    public void select(int index) {
        if (index >= 0 && index < tabs.size()) {
            selected = index;
        }
    }

    public int selected() {
        return selected;
    }

    public int size() {
        return tabs.size();
    }

    /** The width all the tabs take, with the gaps between them. */
    public int contentWidth() {
        Font font = Minecraft.getInstance().font;
        int total = 0;
        for (int i = 0; i < tabs.size(); i++) {
            total += tabWidth(font, tabs.get(i)) + (i > 0 ? GAP : 0);
        }
        return total;
    }

    // The tabs' widths: as their labels need, or, given less room than that, narrowed in proportion
    private int[] widths(Font font) {
        int[] widths = new int[tabs.size()];
        int natural = contentWidth();
        int gaps = GAP * Math.max(0, tabs.size() - 1);
        boolean shrink = width > gaps && width < natural;
        for (int i = 0; i < tabs.size(); i++) {
            int own = tabWidth(font, tabs.get(i));
            widths[i] = shrink ? Math.max(1, own * (width - gaps) / Math.max(1, natural - gaps)) : own;
        }
        return widths;
    }

    /** Where the selected tab starts, so the panel below can leave its top edge out. */
    public int selectedLeft() {
        return tabLeft(selected);
    }

    public int selectedRight() {
        return tabs.isEmpty() ? x : tabLeft(selected) + widths(Minecraft.getInstance().font)[selected];
    }

    private int tabLeft(int index) {
        int[] widths = widths(Minecraft.getInstance().font);
        int left = x;
        for (int i = 0; i < index && i < tabs.size(); i++) {
            left += widths[i] + GAP;
        }
        return left;
    }

    private String text(Tab tab) {
        String label = tab.label().getString();
        return tab.count() >= 0 ? label + " (" + tab.count() + ")" : label;
    }

    private int tabWidth(Font font, Tab tab) {
        int text = font.width(text(tab)) + (tab.done() ? font.width(TICK) : 0);
        if (style == Style.FLAT) {
            return text + 2 * FLAT_PADDING;
        }
        return PADDING + (tab.icon().isEmpty() ? 0 : ICON_SIZE + 4) + text + PADDING + 2;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int[] widths = widths(Minecraft.getInstance().font);
        int left = x;
        for (int i = 0; i < tabs.size(); i++) {
            if (mouseX >= left && mouseX < left + widths[i]) {
                return i;
            }
            left += widths[i] + GAP;
        }
        return -1;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int hoveredIndex = indexAt(mouseX, mouseY);
        int[] widths = widths(font);
        int left = x;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            int width = widths[i];
            if (style == Style.FOLDER) {
                drawFolder(graphics, font, tab, left, width, i == selected, i == hoveredIndex);
            } else {
                drawFlat(graphics, font, tab, left, width, i == selected, i == hoveredIndex);
            }
            left += width + GAP;
        }
    }

    private void drawFolder(GuiGraphics graphics, Font font, Tab tab, int left, int width, boolean isSelected,
                            boolean isHovered) {
        int top = y + (isSelected ? 0 : UNSELECTED_DROP);
        int bottom = y + height;
        boolean hovered = !isSelected && isHovered;
        graphics.fill(left, top, left + width, bottom, isSelected ? selectedBg : hovered ? HOVERED_BG : UNSELECTED_BG);
        graphics.fill(left, top, left + width, top + 1, FOLDER_OUTLINE);
        // The selected tab's sides run on into the panel's own border
        graphics.fill(left, top + 1, left + 1, bottom + (isSelected ? 1 : 0), FOLDER_OUTLINE);
        graphics.fill(left + width - 1, top + 1, left + width, bottom + (isSelected ? 1 : 0), FOLDER_OUTLINE);
        if (!isSelected) {
            graphics.fill(left + 1, bottom - 1, left + width - 1, bottom, FOLDER_OUTLINE);
        }
        int textX = left + PADDING;
        int tabHeight = bottom - top;
        if (!tab.icon().isEmpty()) {
            int iconY = top + (tabHeight - ICON_SIZE) / 2;
            // An unselected tab's icon is drawn a little smaller, centred where the full one sits
            float iconScale = isSelected ? 1.0f : UNSELECTED_ICON_SCALE;
            GuiPose.push(graphics);
            GuiPose.translate(graphics, textX + ICON_SIZE / 2.0f, iconY + ICON_SIZE / 2.0f);
            GuiPose.scale(graphics, iconScale, iconScale);
            GuiPose.translate(graphics, -ICON_SIZE / 2.0f, -ICON_SIZE / 2.0f);
            graphics.renderItem(tab.icon(), 0, 0);
            GuiPose.pop(graphics);
            textX += ICON_SIZE + 4;
        }
        int colour = isSelected ? Theme.TEXT : hovered ? HOVERED_TEXT : UNSELECTED_TEXT;
        drawLabel(graphics, font, tab, textX, top + (tabHeight - 8) / 2 + 1, left + width - PADDING - textX, colour);
    }

    private void drawFlat(GuiGraphics graphics, Font font, Tab tab, int left, int width, boolean isSelected,
                          boolean hovered) {
        if (isSelected) {
            graphics.fill(left, y, left + width, y + height, Theme.HIGHLIGHT);
            graphics.fill(left, y + height - BAR_HEIGHT, left + width, y + height, Theme.HIGHLIGHT_BAR);
        } else if (hovered) {
            graphics.fill(left, y, left + width, y + height, Theme.HOVER);
        }
        int colour = isSelected ? Theme.TEXT : hovered ? HOVERED_TEXT : UNSELECTED_TEXT;
        int pad = Math.min(FLAT_PADDING, Math.max(2, (width - font.width(text(tab))) / 2));
        drawLabel(graphics, font, tab, left + pad, y + (height - BAR_HEIGHT - 8) / 2 + 1, width - 2 * pad, colour);
    }

    private void drawLabel(GuiGraphics graphics, Font font, Tab tab, int textX, int textY, int room, int colour) {
        if (tab.done() && font.width(TICK) < room) {
            Draw.string(graphics, font, TICK, textX, textY, Theme.GREEN);
            textX += font.width(TICK);
            room -= font.width(TICK);
        }
        Draw.text(graphics, font, text(tab), textX, textY, Math.max(0, room), colour);
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
        if (index != selected) {
            selected = index;
            onSelect.accept(index);
        }
        return true;
    }
}
