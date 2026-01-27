package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.renderer.RenderType;
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
        // Calculate effective sizes - scale when using inverse scale mode
        float scale = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getRenderScaleFactor() : 1.0f;
        int effectivePadding = (int)(PADDING * scale);
        int effectiveTopPadding = (int)(TOP_PADDING * scale);
        int effectiveLogoMaxSize = (int)(LOGO_MAX_SIZE * scale);
        int effectiveLinkButtonSize = (int)(LINK_BUTTON_SIZE * scale);

        // Check if this entry is selected
        boolean isSelected = parent.getSelected() == this;

        // Selection and hover highlight with padding
        int highlightPaddingH = (int)(4 * scale);
        int highlightPaddingV = (int)(2 * scale);
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
        // All logos get the same container width for alignment
        int logoContainerX = x + effectivePadding;
        int logoContainerWidth = effectiveLogoMaxSize; // Fixed container width
        ResourceLocation logoTexture = collection.getLogoTexture();

        // Text always starts at the same position for all entries
        int textStartX = logoContainerX + logoContainerWidth + effectivePadding;

        if (logoTexture != null) {
            // In 1.21.5+, assume standard texture dimensions (most logos are 256x256 or similar)
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // Assume standard texture size (can be adjusted per collection if needed)
            int textureWidth = 256;
            int textureHeight = 256;

            // Calculate scaled dimensions preserving aspect ratio
            float aspectRatio = (float) textureWidth / textureHeight;
            int logoWidth;
            int logoHeight;

            // Use smaller size for World Players collection
            int maxSize = "world_players".equals(collection.getId()) ? 40 : effectiveLogoMaxSize;

            if (aspectRatio > 1.0f) {
                // Wider than tall - constrain width
                logoWidth = maxSize;
                logoHeight = (int) (maxSize / aspectRatio);
            } else {
                // Taller than wide or square - constrain height
                logoHeight = maxSize;
                logoWidth = (int) (maxSize * aspectRatio);
            }

            // Center the logo horizontally within the container
            int logoX = logoContainerX + (logoContainerWidth - logoWidth) / 2;

            // Center the logo vertically
            int logoY = y + (entryHeight - logoHeight) / 2;

            // In 1.21.4+, use PoseStack scaling to achieve the desired size
            graphics.pose().pushPose();
            graphics.pose().translate(logoX, logoY, 0);

            // Scale from texture size to desired logo size
            float scaleX = (float) logoWidth / textureWidth;
            float scaleY = (float) logoHeight / textureHeight;
            graphics.pose().scale(scaleX, scaleY, 1.0f);

            // Draw at (0,0) since we've already translated, using full texture
            graphics.blit(RenderType::guiTextured, logoTexture,
                    0, 0,                            // Position (already translated)
                    0.0f, 0.0f,                      // UV offset
                    textureWidth, textureHeight,     // Region size (full texture)
                    textureWidth, textureHeight);    // Texture dimensions

            graphics.pose().popPose();
        }

        // Draw collection name
        int textX = textStartX;
        int textY = y + effectiveTopPadding;
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
        if ("world_players".equals(collection.getId())) {
            authorText = Component.literal("Auto-generated skins")
                .withStyle(ChatFormatting.GOLD);
        } else if (author.equals("Unknown")) {
            authorText = Component.literal("Collection with multiple creators")
                .withStyle(ChatFormatting.GOLD);
        } else {
            authorText = Component.literal("Skin creator: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(author).withStyle(ChatFormatting.GOLD));
        }
        int authorY = subTextY + mc.font.lineHeight + 2;
        graphics.drawString(mc.font, authorText, textX, authorY, 0xFFFFFF, false);

        // Render link button on hover or when selected if author URL is available
        this.isLinkHovered = false;
        if ((isMouseOver || isSelected) && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
            int margin = 4;
            this.linkButtonX = highlightRight - effectiveLinkButtonSize - margin;
            this.linkButtonY = highlightTop + margin;

            boolean linkHovered = mouseX >= linkButtonX && mouseX < linkButtonX + effectiveLinkButtonSize &&
                                 mouseY >= linkButtonY && mouseY < linkButtonY + effectiveLinkButtonSize;

            // Draw button background (square) with shadow/depth - darker gold colored
            graphics.fill(linkButtonX, linkButtonY,
                linkButtonX + effectiveLinkButtonSize, linkButtonY + effectiveLinkButtonSize,
                linkHovered ? 0xFFB8860B : 0xC0997000); // Darker gold colors

            // Draw button outline for depth - darker gold
            graphics.renderOutline(linkButtonX, linkButtonY, effectiveLinkButtonSize, effectiveLinkButtonSize,
                linkHovered ? 0xFFDAA520 : 0xFFB8860B);

            // Draw planet emoji centered
            String planetEmoji = "\uD83C\uDF10";
            int emojiWidth = mc.font.width(planetEmoji);
            int emojiX = linkButtonX + (effectiveLinkButtonSize - emojiWidth) / 2; // Moved 1px left
            int emojiY = linkButtonY + (effectiveLinkButtonSize - 8) / 2; // Moved 1px up (8 is approximate emoji height)
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
                            // In 1.21.5+, ClickEvent.OpenUrl requires URI
                            new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(collection.getAuthorUrl()))
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
