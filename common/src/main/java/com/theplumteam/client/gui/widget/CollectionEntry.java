package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.style.Chip;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.renderer.LogoLayout;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///? }
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

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
    private static final int CARD_LIP_HEIGHT = 2;
    /** The colour behind an unselected logo, and how strong the glow starts at the card's edge. */
    private static final int IDLE_GLOW_COLOR = 0xFF8D8E93;
    private static final int IDLE_GLOW = 0x30;
    private static final int SELECTED_GLOW = 0x70;
    private static final int GLOW_WIDTH = 70;
    private static final int HAIRLINE = 0x38FFFFFF;
    private static final int AUTHOR_COLOR = 0xFFFFC23A;

    // The author's link, a chip like the figures' version counter
    private static final String LINK_LABEL = "Web";
    private static final int LINK_INSET = 4;
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
        boolean linkShown = isMouseOver && collection.getAuthorUrl() != null && !collection.getAuthorUrl().isEmpty();
        int linkWidth = Chip.width(LINK_LABEL, Chip.ARROW_OUT);
        // The name stops short of the link
        drawCard(graphics, mc.font, collection, x, y, entryWidth, entryHeight, isSelected, isMouseOver,
                ClientDiscoveryManager::isDiscovered, linkShown ? linkWidth + 3 : 0);

        // The author's link, in a hovered card's top-right corner, gold under the mouse
        this.isLinkHovered = false;
        if (linkShown) {
            int linkRight = cardRight(x, entryWidth) - LINK_INSET;
            int linkTop = cardTop(y) + LINK_INSET;
            boolean linkHovered = mouseX >= linkRight - linkWidth && mouseX < linkRight
                    && mouseY >= linkTop && mouseY < linkTop + Chip.height();
            Chip.draw(graphics, LINK_LABEL, Chip.ARROW_OUT, linkRight, linkTop, linkHovered);
            this.isLinkHovered = linkHovered;
        }
    }

    // The card spans the row, stopping short of the list's scroll bar, with a gap to the next one
    private static int cardLeft(int x) {
        return x - 2;
    }

    private static int cardRight(int x, int entryWidth) {
        return x + entryWidth - 8;
    }

    private static int cardTop(int y) {
        return y - 1;
    }

    /**
     * Draws a collection's card as the machine's collection list draws it, a card of Quick Skin's
     * skin list: a raised charcoal card with the logo (or a monogram in its place) on the left
     * over a glow that is blue on the selected card, then the name over a hairline, the count of
     * discovered figures and the author line. The creator's machine replica draws its cards
     * through this too, counting as discovered the figures a predicate names, asked with
     * "collection:figure", as it shows a collection to a new player or a collector.
     *
     * @param entryWidth  the row's width; the card stops 8 short of it, before the scroll bar
     * @param entryHeight the row's height; the card reaches a pixel above and below it
     */
    public static void drawCard(GuiGraphics graphics, Font font, FigureCollection collection, int x, int y,
                                int entryWidth, int entryHeight, boolean selected, boolean hovered,
                                Predicate<String> discovered) {
        drawCard(graphics, font, collection, x, y, entryWidth, entryHeight, selected, hovered, discovered, 0);
    }

    // As above, with the name kept clear of the top-right corner by the given width
    private static void drawCard(GuiGraphics graphics, Font font, FigureCollection collection, int x, int y,
                                 int entryWidth, int entryHeight, boolean selected, boolean hovered,
                                 Predicate<String> discovered, int nameReserve) {
        int left = cardLeft(x);
        int right = cardRight(x, entryWidth);
        int top = cardTop(y);
        int bottom = y + entryHeight + 1;
        int faceBottom = Surfaces.raised(graphics, left, top, right, bottom,
                selected ? Surfaces.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                hovered ? Surfaces.FACE_HOVER : Surfaces.FACE, Surfaces.LIP, CARD_LIP_HEIGHT);

        // Colour behind the logo, fading toward the text: strong only on the selected card
        int glowColor = selected ? Surfaces.SELECTED : IDLE_GLOW_COLOR;
        int glow = selected ? SELECTED_GLOW : IDLE_GLOW;
        int glowWidth = Math.min(GLOW_WIDTH, right - left - 4);
        for (int k = 0; k < glowWidth; k++) {
            int alpha = glow * (GLOW_WIDTH - k) / GLOW_WIDTH;
            if (alpha > 0) {
                graphics.fill(left + 2 + k, top + 2, left + 3 + k, faceBottom - 1, alpha << 24 | (glowColor & 0x00FFFFFF));
            }
        }

        // The logo, or a monogram in its place, in a box on the left up to twice as wide as tall,
        // so wide logos keep their size
        int logoSize = Math.max(8, faceBottom - 1 - (top + 2) - 4);
        int logoBoxWidth = Mth.clamp((right - left) / 4, logoSize, logoSize * 2);
        int logoLeft = left + 2 + PADDING;
        int logoTop = top + 2 + (faceBottom - 1 - (top + 2) - logoSize) / 2;
        ResourceLocation logoTexture = collection.getLogoTexture();
        if (logoTexture != null) {
            // Only the drawn part of the logo counts, as on the box, so transparent margins
            // in the file do not shrink it; it keeps that part's aspect ratio.
            int[] bounds = LogoLayout.bounds(logoTexture);
            int textureWidth = bounds[0];
            int textureHeight = bounds[1];
            int drawnWidth = bounds[4] - bounds[2];
            int drawnHeight = bounds[5] - bounds[3];
            //? if <1.21.5 {
            // Enable blending for transparent logos
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            //? }

            // Calculate scaled dimensions preserving aspect ratio
            float aspectRatio = (float) drawnWidth / drawnHeight;
            int logoWidth;
            int logoHeight;

            int maxWidth = Math.min(logoBoxWidth, logoSize * 2);

            // As large as the box allows, keeping the logo's shape
            if (aspectRatio > (float) maxWidth / logoSize) {
                logoWidth = maxWidth;
                logoHeight = (int) (maxWidth / aspectRatio);
            } else {
                logoHeight = logoSize;
                logoWidth = (int) (logoSize * aspectRatio);
            }

            // Centred in its box
            int logoX = logoLeft + (logoBoxWidth - logoWidth) / 2;
            int logoY = logoTop + (logoSize - logoHeight) / 2;

            // Draw the logo centered within its container
            //? if >=1.21.6 {
            /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, logoTexture,
                    logoX, logoY,
                    bounds[2], bounds[3],
                    logoWidth, logoHeight,
                    drawnWidth, drawnHeight,
                    textureWidth, textureHeight);
            *///? } elif >=1.21.2 {
            /*graphics.blit(RenderType::guiTextured, logoTexture,
                    logoX, logoY,
                    bounds[2], bounds[3],
                    logoWidth, logoHeight,
                    drawnWidth, drawnHeight,
                    textureWidth, textureHeight);
            *///? } else {
            graphics.blit(logoTexture,
                    logoX, logoY, logoWidth, logoHeight,
                    bounds[2], bounds[3],
                    drawnWidth, drawnHeight,
                    textureWidth, textureHeight);
            //? }
        } else {
            drawMonogram(graphics, font, collection, logoLeft + (logoBoxWidth - logoSize) / 2, logoTop, logoSize);
        }

        // The name over a hairline, the count of discovered figures, the author line
        int textX = logoLeft + logoBoxWidth + PADDING + 2;
        int room = right - 4 - textX;
        int textY = top + 5;
        UiText.drawFitted(graphics, font, collection.getName(), textX, textY, room - nameReserve,
                selected ? Surfaces.TEXT : 0xFFE8E8E8);
        graphics.fill(textX, textY + font.lineHeight + 1, right - 4, textY + font.lineHeight + 2, HAIRLINE);

        int subTextY = textY + font.lineHeight + 4;
        UiText.drawFitted(graphics, font, figureCountText(collection, discovered), textX, subTextY, room,
                selected ? 0xFFC8C8C8 : Surfaces.TEXT_DIM);

        String author = collection.getAuthor();
        String authorText;
        if ("world_players".equals(collection.getId())) {
            authorText = "Auto-generated skins";
        } else if (isUnsignedWorldCollection(collection)) {
            // An operator made it in this world and gave no author; "multiple creators" would be wrong
            authorText = Component.translatable("blockpops.world_collections.created_in_world").getString();
        } else if (author.equals("Unknown")) {
            authorText = "Collection with multiple creators";
        } else {
            authorText = "Skin creator: " + author;
        }
        UiText.drawFitted(graphics, font, authorText, textX, subTextY + font.lineHeight + 2, room, AUTHOR_COLOR);
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
    private static void drawMonogram(GuiGraphics graphics, Font font, FigureCollection collection, int tileX,
                                     int tileY, int size) {
        int[] rgb = collection.getBackgroundColor();
        int red = rgb != null && rgb.length == 3 ? rgb[0] & 0xFF : 0x40;
        int green = rgb != null && rgb.length == 3 ? rgb[1] & 0xFF : 0x40;
        int blue = rgb != null && rgb.length == 3 ? rgb[2] & 0xFF : 0x40;
        graphics.fill(tileX, tileY, tileX + size, tileY + size, 0xFF000000 | (red << 16) | (green << 8) | blue);
        Surfaces.outline(graphics, tileX, tileY, size, size, 0x60FFFFFF);

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
