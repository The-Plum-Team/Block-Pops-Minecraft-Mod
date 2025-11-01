package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Individual entry in the collection list widget
 */
public class CollectionEntry extends ObjectSelectionList.Entry<CollectionEntry> {
    private final Minecraft mc;
    private final FigureCollection collection;
    private final CollectionListWidget parent;
    private static final int PADDING = 4;
    private static final int LOGO_SIZE = 28; // Size of the logo image

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

        // Selection and hover highlight with padding
        int highlightPaddingH = 4;
        int highlightPaddingV = 2;
        int highlightLeft = x - highlightPaddingH;
        int highlightRight = x + entryWidth - 10;
        int highlightTop = y - highlightPaddingV;
        int highlightBottom = y + entryHeight + highlightPaddingV;

        if (isSelected) {
            // Selected state - blue highlight with border
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x80308CC0);
            graphics.renderOutline(highlightLeft, highlightTop, highlightRight - highlightLeft,
                highlightBottom - highlightTop, 0xFF4080FF);
        } else if (isMouseOver) {
            // Hover state - subtle white highlight
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x30FFFFFF);
        }

        // Draw logo on the left if available
        int logoX = x + PADDING;
        int logoY = y + (entryHeight - LOGO_SIZE) / 2; // Center vertically
        ResourceLocation logoTexture = collection.getLogoTexture();

        int textStartX = logoX; // Default if no logo

        if (logoTexture != null) {
            // Enable blending for transparent logos
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // Draw the logo
            graphics.blit(logoTexture,
                    logoX, logoY,
                    0, 0,
                    LOGO_SIZE, LOGO_SIZE,
                    LOGO_SIZE, LOGO_SIZE);

            textStartX = logoX + LOGO_SIZE + PADDING; // Offset text after logo
        }

        // Draw collection name
        int textX = textStartX;
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
