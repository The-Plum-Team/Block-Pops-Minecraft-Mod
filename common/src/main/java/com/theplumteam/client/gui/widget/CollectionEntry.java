package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///? }
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Locale;
import java.util.function.Predicate;

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
    private static final int MONOGRAM_SIZE = 40; // The tile drawn in place of a missing logo

    // Link button state
    private static final int LINK_BUTTON_SIZE = 14; // Square button like the reference
    private int linkButtonX, linkButtonY;
    private boolean isLinkHovered;

    public CollectionEntry(CollectionListWidget parent, FigureCollection collection) {
        this.parent = parent;
        this.mc = Minecraft.getInstance();
        this.collection = collection;
    }

    //? if >=26 {
    /*// 26.1 gives an entry its own bounds and asks it to extract its contents rather
    // than passing the row geometry in on every draw.
    @Override
    public void extractContent(GuiGraphics graphics, int mouseX, int mouseY,
                               boolean isMouseOver, float partialTick) {
        render(graphics, 0, getY(), getX(), getWidth(), getHeight(), mouseX, mouseY, isMouseOver, partialTick);
    }

    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
    *///? } else {
    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
    //? }
        // Check if this entry is selected
        boolean isSelected = parent.getSelected() == this;
        drawCard(graphics, mc.font, collection, x, y, entryWidth, entryHeight, isSelected, isMouseOver);

        // Where the card's highlight ends, which the link button sits inside
        float scale = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getRenderScaleFactor() : 1.0f;
        int effectiveLinkButtonSize = (int)(LINK_BUTTON_SIZE * scale);
        int highlightRight = x + entryWidth - 10;
        int highlightTop = y - (int)(2 * scale);

        // Render link button on hover if author URL is available
        this.isLinkHovered = false;
        if (isMouseOver && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
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
            graphics.drawString(mc.font, planetEmoji, emojiX, emojiY, 0xFFFFFFFF, false);

            this.isLinkHovered = linkHovered;
        }
    }

    /**
     * Draws a collection's card as the machine's collection list draws it: the selection or hover
     * highlight, the logo or a monogram in its place, the name, the count of discovered figures
     * and the author line. The creator's machine replica draws its cards through this too.
     *
     * @param entryWidth  the row's width; the highlight stops 10 short of it, before the scroll bar
     * @param entryHeight the row's height; the highlight reaches a little above and below it
     */
    public static void drawCard(GuiGraphics graphics, Font font, FigureCollection collection, int x, int y,
                                int entryWidth, int entryHeight, boolean selected, boolean hovered) {
        drawCard(graphics, font, collection, x, y, entryWidth, entryHeight, selected, hovered,
                ClientDiscoveryManager::isDiscovered);
    }

    /**
     * Draws a collection's card as above, counting as discovered the figures a predicate names,
     * asked with "collection:figure", as the creator's replica shows a collection to a new player
     * or a collector.
     */
    public static void drawCard(GuiGraphics graphics, Font font, FigureCollection collection, int x, int y,
                                int entryWidth, int entryHeight, boolean selected, boolean hovered,
                                Predicate<String> discovered) {
        // Calculate effective sizes - scale when using inverse scale mode
        float scale = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getRenderScaleFactor() : 1.0f;
        int effectivePadding = (int)(PADDING * scale);
        int effectiveTopPadding = (int)(TOP_PADDING * scale);
        int effectiveLogoMaxSize = (int)(LOGO_MAX_SIZE * scale);

        // Selection and hover highlight with padding
        int highlightPaddingH = (int)(4 * scale);
        int highlightPaddingV = (int)(2 * scale);
        int highlightLeft = x - highlightPaddingH;
        int highlightRight = x + entryWidth - 10;
        int highlightTop = y - highlightPaddingV;
        int highlightBottom = y + entryHeight + highlightPaddingV;

        if (selected) {
            // Selected state - blue highlight with border
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x80308CC0);
            graphics.renderOutline(highlightLeft, highlightTop, highlightRight - highlightLeft,
                highlightBottom - highlightTop, 0xFF4080FF);
        } else if (hovered) {
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
            // Logos keep their aspect ratio, so the drawn size comes from the texture's
            // own size. 1.21.5 exposes that on the uploaded texture; below it the size
            // is only reachable through the raw GL object.
            //? if >=1.21.5 {
            /*com.mojang.blaze3d.textures.GpuTexture logoGpuTexture =
                    Minecraft.getInstance().getTextureManager().getTexture(logoTexture).getTexture();
            int textureWidth = logoGpuTexture.getWidth(0) > 0 ? logoGpuTexture.getWidth(0) : 256;
            int textureHeight = logoGpuTexture.getHeight(0) > 0 ? logoGpuTexture.getHeight(0) : 256;
            *///? } else {
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
            //? }

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

            // Draw the logo centered within its container
            //? if >=1.21.6 {
            /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, logoTexture,
                    logoX, logoY,
                    0.0f, 0.0f,
                    logoWidth, logoHeight,
                    textureWidth, textureHeight,
                    textureWidth, textureHeight);
            *///? } elif >=1.21.2 {
            /*graphics.blit(RenderType::guiTextured, logoTexture,
                    logoX, logoY,
                    0.0f, 0.0f,
                    logoWidth, logoHeight,
                    textureWidth, textureHeight,
                    textureWidth, textureHeight);
            *///? } else {
            graphics.blit(logoTexture,
                    logoX, logoY, logoWidth, logoHeight,
                    0.0f, 0.0f,
                    textureWidth, textureHeight,
                    textureWidth, textureHeight);
            //? }
        } else {
            drawMonogram(graphics, font, collection, logoContainerX, y, logoContainerWidth, entryHeight, scale);
        }

        // Draw collection name
        int textX = textStartX;
        int textY = y + effectiveTopPadding;
        int textColor = selected ? 0xFFFFFFFF : 0xFFE0E0E0;

        graphics.drawString(font, collection.getName(), textX, textY, textColor, false);

        // Draw figure count below the name (discovered/total)
        String figureCount = figureCountText(collection, discovered);
        int subTextY = textY + font.lineHeight + 2;
        int subTextColor = selected ? 0xFFAAAAAA : 0xFF808080;
        graphics.drawString(font, figureCount, textX, subTextY, subTextColor, false);

        // Draw collection author below the figure count
        String author = collection.getAuthor();
        Component authorText;
        if ("world_players".equals(collection.getId())) {
            authorText = Component.literal("Auto-generated skins")
                .withStyle(ChatFormatting.GOLD);
        } else if (isUnsignedWorldCollection(collection)) {
            // An operator made it in this world and gave no author; "multiple creators" would be wrong
            authorText = Component.translatable("blockpops.world_collections.created_in_world")
                .withStyle(ChatFormatting.GOLD);
        } else if (author.equals("Unknown")) {
            authorText = Component.literal("Collection with multiple creators")
                .withStyle(ChatFormatting.GOLD);
        } else {
            authorText = Component.literal("Skin creator: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(author).withStyle(ChatFormatting.GOLD));
        }
        int authorY = subTextY + font.lineHeight + 2;
        graphics.drawString(font, authorText, textX, authorY, 0xFFFFFFFF, false);
    }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            // Check if link button was clicked
            if (this.isLinkHovered && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
                // Open the URL in the default browser
                try {
                    // 1.21.5 made ClickEvent a sealed hierarchy whose open-url case
                    // carries a URI instead of a string.
                    //? if >=26 {
                    /^net.minecraft.client.gui.screens.Screen previous = parent.getParentScreen();
                    java.net.URI target = java.net.URI.create(collection.getAuthorUrl());
                    mc.setScreen(com.theplumteam.client.gui.util.LinkOpening.confirm(target, confirmed -> {
                        if (confirmed) {
                            com.theplumteam.client.gui.util.LinkOpening.open(target);
                        }
                        mc.setScreen(previous);
                    }));
                    ^///? } elif >=1.21.5 {
                    /^mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent.OpenUrl(
                                java.net.URI.create(collection.getAuthorUrl())
                            )
                        )
                    );
                    ^///? } else {
                    mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL,
                                collection.getAuthorUrl()
                            )
                        )
                    );
                    //? }
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
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            // Check if link button was clicked
            if (this.isLinkHovered && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty()) {
                // Open the URL in the default browser
                try {
                    // 1.21.5 made ClickEvent a sealed hierarchy whose open-url case
                    // carries a URI instead of a string.
                    //? if >=1.21.5 {
                    /*mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent.OpenUrl(
                                java.net.URI.create(collection.getAuthorUrl())
                            )
                        )
                    );
                    *///? } else {
                    mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL,
                                collection.getAuthorUrl()
                            )
                        )
                    );
                    //? }
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
    //? }

    /**
     * Draws a tile in the collection's own colour with its initials where its logo would be,
     * for a collection without one, as a World collection may be.
     */
    private static void drawMonogram(GuiGraphics graphics, Font font, FigureCollection collection, int containerX,
                                     int y, int containerWidth, int entryHeight, float scale) {
        int size = Math.min(containerWidth, (int) (MONOGRAM_SIZE * scale));
        int tileX = containerX + (containerWidth - size) / 2;
        int tileY = y + (entryHeight - size) / 2;
        int[] rgb = collection.getBackgroundColor();
        int red = rgb != null && rgb.length == 3 ? rgb[0] & 0xFF : 0x40;
        int green = rgb != null && rgb.length == 3 ? rgb[1] & 0xFF : 0x40;
        int blue = rgb != null && rgb.length == 3 ? rgb[2] & 0xFF : 0x40;
        graphics.fill(tileX, tileY, tileX + size, tileY + size, 0xFF000000 | (red << 16) | (green << 8) | blue);
        graphics.renderOutline(tileX, tileY, size, size, 0x60FFFFFF);

        String initials = initials(collection.getName());
        if (initials.isEmpty()) {
            return;
        }
        // Dark letters on a light colour, light ones with a shadow on a dark colour
        boolean light = red * 299 + green * 587 + blue * 114 > 160_000;
        float textScale = size / 20.0f;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, tileX + size / 2.0f, tileY + size / 2.0f);
        GuiPose.scale(graphics, textScale, textScale);
        graphics.drawString(font, initials, -font.width(initials) / 2, -4,
                light ? 0xFF202020 : 0xFFFFFFFF, !light);
        GuiPose.pop(graphics);
    }

    /** The first letter or digit of the first two words of a name, in capitals. */
    static String initials(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (word.isEmpty() || !Character.isLetterOrDigit(word.codePointAt(0))) {
                continue;
            }
            initials.appendCodePoint(word.codePointAt(0));
            if (initials.codePointCount(0, initials.length()) == 2) {
                break;
            }
        }
        return initials.toString().toUpperCase(Locale.ROOT);
    }

    /** Whether this is a World collection whose operator left the author empty. */
    private static boolean isUnsignedWorldCollection(FigureCollection collection) {
        // The creator's preview of a World collection reads as the collection will
        if (!WorldContentIds.isWorldCollectionId(collection.getId())
                && !collection.getId().startsWith(CollectionRegistry.PREVIEW_PREFIX)) {
            return false;
        }
        // The collection stands in for an empty author with "Unknown"; the record says what was typed
        WorldCollectionRecord record = ClientWorldCollections.record(collection.getId());
        return record != null ? record.author().isEmpty() : "Unknown".equals(collection.getAuthor());
    }

    @Override
    public Component getNarration() {
        return Component.literal(collection.getName() + " - "
                + figureCountText(collection, ClientDiscoveryManager::isDiscovered));
    }

    // A collection's specials are counted apart from its regular figures: "3/5 | 0/7 figures".
    // The figures are read from the registry, which holds the current ones of a re-synced
    // collection (see getCollection).
    private static String figureCountText(FigureCollection collection, Predicate<String> discovered) {
        FigureCollection current = CollectionRegistry.getCollection(collection.getId()).orElse(collection);
        String count = discoveredCount(collection, current.getRegularFigures(), discovered) + "/"
                + current.getRegularFigures().size();
        if (current.hasSpecials()) {
            count += " | " + discoveredCount(collection, current.getSpecialFigures(), discovered) + "/"
                    + current.getSpecialFigures().size();
        }
        return count + " figures";
    }

    private static int discoveredCount(FigureCollection collection, java.util.List<FigureDefinition> figures,
                                       Predicate<String> discovered) {
        int discoveredCount = 0;
        for (FigureDefinition figure : figures) {
            String figureId = collection.getId() + ":" + figure.getId();
            if (discovered.test(figureId)) {
                discoveredCount++;
            }
        }
        return discoveredCount;
    }

    public FigureCollection getCollection() {
        // The screen keeps the collections it opened with, and World Players is re-synced
        // while it is open, so the registry holds the current figures.
        return CollectionRegistry.getCollection(collection.getId()).orElse(collection);
    }
}
