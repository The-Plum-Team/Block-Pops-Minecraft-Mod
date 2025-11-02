package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Individual entry in the collection list widget
 */
public class CollectionEntry extends ObjectSelectionList.Entry<CollectionEntry> {
    private final Minecraft mc;
    private final FigureCollection collection;
    private final CollectionListWidget parent;
    private static final int PADDING = 4;
    private static final int TOP_PADDING = 12; // Extra padding at the top
    private static final int LOGO_MAX_SIZE = 56; // Maximum size of the logo image

    // Link button state
    private static final int LINK_BUTTON_SIZE = 14; // Square button like the reference
    private int linkButtonX, linkButtonY;
    private boolean isLinkHovered;

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
        ResourceLocation logoTexture = collection.getLogoTexture();

        int textStartX = logoX; // Default if no logo

        if (logoTexture != null) {
            // Enable blending for transparent logos
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // Bind texture first to ensure it's loaded
            RenderSystem.setShaderTexture(0, logoTexture);

            // Get actual texture dimensions from OpenGL
            int textureId = RenderSystem.getShaderTexture(0);
            int[] width = new int[1];
            int[] height = new int[1];

            // Bind and query texture dimensions
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH, width);
            GL11.glGetTexLevelParameteriv(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT, height);

            int textureWidth = width[0] > 0 ? width[0] : 256;
            int textureHeight = height[0] > 0 ? height[0] : 256;

            // Calculate scaled dimensions preserving aspect ratio
            float aspectRatio = (float) textureWidth / textureHeight;
            int logoWidth;
            int logoHeight;

            if (aspectRatio > 1.0f) {
                // Wider than tall - constrain width
                logoWidth = LOGO_MAX_SIZE;
                logoHeight = (int) (LOGO_MAX_SIZE / aspectRatio);
            } else {
                // Taller than wide or square - constrain height
                logoHeight = LOGO_MAX_SIZE;
                logoWidth = (int) (LOGO_MAX_SIZE * aspectRatio);
            }

            // Center the logo vertically
            int logoY = y + (entryHeight - logoHeight) / 2;

            // Draw the logo with preserved aspect ratio
            graphics.blit(logoTexture,
                    logoX, logoY, logoWidth, logoHeight,
                    0.0f, 0.0f,
                    textureWidth, textureHeight,
                    textureWidth, textureHeight);

            textStartX = logoX + logoWidth + PADDING; // Use actual logo width instead of max size
        }

        // Draw collection name
        int textX = textStartX;
        int textY = y + TOP_PADDING;
        int textColor = isSelected ? 0xFFFFFF : 0xE0E0E0;

        graphics.drawString(mc.font, collection.getName(), textX, textY, textColor, false);

        // Draw figure count below the name (discovered/total)
        int totalFigures = collection.getFigures().size();
        int discoveredCount = 0;
        for (FigureDefinition figure : collection.getFigures()) {
            String figureId = collection.getId() + ":" + figure.getId();
            if (ClientDiscoveryManager.isDiscovered(figureId)) {
                discoveredCount++;
            }
        }
        String figureCount = discoveredCount + "/" + totalFigures + " figures";
        int subTextY = textY + mc.font.lineHeight + 2;
        int subTextColor = isSelected ? 0xAAAAAA : 0x808080;
        graphics.drawString(mc.font, figureCount, textX, subTextY, subTextColor, false);

        // Draw collection author below the figure count
        String author = collection.getAuthor();
        Component authorText;
        if (author.equals("Unknown")) {
            authorText = Component.literal("Collection with multiple creators")
                .withStyle(ChatFormatting.GOLD);
        } else {
            authorText = Component.literal("Skin creator: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(author).withStyle(ChatFormatting.GOLD));
        }
        int authorY = subTextY + mc.font.lineHeight + 2;
        graphics.drawString(mc.font, authorText, textX, authorY, 0xFFFFFF, false);

        // Render link button on hover if author URL is available
        this.isLinkHovered = false;
        if (isMouseOver && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
            int margin = 4;
            this.linkButtonX = highlightRight - LINK_BUTTON_SIZE - margin;
            this.linkButtonY = highlightTop + margin;

            boolean linkHovered = mouseX >= linkButtonX && mouseX < linkButtonX + LINK_BUTTON_SIZE &&
                                 mouseY >= linkButtonY && mouseY < linkButtonY + LINK_BUTTON_SIZE;

            // Draw button background (square) with shadow/depth - darker gold colored
            graphics.fill(linkButtonX, linkButtonY,
                linkButtonX + LINK_BUTTON_SIZE, linkButtonY + LINK_BUTTON_SIZE,
                linkHovered ? 0xFFB8860B : 0xC0997000); // Darker gold colors

            // Draw button outline for depth - darker gold
            graphics.renderOutline(linkButtonX, linkButtonY, LINK_BUTTON_SIZE, LINK_BUTTON_SIZE,
                linkHovered ? 0xFFDAA520 : 0xFFB8860B);

            // Draw planet emoji centered
            String planetEmoji = "🌐";
            int emojiWidth = mc.font.width(planetEmoji);
            int emojiX = linkButtonX + (LINK_BUTTON_SIZE - emojiWidth) / 2; // Moved 1px left
            int emojiY = linkButtonY + (LINK_BUTTON_SIZE - 8) / 2; // Moved 1px up (8 is approximate emoji height)
            graphics.drawString(mc.font, planetEmoji, emojiX, emojiY, 0xFFFFFF, false);

            this.isLinkHovered = linkHovered;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Check if link button was clicked
            if (this.isLinkHovered && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
                // Open the URL in the default browser
                try {
                    mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL,
                                collection.getAuthorUrl()
                            )
                        )
                    );
                } catch (Exception e) {
                    // Fallback: just log the error
                    System.err.println("Failed to open URL: " + collection.getAuthorUrl());
                }
                return true;
            }

            // Otherwise select this entry
            parent.setSelected(this);
            parent.onCollectionSelected(this);
            return true;
        }
        return false;
    }

    @Override
    public Component getNarration() {
        int totalFigures = collection.getFigures().size();
        int discoveredCount = 0;
        for (FigureDefinition figure : collection.getFigures()) {
            String figureId = collection.getId() + ":" + figure.getId();
            if (ClientDiscoveryManager.isDiscovered(figureId)) {
                discoveredCount++;
            }
        }
        return Component.literal(collection.getName() + " - " +
                               discoveredCount + "/" + totalFigures + " figures");
    }

    public FigureCollection getCollection() {
        return collection;
    }
}
