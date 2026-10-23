package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCardGrid;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.UiViewport;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One card of the creator's overview of World collections. A collection's card shows a band in
 * its background colour holding its box, turning slowly, and the heads of up to three of its
 * figures peeking up from the band's bottom edge; under the band come its name, its author, how
 * many figures it has and how many of them are in the draw, and how much of a collection's file
 * space it uses. A badge says whether it is published, a draft, archived or missing files on the
 * server, and an archived card is greyed out. While the pointer is over a card it shows an
 * "Edit" button and a "..." button for the card's menu. A card is raised as the menus' buttons
 * are, lighter under the pointer and with Quick Skin's blue border while selected.
 *
 * <p>The first card of the overview is not a collection: it starts a new one, and is drawn as
 * Quick Skin's import zone, a dark backdrop with a bracket at each corner.
 *
 * <p>The models are drawn from the collection the client registered for the record, so a card
 * shows exactly what the machine shows; a collection that is not registered yet draws its band
 * without them.
 */
public final class CollectionCard {
    /** The height of every card, the new-collection card included. */
    public static final int HEIGHT = 92;
    private static final int BAND = 40;
    private static final int PAD = 6;
    private static final int BOX_SIZE = 34;
    // The heads stand in cells taller than the band shows, so only their top peeks out of it
    private static final int HEAD_CELL = 46;
    private static final int HEAD_SHOWN = 27;
    private static final int HEAD_STEP = 25;
    private static final int MAX_HEADS = 3;
    // The machine screen's camera, as the figure grid draws figures
    private static final float CAMERA_YAW = 150.0f;
    private static final float CAMERA_X = 0.0f;
    private static final float CAMERA_Y = 15.0f;
    // One turn of the box every twelve seconds
    private static final long SPIN_MS = 12_000L;
    private static final int GREYED = 0x99000000;
    private static final int NEW_BACKDROP = 0x90;
    private static final int NEW_BACKDROP_HOVER = 0xC0;
    private static final int NEW_HINT = 0xFFC8C8C8;
    private static final int ARCHIVED_NAME = Theme.FAINT;
    private static final int SIZE_TEXT = Theme.FAINT;

    @Nullable
    private final WorldCollectionRecord record;
    private final int figureCount;
    private final int inDraw;
    private final int retired;
    private final int missingFiles;
    private final long bytes;
    @Nullable
    private final UiButton editButton;
    @Nullable
    private final UiButton moreButton;
    // The figures whose heads peek out, the enabled ones first
    private final List<String> headIds = new ArrayList<>();
    @Nullable
    private final String boxFigureId;
    private ItemStack box = ItemStack.EMPTY;
    @Nullable
    private FigureEntry heads;

    private CollectionCard(@Nullable WorldCollectionRecord record, @Nullable Runnable onEdit,
                           @Nullable Consumer<UiNode.Rect> onMore) {
        this.record = record;
        if (record == null) {
            this.figureCount = 0;
            this.inDraw = 0;
            this.retired = 0;
            this.missingFiles = 0;
            this.bytes = 0;
            this.editButton = null;
            this.moreButton = null;
            this.boxFigureId = null;
            return;
        }
        int enabled = 0;
        List<String> enabledIds = new ArrayList<>();
        List<String> retiredIds = new ArrayList<>();
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            if (figure.enabled()) {
                enabled++;
                enabledIds.add(figure.id());
            } else {
                retiredIds.add(figure.id());
            }
        }
        this.figureCount = record.figures().size();
        this.inDraw = enabled;
        this.retired = figureCount - enabled;
        long total = 0;
        int missing = 0;
        for (String sha : record.referencedShas()) {
            BlobInfo info = ClientWorldCollections.blob(sha);
            if (info != null) {
                total += info.size();
            }
            if (ClientWorldCollections.isMissingOnServer(sha)) {
                missing++;
            }
        }
        this.bytes = total;
        this.missingFiles = missing;
        List<String> order = new ArrayList<>(enabledIds);
        order.addAll(retiredIds);
        this.headIds.addAll(order.subList(0, Math.min(MAX_HEADS, order.size())));
        // The box holds a figure as the machine hands it out: the first one in the draw
        this.boxFigureId = order.isEmpty() ? null : order.get(0);
        this.editButton = new UiButton(CreatorScreen.tr("edit"), UiButton.Style.SECONDARY,
                onEdit != null ? onEdit : () -> { });
        // The button names itself in its own action, which a final field cannot do yet
        UiButton[] more = new UiButton[1];
        more[0] = new UiButton(Component.literal("···"), UiButton.Style.SECONDARY, () -> {
            if (onMore != null) {
                onMore.accept(UiScrollPane.onScreen(more[0].bounds()));
            }
        });
        more[0].setTooltip(CreatorScreen.tr("home.more"));
        this.moreButton = more[0];
    }

    /** The card that starts a new collection. */
    public static CollectionCard newCollection() {
        return new CollectionCard(null, null, null);
    }

    /**
     * The card of a collection.
     *
     * @param onEdit what its "Edit" button does
     * @param onMore what its "..." button does, told where the button is on the screen
     */
    public static CollectionCard of(WorldCollectionRecord record, Runnable onEdit, Consumer<UiNode.Rect> onMore) {
        return new CollectionCard(record, onEdit, onMore);
    }

    /** Tells cards apart across a rebuild: the collection's id, or empty for the new-collection card. */
    public String key() {
        return record != null ? record.id() : "";
    }

    public boolean isNewCard() {
        return record == null;
    }

    @Nullable
    public WorldCollectionRecord record() {
        return record;
    }

    // Layout

    private void placeButtons(UiCardGrid.Card card) {
        if (editButton == null || moreButton == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int editWidth = UiButton.widthFor(font, editButton.label(), false);
        int moreWidth = UiButton.widthFor(font, moreButton.label(), false);
        int top = card.y() + 4;
        editButton.at(card.x() + 4, top, editWidth, UiButton.SMALL_HEIGHT);
        moreButton.at(card.x() + 4 + editWidth + 3, top, moreWidth, UiButton.SMALL_HEIGHT);
    }

    /** Whether the card shows its buttons: while the pointer is over it, or it is selected. */
    private static boolean showsButtons(UiCardGrid.Card card) {
        return card.hovered() || card.selected();
    }

    // Input

    /**
     * Gives the card's own buttons first look at a click on it; true when one of them took it.
     * The new-collection card has none.
     */
    public boolean click(UiCardGrid.Card card, UiInput.Click click) {
        if (editButton == null || moreButton == null || !click.left()) {
            return false;
        }
        placeButtons(card);
        return moreButton.click(click) || editButton.click(click);
    }

    // Drawing

    /**
     * Draws the card.
     *
     * @param withHeads     whether to draw the figure heads, which cost a render each
     * @param disabled      for the new-collection card, why no collection can be started now,
     *                      or null when one can
     */
    public void draw(GuiGraphics graphics, UiCardGrid.Card card, boolean withHeads, @Nullable Component disabled,
                     float partialTick) {
        if (record == null) {
            drawNew(graphics, card, disabled);
            return;
        }
        Font font = Minecraft.getInstance().font;
        int x = card.x();
        int y = card.y();
        int width = card.width();
        int height = card.height();
        boolean archived = record.archived();
        Draw.card(graphics, x, y, width, height, card.hovered(), card.selected());

        // The band fills the face's top, inside the card's border
        int bandX = x + 2;
        int bandY = y + 2;
        int bandRight = x + width - 2;
        int bandBottom = bandY + BAND;
        graphics.fill(bandX, bandY, bandRight, bandBottom, bandColour(record));
        graphics.fill(bandX, bandBottom - 1, bandRight, bandBottom, Surfaces.OUTLINE);
        drawModels(graphics, bandX, bandY, bandRight, bandBottom, withHeads, archived, partialTick);
        // The wash, the badge and the buttons go above the models: a later stratum from 1.21.6,
        // depth before it, where a model in the band would otherwise cover them
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        try {
            if (archived) {
                graphics.fill(bandX, bandY, bandRight, bandBottom, GREYED);
            }
            boolean buttons = showsButtons(card);
            drawBadge(graphics, font, card, buttons);
            if (buttons && editButton != null && moreButton != null) {
                placeButtons(card);
                editButton.draw(graphics, card.mouseX(), card.mouseY(), partialTick);
                moreButton.draw(graphics, card.mouseX(), card.mouseY(), partialTick);
            }
        } finally {
            GuiPose.pop(graphics);
        }

        int textX = x + PAD;
        int textWidth = width - 2 * PAD;
        int top = bandBottom + 4;
        drawName(graphics, font, record.name(), textX, top, textWidth, archived ? ARCHIVED_NAME : Theme.TEXT);
        String author = record.author().isBlank() ? record.slug()
                : CreatorScreen.tr("card.by", record.author()).getString();
        Draw.text(graphics, font, author, textX, top + Theme.LINE_HEIGHT, textWidth, Theme.MUTED);
        int usedKiB = (int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024);
        Component size = UiQuotaMeter.text(usedKiB, (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024));
        Component stats = stats(true);
        if (retired > 0 && !archived && font.width(stats) > textWidth) {
            // A narrow card moves the retired count down beside the size, rather than cut it off
            stats = stats(false);
            size = CreatorScreen.tr("card.joined", size, CreatorScreen.tr("card.retired", retired));
        }
        Draw.text(graphics, font, stats.getString(), textX, top + 2 * Theme.LINE_HEIGHT - 1, textWidth,
                Theme.TEXT_SOFT);
        Draw.text(graphics, font, size.getString(), textX, top + 3 * Theme.LINE_HEIGHT - 2, textWidth, SIZE_TEXT);
    }

    // The band's own colour, or grey for an archived collection
    private static int bandColour(WorldCollectionRecord record) {
        int red = record.red();
        int green = record.green();
        int blue = record.blue();
        if (record.archived()) {
            int grey = (red * 30 + green * 59 + blue * 11) / 100;
            red = grey;
            green = grey;
            blue = grey;
        }
        return 0xFF000000 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
    }

    private void drawModels(GuiGraphics graphics, int left, int top, int right, int bottom, boolean withHeads,
                            boolean greyed, float partialTick) {
        FigureCollection collection = CollectionRegistry.getCollection(record.id()).orElse(null);
        if (collection == null) {
            return;
        }
        UiScrollPane.clip(graphics, left, top, right, bottom);
        try {
            if (box.isEmpty()) {
                box = UiViewport.boxStack(record.id(), boxFigureId, false);
            }
            float yaw = (Util.getMillis() % SPIN_MS) * 360.0f / SPIN_MS;
            UiViewport.drawBoxFront(graphics, box, yaw, 0, left + PAD + BOX_SIZE / 2.0f, (top + bottom) / 2.0f + 1,
                    BOX_SIZE);
            if (withHeads && !headIds.isEmpty()) {
                drawHeads(graphics, collection, right, bottom, greyed, partialTick);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    // Each head stands in its own cell, the first on the right, its feet below the band
    private void drawHeads(GuiGraphics graphics, FigureCollection collection, int right, int bottom, boolean greyed,
                           float partialTick) {
        if (heads == null) {
            heads = new FigureEntry(List.of(), collection.getId());
            heads.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, CAMERA_X, CAMERA_Y, 0.0f);
        }
        heads.setPreviewScale(collection.isPlush() ? 0.6f : 1.0f);
        int cellY = bottom - HEAD_SHOWN;
        for (int i = 0; i < headIds.size(); i++) {
            FigureDefinition figure = collection.getFigure(headIds.get(i)).orElse(null);
            if (figure == null) {
                continue;
            }
            int cellX = right - PAD - HEAD_CELL / 2 - HEAD_CELL / 2 - i * HEAD_STEP;
            UiViewport.drawFigure(graphics, heads, figure, cellX, cellY, HEAD_CELL, partialTick, greyed);
        }
    }

    // A bold name cut to the width; bold letters are wider than the plain ones Draw.cut measures
    private static void drawName(GuiGraphics graphics, Font font, String name, int x, int y, int width, int colour) {
        Component bold = Component.literal(name).withStyle(ChatFormatting.BOLD);
        if (font.width(bold) > width) {
            String shown = name;
            do {
                shown = shown.substring(0, shown.length() - 1);
                bold = Component.literal(shown.stripTrailing() + "...").withStyle(ChatFormatting.BOLD);
            } while (!shown.isEmpty() && font.width(bold) > width);
        }
        Draw.string(graphics, font, bold, x, y, colour);
    }

    /** "8 figures · 7 in the draw · 1 retired", without the retired part if asked, or "9 figures · archived". */
    private Component stats(boolean withRetired) {
        Component figures = figureCount == 1 ? CreatorScreen.tr("card.figure_one")
                : CreatorScreen.tr("card.figures", figureCount);
        if (record != null && record.archived()) {
            return CreatorScreen.tr("card.joined", figures, CreatorScreen.tr("card.archived"));
        }
        Component line = CreatorScreen.tr("card.joined", figures, CreatorScreen.tr("card.in_draw", inDraw));
        return retired > 0 && withRetired
                ? CreatorScreen.tr("card.joined", line, CreatorScreen.tr("card.retired", retired)) : line;
    }

    private void drawBadge(GuiGraphics graphics, Font font, UiCardGrid.Card card, boolean buttons) {
        Component text;
        int background;
        int foreground;
        Component tooltip = null;
        if (record.archived()) {
            text = CreatorScreen.tr("card.badge.archived");
            background = Theme.BADGE_ARCHIVED_BG;
            foreground = Theme.BADGE_ARCHIVED_FG;
            tooltip = CreatorScreen.tr("card.badge.archived_tip");
        } else if (missingFiles > 0) {
            text = missingFiles == 1 ? CreatorScreen.tr("card.badge.missing_one")
                    : CreatorScreen.tr("card.badge.missing", missingFiles);
            background = Theme.BADGE_MISSING_BG;
            foreground = Theme.BADGE_MISSING_FG;
            tooltip = CreatorScreen.tr("card.badge.missing_tip");
        } else if (record.draft()) {
            text = CreatorScreen.tr("card.badge.draft");
            background = Theme.BADGE_DRAFT_BG;
            foreground = Theme.BADGE_DRAFT_FG;
            tooltip = CreatorScreen.tr("card.badge.draft_tip");
        } else {
            text = CreatorScreen.tr("card.badge.published");
            background = Theme.BADGE_PUBLISHED_BG;
            foreground = Theme.BADGE_PUBLISHED_FG;
        }
        int width = Draw.badgeWidth(font, text);
        int badgeX = card.x() + card.width() - 4 - width;
        int badgeY = card.y() + 4;
        if (buttons && moreButton != null && badgeX < moreButton.bounds().right() + 3) {
            // A narrow card puts the badge under the buttons rather than over them
            badgeY += UiButton.SMALL_HEIGHT + 3;
        }
        Draw.badge(graphics, font, text, badgeX, badgeY, background, foreground);
        if (tooltip != null && card.mouseX() >= badgeX && card.mouseX() < badgeX + width && card.mouseY() >= badgeY
                && card.mouseY() < badgeY + 12) {
            UiTooltip.set(tooltip);
        }
    }

    private static void drawNew(GuiGraphics graphics, UiCardGrid.Card card, @Nullable Component disabled) {
        Font font = Minecraft.getInstance().font;
        int x = card.x();
        int y = card.y();
        int width = card.width();
        int height = card.height();
        boolean enabled = disabled == null;
        boolean lit = enabled && (card.hovered() || card.selected());
        Surfaces.backdrop(graphics, x, y, x + width, y + height, lit ? NEW_BACKDROP_HOVER : NEW_BACKDROP);
        if (card.selected()) {
            Draw.outline(graphics, x, y, width, height, Theme.SELECTED);
        }
        int centerX = x + width / 2;
        int top = y + (height - (9 + 8 + 14 + 11 + 11)) / 2;
        PixelIcon.draw(graphics, PixelIcon.PLUS, centerX - PixelIcon.width(PixelIcon.PLUS) / 2, top,
                enabled ? Theme.TEXT : Theme.FAINT);
        top += 9 + 8;
        Component title = CreatorScreen.tr("home.new");
        int room = width - 2 * PAD;
        int titleWidth = Math.min(room, PixelFont.width(title, PixelFont.crispScale(1.0f)));
        Draw.heading(graphics, title, centerX - titleWidth / 2, top + 4, room, 1.0f,
                enabled ? Theme.TEXT : Theme.FAINT);
        top += 14;
        Draw.centred(graphics, font, CreatorScreen.tr("home.new_hint").getString(), centerX, top, room,
                enabled ? NEW_HINT : Theme.FAINT);
        top += Theme.LINE_HEIGHT;
        Draw.centred(graphics, font, CreatorScreen.tr("home.new_key").getString(), centerX, top, room, Theme.FAINT);
        if (!enabled && card.hovered()) {
            UiTooltip.set(disabled);
        }
    }
}
