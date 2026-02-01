package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SODIUM WORKAROUND: Custom scrollable list widget that DOES NOT use ObjectSelectionList
 * This completely bypasses vanilla's list rendering to test if Sodium's bug is specific to ObjectSelectionList
 */
public class CustomCollectionListWidget extends AbstractWidget {
    private final CollectionSelectionScreen parentScreen;
    private final List<FigureCollection> collections = new ArrayList<>();
    private final int entryHeight = 55;
    private double scrollOffset = 0;
    private int selectedIndex = -1;

    public CustomCollectionListWidget(CollectionSelectionScreen parentScreen, int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Collections"));
        this.parentScreen = parentScreen;
    }

    public void addCollection(FigureCollection collection) {
        collections.add(collection);
    }

    public void clearCollections() {
        collections.clear();
        selectedIndex = -1;
        scrollOffset = 0;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Enable scissor for clipping
        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);

        // Calculate visible entries
        int scrollOffsetInt = (int) scrollOffset;
        int firstVisibleIndex = Math.max(0, scrollOffsetInt / entryHeight);
        int lastVisibleIndex = Math.min(collections.size() - 1,
            (scrollOffsetInt + height) / entryHeight + 1);

        // Render each visible entry
        for (int i = firstVisibleIndex; i <= lastVisibleIndex && i < collections.size(); i++) {
            FigureCollection collection = collections.get(i);

            int entryY = getY() + (i * entryHeight) - scrollOffsetInt;
            int entryX = getX();

            boolean isSelected = (i == selectedIndex);
            boolean isHovered = mouseX >= entryX && mouseX < entryX + width - 8 &&
                               mouseY >= entryY && mouseY < entryY + entryHeight;

            // Render selection/hover background
            if (isSelected) {
                graphics.fill(entryX + 4, entryY, entryX + width - 8, entryY + entryHeight, 0x80308CC0);
                graphics.renderOutline(entryX + 4, entryY, width - 12, entryHeight, 0xFF4080FF);
            } else if (isHovered) {
                graphics.fill(entryX + 4, entryY, entryX + width - 8, entryY + entryHeight, 0x30FFFFFF);
            }

            // Logo rendering - textures are 256x256, scale to 56x56 using pose stack
            int logoDisplaySize = 56;
            int logoX = entryX + 8;
            int textStartX = logoX + logoDisplaySize + 8;

            net.minecraft.resources.ResourceLocation logoTexture = collection.getLogoTexture();
            if (logoTexture != null) {
                // Flush before logo rendering to isolate from text
                graphics.flush();

                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                int logoY = entryY + (entryHeight - logoDisplaySize) / 2;

                // Use pose stack to scale 256x256 texture down to 56x56
                graphics.pose().pushPose();
                graphics.pose().translate(logoX, logoY, 0);
                float scale = (float)logoDisplaySize / 256.0f; // 56/256 = 0.21875
                graphics.pose().scale(scale, scale, 1.0f);

                // Render at 0,0 since we already translated, full 256x256 texture
                graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, logoTexture,
                    0, 0, 0.0f, 0.0f, 256, 256, 256, 256);

                graphics.pose().popPose();

                // Flush after logo rendering to isolate from text
                graphics.flush();
            }

            // Render text - THIS WORKS because we're not using ObjectSelectionList!
            int textX = textStartX;
            int textY = entryY + 12;

            // Collection name
            graphics.drawString(Minecraft.getInstance().font, collection.getName(),
                textX, textY, 0xFFFFFFFF, false);

            // Figure count
            String figureCount = collection.getFigures().size() + " figures";
            graphics.drawString(Minecraft.getInstance().font, figureCount,
                textX, textY + 12, 0xFFAAAAAA, false);

            // Author
            String author = "By " + collection.getAuthor();
            graphics.drawString(Minecraft.getInstance().font, author,
                textX, textY + 24, 0xFF808080, false);
        }

        graphics.disableScissor();

        // Draw scrollbar
        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int maxScroll = Math.max(0, (collections.size() * entryHeight) - height);
        if (maxScroll > 0) {
            int scrollbarX = getX() + width - 6;
            int scrollbarHeight = height;
            int thumbHeight = Math.max(20, (int)((float)height / (collections.size() * entryHeight) * height));
            int thumbY = getY() + (int)((scrollOffset / maxScroll) * (scrollbarHeight - thumbHeight));

            // Scrollbar track
            graphics.fill(scrollbarX, getY(), scrollbarX + 6, getY() + height, 0x40FFFFFF);

            // Scrollbar thumb
            graphics.fill(scrollbarX, thumbY, scrollbarX + 6, thumbY + thumbHeight, 0x80FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

        int scrollOffsetInt = (int) scrollOffset;
        int clickedIndex = ((int)mouseY - getY() + scrollOffsetInt) / entryHeight;

        if (clickedIndex >= 0 && clickedIndex < collections.size()) {
            selectedIndex = clickedIndex;
            // Notify parent screen of selection
            // parentScreen.onCollectionSelected(...); // TODO: implement this
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

        double maxScroll = Math.max(0, (collections.size() * entryHeight) - height);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - scrollY * 15));

        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration for now
    }
}
