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

            // Logo rendering with proper sizing
            int logoMaxSize = 56;
            int logoContainerX = entryX + 8;
            int textStartX = logoContainerX + logoMaxSize + 8;

            net.minecraft.resources.ResourceLocation logoTexture = collection.getLogoTexture();
            if (logoTexture != null) {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, logoTexture);

                // Get texture dimensions
                int textureId = com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0);
                int[] widthArr = new int[1];
                int[] heightArr = new int[1];
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
                org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH, widthArr);
                org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT, heightArr);

                int textureWidth = widthArr[0] > 0 ? widthArr[0] : 256;
                int textureHeight = heightArr[0] > 0 ? heightArr[0] : 256;

                // Calculate scaled dimensions preserving aspect ratio
                float aspectRatio = (float) textureWidth / textureHeight;
                int logoWidth, logoHeight;
                int maxSize = "world_players".equals(collection.getId()) ? 40 : logoMaxSize;

                if (aspectRatio > 1.0f) {
                    logoWidth = maxSize;
                    logoHeight = (int) (maxSize / aspectRatio);
                } else {
                    logoHeight = maxSize;
                    logoWidth = (int) (maxSize * aspectRatio);
                }

                int logoX = logoContainerX + (logoMaxSize - logoWidth) / 2;
                int logoY = entryY + (entryHeight - logoHeight) / 2;

                graphics.pose().pushPose();
                graphics.pose().translate(logoX, logoY, 0);
                float scaleX = (float) logoWidth / textureWidth;
                float scaleY = (float) logoHeight / textureHeight;
                graphics.pose().scale(scaleX, scaleY, 1.0f);
                graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, logoTexture,
                    0, 0, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);
                graphics.pose().popPose();
            }

            // Render text with correct colors - THIS WORKS because we're not using ObjectSelectionList!
            int textX = textStartX;
            int textY = entryY + 12;

            // Collection name (white/light gray)
            int nameColor = isSelected ? 0xFFFFFFFF : 0xFFE0E0E0;
            graphics.drawString(Minecraft.getInstance().font, collection.getName(),
                textX, textY, nameColor, false);

            // Figure count (gray)
            int figureCountColor = isSelected ? 0xFFAAAAAA : 0xFF808080;
            String figureCount = collection.getFigures().size() + " figures";
            graphics.drawString(Minecraft.getInstance().font, figureCount,
                textX, textY + 12, figureCountColor, false);

            // Author (gold color)
            String author = collection.getAuthor();
            net.minecraft.network.chat.Component authorText;
            if ("world_players".equals(collection.getId())) {
                authorText = net.minecraft.network.chat.Component.literal("Auto-generated skins")
                    .withStyle(net.minecraft.ChatFormatting.GOLD);
            } else if ("Unknown".equals(author)) {
                authorText = net.minecraft.network.chat.Component.literal("Collection with multiple creators")
                    .withStyle(net.minecraft.ChatFormatting.GOLD);
            } else {
                authorText = net.minecraft.network.chat.Component.literal("Skin creator: ")
                    .withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(net.minecraft.network.chat.Component.literal(author).withStyle(net.minecraft.ChatFormatting.GOLD));
            }
            graphics.drawString(Minecraft.getInstance().font, authorText,
                textX, textY + 24, 0xFFFFFFFF, false);
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
