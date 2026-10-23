package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.FigureDraw;
import com.theplumteam.client.gui.widget.CollectionEntry;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * The claw machine's screen as it will show a collection, drawn at full size inside a creator
 * screen: on the left the list of collections, with two of the machine's real ones dimmed above
 * this one, selected; on the right the collection's name, its count of figures and its figure
 * grid on the collection's colour. The grid is the machine's own list and cells, so it looks
 * exactly as the machine will draw it, and can be scrolled.
 *
 * <p>The host decides which figures count as discovered (see {@link #setDiscovered}), to show
 * the collection as a new player or as a collector sees it, and can play a test draw on the grid
 * (see {@link #testDraw}): the machine's own light runs over the figures and its result card
 * shows the winner, with a stamp saying that nothing was spent.
 *
 * <p>Where there is no room for the grid's four columns of cells, or in compact mode, as in the
 * new-collection wizard, the grid is replaced by a line of text. The replica is never scaled down,
 * because before 1.21.4 a clip ignores scaled drawing, and must not sit in a {@link UiScrollPane}:
 * its grid clips itself in screen units. A screen showing a replica calls {@link #close} when it
 * closes, which ends a test draw still playing.
 */
public class UiMachineReplica extends UiNode.Box {
    // The machine's layout: the list's rows and the grid's rows and cells
    private static final int CARD_HEIGHT = 55;
    private static final int GRID_ROW = 90;
    // Four cells of 80 with gaps of 4, the list's margin of 8 and its scroll bar
    private static final int GRID_MIN_WIDTH = 4 * 80 + 3 * 4 + 8 + 8;
    private static final int FRAME = 6;
    private static final int PADDING = 6;
    private static final int GAP = 6;
    private static final int HEADER = 30;
    private static final int MIN_LEFT = 150;
    private static final int PANEL = 0xFF050505;
    private static final int DIMMED = 0xA0050505;
    private static final int RULE = 0x40FFFFFF;
    private static final int TOKENS = 0xFF5599FF;
    // How many of the machine's real collections are shown above this one
    private static final int REAL_CARDS = 2;

    @Nullable
    private String collectionId;
    // The real collection a preview stands for, which the machine would not list twice
    @Nullable
    private String sourceId;
    private boolean compact;
    @Nullable
    private Component compactNote;
    @Nullable
    private Predicate<String> discovered;
    @Nullable
    private Component stamp = CreatorScreen.tr("test_draw.stamp");
    @Nullable
    private FigureListWidget list;
    // The grid's bounds the list was made for, and the collection it shows
    private UiNode.Rect listArea = UiNode.Rect.EMPTY;
    @Nullable
    private FigureCollection listCollection;
    private boolean listPressed;

    /** Shows a collection by id: a creator preview's id or any registered collection's. */
    public void setCollection(@Nullable String collectionId) {
        this.collectionId = collectionId;
    }

    /**
     * For a preview of a collection already on the server, its real id: the machine's real
     * collections shown beside the preview and counted with it leave that one out. Null for none.
     */
    public void setSourceId(@Nullable String sourceId) {
        this.sourceId = sourceId;
    }

    /** Replaces the figure grid with a line of text, as the new-collection wizard shows it. */
    public void setCompact(boolean compact) {
        this.compact = compact;
    }

    /** The line shown in place of the grid; null for the count of figures. */
    public void setCompactNote(@Nullable Component note) {
        this.compactNote = note;
    }

    /**
     * Decides which figures the grid shows as discovered, asked with "collection:figure", such
     * as none for a new player and all for a collector; null for the client's own record.
     */
    public void setDiscovered(@Nullable Predicate<String> discovered) {
        this.discovered = discovered;
        if (list != null) {
            list.setDiscovered(discovered);
        }
    }

    /** The stamp on a test draw's result card; null for none. */
    public void setStamp(@Nullable Component stamp) {
        this.stamp = stamp;
    }

    /**
     * Plays a draw on the grid that stops on this figure, made up on the client: nothing is
     * spent and nothing is handed out. A holographic winner is shown as a real draw shows one.
     * Returns false when the grid is not shown.
     */
    public boolean testDraw(FigureDefinition winner, boolean isNew, boolean holographic) {
        if (list == null || listCollection == null || !showsGrid()) {
            return false;
        }
        FigureDraw.preview(list, listCollection, winner, isNew, holographic);
        return true;
    }

    /** Whether a test draw is playing or showing its figure on this replica. */
    public boolean isDrawing() {
        return list != null && FigureDraw.playsOn(list);
    }

    /** Ends a test draw still playing on this replica; the host screen calls this as it closes. */
    public void close() {
        if (list != null) {
            FigureDraw.stopOn(list);
        }
    }

    @Nullable
    private FigureCollection collection() {
        return collectionId != null ? CollectionRegistry.getCollection(collectionId).orElse(null) : null;
    }

    // The machine's real collections of the same kind, as its list would offer them, leaving out
    // the one shown and the one it previews
    private List<FigureCollection> realCollections(boolean plush) {
        Collection<FigureCollection> all = plush ? CollectionRegistry.getPlushCollections()
                : CollectionRegistry.getAllCollections();
        List<FigureCollection> offered = new ArrayList<>();
        for (FigureCollection collection : all) {
            if (!"default".equals(collection.getId()) && !CreatorPreview.isPreviewId(collection.getId())
                    && !collection.getId().equals(collectionId) && !collection.getId().equals(sourceId)
                    && ClientWorldCollections.isOffered(collection.getId())) {
                offered.add(collection);
            }
        }
        return offered;
    }

    // Layout

    private int innerX() {
        return x + FRAME + PADDING;
    }

    private int innerWidth() {
        return Math.max(0, width - 2 * (FRAME + PADDING));
    }

    private int leftWidth() {
        int inner = innerWidth();
        if (compact) {
            return Math.min(MIN_LEFT, inner / 2);
        }
        // The machine gives its list 45%; the grid keeps the room for its four columns
        return Math.max(Math.min(MIN_LEFT, inner / 2), Math.min((int) (inner * 0.45f), inner - GAP - GRID_MIN_WIDTH));
    }

    private int rightX() {
        return innerX() + leftWidth() + GAP;
    }

    private int rightWidth() {
        return Math.max(0, innerX() + innerWidth() - rightX());
    }

    private boolean showsGrid() {
        return !compact && rightWidth() >= GRID_MIN_WIDTH && height - 2 * (FRAME + PADDING) - HEADER >= GRID_ROW;
    }

    private UiNode.Rect gridArea() {
        int top = y + FRAME + PADDING + HEADER;
        return new UiNode.Rect(rightX(), top, rightWidth(), Math.max(0, y + height - FRAME - PADDING - top));
    }

    // The list is made again when its place changes, and shown the collection again when it changes
    private void updateList(@Nullable FigureCollection collection) {
        UiNode.Rect area = gridArea();
        if (list == null || !area.equals(listArea)) {
            if (list != null) {
                FigureDraw.stopOn(list);
            }
            list = new FigureListWidget(Minecraft.getInstance(), area.width(), area.height(), area.y(), GRID_ROW);
            list.setLeftPos(area.x());
            list.setRenderBackground(false);
            list.setRenderTopAndBottom(false);
            list.setDiscovered(discovered);
            listArea = area;
            listCollection = null;
        }
        if (collection != listCollection && !FigureDraw.playsOn(list)) {
            list.setCollection(collection);
            listCollection = collection;
        }
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        FigureCollection collection = collection();
        int colour = UiViewport.collectionColour(collection, 0xFF3A3A3A);
        // The machine's background in the collection's colour, around its dark panel
        graphics.fill(x, y, x + width, y + height, colour);
        Draw.panel(graphics, x + FRAME, y + FRAME, width - 2 * FRAME, height - 2 * FRAME, PANEL, Theme.PANEL_OUTLINE);
        drawCollections(graphics, font, collection, mouseX, mouseY);
        if (collection == null) {
            return;
        }
        drawFigures(graphics, font, collection, colour, mouseX, mouseY, partialTick);
    }

    private void drawCollections(GuiGraphics graphics, Font font, @Nullable FigureCollection collection,
                                 int mouseX, int mouseY) {
        int left = innerX();
        int columnWidth = leftWidth();
        int top = y + FRAME + PADDING;
        int bottom = y + height - FRAME - PADDING;
        boolean plush = collection != null && collection.isPlush();
        // As many real collections as leave room for this one and the tokens line
        int room = (bottom - top - HEADER - Theme.LINE_HEIGHT) / CARD_HEIGHT - (collection != null ? 1 : 0);
        List<FigureCollection> offered = realCollections(plush);
        List<FigureCollection> real = offered.subList(0, Math.max(0, Math.min(Math.min(REAL_CARDS, room),
                offered.size())));
        // As many as the machine will offer once this one is on it
        int available = offered.size() + (collection != null ? 1 : 0);

        Draw.text(graphics, font, tr("replica.collections").getString(), left + 4, top + 4, columnWidth - 8,
                Theme.TEXT);
        Draw.text(graphics, font, tr("replica.available", available).getString(), left + 4,
                top + 4 + font.lineHeight + 4, columnWidth - 8, Theme.MUTED);
        int ruleY = top + 4 + 2 * (font.lineHeight + 4);
        graphics.fill(left + 4, ruleY, left + columnWidth - 4, ruleY + 1, RULE);

        int cardY = top + HEADER + 4;
        for (FigureCollection other : real) {
            drawCard(graphics, font, other, left, cardY, columnWidth, false, false, FigureEntry.DEFAULT_DISCOVERED);
            // The real ones stand back, so this collection is the one that reads
            graphics.fill(left, cardY - 2, left + columnWidth, cardY + CARD_HEIGHT - 2, DIMMED);
            cardY += CARD_HEIGHT;
        }
        if (collection != null) {
            boolean hovered = mouseX >= left && mouseX < left + columnWidth && mouseY >= cardY - 2
                    && mouseY < cardY + CARD_HEIGHT - 2;
            // Its count of discovered figures follows who the grid shows it to
            drawCard(graphics, font, collection, left, cardY, columnWidth, true, hovered,
                    discovered != null ? discovered : FigureEntry.DEFAULT_DISCOVERED);
        }
        Draw.centred(graphics, font, tr("replica.tokens").getString(), left + columnWidth / 2,
                bottom - font.lineHeight, columnWidth - 4, TOKENS);
    }

    // A card cut to the column, which may be narrower than the machine's
    private static void drawCard(GuiGraphics graphics, Font font, FigureCollection collection, int left, int top,
                                 int columnWidth, boolean selected, boolean hovered, Predicate<String> discovered) {
        UiScrollPane.clip(graphics, left, top - 2, left + columnWidth, top + CARD_HEIGHT - 2);
        try {
            CollectionEntry.drawCard(graphics, font, collection, left + 4, top, columnWidth + 6, CARD_HEIGHT - 4,
                    selected, hovered, discovered);
        } finally {
            graphics.disableScissor();
        }
    }

    private void drawFigures(GuiGraphics graphics, Font font, FigureCollection collection, int colour, int mouseX,
                             int mouseY, float partialTick) {
        int left = rightX();
        int columnWidth = rightWidth();
        int top = y + FRAME + PADDING;
        Draw.text(graphics, font, collection.getName(), left + 4, top + 4, columnWidth - 8, Theme.TEXT);
        Component count = collection.hasSpecials()
                ? tr("replica.figures_specials", collection.getRegularFigures().size(),
                        collection.getSpecialFigures().size())
                : tr("replica.figures", collection.getRegularFigures().size());
        Draw.text(graphics, font, count.getString(), left + 4, top + 4 + font.lineHeight + 4, columnWidth - 8,
                Theme.MUTED);
        int ruleY = top + 4 + 2 * (font.lineHeight + 4);
        graphics.fill(left + 4, ruleY, left + columnWidth - 4, ruleY + 1, RULE);

        UiNode.Rect area = gridArea();
        graphics.fill(area.x(), area.y(), area.right(), area.bottom(), colour);
        if (!showsGrid()) {
            Component note = compactNote != null ? compactNote
                    : tr("replica.compact", collection.getRegularFigures().size());
            Draw.lines(graphics, font, note.getString(), area.x() + 8, area.y() + area.height() / 2 - 8,
                    area.width() - 16, 2, 0xFF000000 | textOn(colour));
            return;
        }
        updateList(collection);
        if (list != null) {
            list.render(graphics, mouseX, mouseY, partialTick);
            FigureDraw.drawResultCard(graphics, font, list, partialTick, stamp);
        }
    }

    // Dark text on a light colour, light text on a dark one
    private static int textOn(int colour) {
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        return red * 299 + green * 587 + blue * 114 > 160_000 ? 0x202020 : 0xFFFFFF;
    }

    private static Component tr(String key, Object... args) {
        return CreatorScreen.tr(key, args);
    }

    // Input

    private boolean overGrid(double mouseX, double mouseY) {
        return list != null && showsGrid() && listArea.contains(mouseX, mouseY);
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !bounds().contains(click.x(), click.y())) {
            return false;
        }
        if (list != null && FigureDraw.playsOn(list) && FigureDraw.result() != null) {
            // As on the machine, a click puts the shown figure away
            FigureDraw.dismissResult();
            return true;
        }
        if (overGrid(click.x(), click.y()) && !FigureDraw.isBusy()) {
            listPressed = UiInput.forwardClick(list, click);
        }
        // The replica is a picture: a click on it reaches nothing below
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        return listPressed && list != null && UiInput.forwardDrag(list, click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        boolean pressed = listPressed;
        listPressed = false;
        return pressed && list != null && UiInput.forwardRelease(list, click);
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (!overGrid(mouseX, mouseY)) {
            return false;
        }
        // The light's own scrolling is not fought while a draw plays
        if (!FigureDraw.isBusy()) {
            UiInput.forwardScroll(list, mouseX, mouseY, amount);
        }
        return true;
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (key.escape() && list != null && FigureDraw.playsOn(list) && FigureDraw.result() != null) {
            FigureDraw.dismissResult();
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        // The machine's screen advances its draws; here the replica advances its own
        if (list != null && FigureDraw.playsOn(list)) {
            FigureDraw.tick();
        }
    }
}
