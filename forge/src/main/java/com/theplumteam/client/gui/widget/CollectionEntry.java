package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * Individual entry in the collection list widget
 */
public class CollectionEntry extends ObjectSelectionList.Entry<CollectionEntry> {
    private final Minecraft mc;
    private final FigureCollection collection;
    private final CollectionListWidget parent;
    private static final int PADDING = 4;

    public CollectionEntry(CollectionListWidget parent, FigureCollection collection) {
        this.parent = parent;
        this.mc = Minecraft.getInstance();
        this.collection = collection;
    }

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
        // Check if this entry is selected
        boolean isSelected = parent.getSelected() == this;

        // Background color for hover/selection
        if (isSelected) {
            graphics.fill(x, y, x + entryWidth, y + entryHeight, 0x80FFFFFF);
        } else if (isMouseOver) {
            graphics.fill(x, y, x + entryWidth, y + entryHeight, 0x40FFFFFF);
        }

        // Draw collection name
        int textX = x + PADDING;
        int textY = y + PADDING;
        int textColor = isSelected ? 0xFFFFFF : 0xE0E0E0;

        graphics.drawString(mc.font, collection.getName(), textX, textY, textColor, false);

        // Draw figure count below the name
        String figureCount = collection.getFigures().size() + " figures";
        int subTextY = textY + mc.font.lineHeight + 2;
        int subTextColor = isSelected ? 0xAAAAAA : 0x808080;
        graphics.drawString(mc.font, figureCount, textX, subTextY, subTextColor, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            parent.setSelected(this);
            parent.onCollectionSelected(this);
            return true;
        }
        return false;
    }

    @Override
    public Component getNarration() {
        return Component.literal(collection.getName() + " - " +
                               collection.getFigures().size() + " figures");
    }

    public FigureCollection getCollection() {
        return collection;
    }
}
