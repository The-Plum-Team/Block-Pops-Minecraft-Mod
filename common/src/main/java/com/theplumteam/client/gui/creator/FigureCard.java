package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiCardGrid;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.UiViewport;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Draws the cards of the editor's figure grid. A card shows its figure as the machine screen
 * does, from the editor's preview of the draft, on a cell of the collection's colour, so a file
 * picked but not yet uploaded shows at once; under it comes the figure's name, and at the top
 * whether it is a skin or a 3D model and its size.
 *
 * <p>A retired figure is drawn in black and white under a red "RETIRED" ribbon; an amber dot
 * marks a figure with unsaved changes, and a red "!" in the corner one with a problem (a file
 * still to choose, lost on the server, that could not be loaded or that breaks a rule), which
 * its tooltip names. A figure that misses a file has nothing to preview yet and shows a "?".
 * The selected card has a gold frame.
 */
final class FigureCard {
    /** The height of every card. */
    static final int HEIGHT = 72;
    // The figure's cell: inset from the card's sides, under the kind and size tags
    private static final int CELL_SIDE = 2;
    private static final int CELL_TOP = 6;
    private static final int NAME_FROM_BOTTOM = 11;
    // The machine screen's camera, as the figure grid draws figures
    private static final float CAMERA_YAW = 150.0f;
    private static final float CAMERA_X = 0.0f;
    private static final float CAMERA_Y = 15.0f;
    // A plush is about twice as wide as a figure, as the machine's grid shrinks it
    private static final float PLUSH_SCALE = 0.6f;
    // The tags are drawn at half size, as small as the mockup's
    private static final float TAG_SCALE = 0.5f;
    private static final int TAG_TEXT = 0xFFBBBBBB;
    private static final int TAG_FILL = 0xFF2A2A2A;
    private static final int HOVER_OUTLINE = 0xFF6A6A6A;
    private static final int RIBBON = 0xFFA01818;
    private static final int RETIRED_WASH = 0x66000000;
    private static final int DRAGGED_WASH = 0x80000000;
    private static final int RETIRED_NAME = 0xFF9A9A9A;
    private static final int PROBLEM = 0xFFE03C3C;
    private static final int CELL_EDGE = 0x30FFFFFF;

    // Draws the figures; made again for another collection, since it names its collection
    @Nullable
    private FigureEntry entry;
    @Nullable
    private String entryCollectionId;

    /**
     * Draws one card.
     *
     * @param preview  the editor's preview of the draft, or null while it has none
     * @param colour   the collection's background colour, opaque
     * @param dirty    whether the figure has unsaved changes
     * @param problems what is wrong with the figure, empty when nothing is
     */
    void draw(GuiGraphics graphics, FigureDraft figure, UiCardGrid.Card card, @Nullable FigureCollection preview,
              int colour, boolean dirty, List<Component> problems, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int x = card.x();
        int y = card.y();
        int width = card.width();
        int height = card.height();
        boolean retired = !figure.enabled();
        int outline = card.selected() ? Theme.GOLD : card.hovered() ? HOVER_OUTLINE : 0xFF2C2C2C;
        Draw.panel(graphics, x, y, width, height, 0xFF151515, outline);
        if (card.selected()) {
            // Two units thick, as the mockup frames the selected card
            Draw.outline(graphics, x + 1, y + 1, width - 2, height - 2, Theme.GOLD);
        }

        int cell = Math.max(8, Math.min(width - 2 * CELL_SIDE, height - CELL_TOP - NAME_FROM_BOTTOM - 1));
        int cellX = x + (width - cell) / 2;
        int cellY = y + CELL_TOP;
        graphics.fill(cellX, cellY, cellX + cell, cellY + cell, darken(colour, retired ? 0.2f : 0.35f));
        Draw.outline(graphics, cellX, cellY, cell, cell, CELL_EDGE);
        FigureDefinition shown = preview != null ? preview.getFigure(figure.id()).orElse(null) : null;
        if (shown != null && figure.isComplete()) {
            FigureEntry figures = entry(preview);
            figures.setPreviewScale(preview.isPlush() ? PLUSH_SCALE : 1.0f);
            UiViewport.drawFigure(graphics, figures, shown, cellX, cellY, cell, partialTick, retired);
        }

        // The tags, the ribbon and the marks go above the model: a later stratum from 1.21.6,
        // depth before it, where the model would otherwise cover them
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        try {
            if (shown == null || !figure.isComplete()) {
                Draw.centred(graphics, font, "?", cellX + cell / 2, cellY + (cell - 8) / 2, cell, Theme.FAINT);
            }
            if (retired) {
                graphics.fill(cellX, cellY, cellX + cell, cellY + cell, RETIRED_WASH);
                int ribbonY = cellY + cell / 2 - 5;
                graphics.fill(x + 1, ribbonY, x + width - 1, ribbonY + 10, RIBBON);
                Draw.centred(graphics, font, CreatorScreen.tr("figures.retired_ribbon").getString(), x + width / 2,
                        ribbonY + 1, width - 4, Theme.TEXT);
            }
            drawTags(graphics, font, figure, x, y, width);
            int nameY = y + height - NAME_FROM_BOTTOM + 1;
            int nameRoom = width - 2 * 6;
            String name = figure.name().isBlank() ? figure.id() : figure.name();
            String cut = Draw.cut(font, name, nameRoom);
            graphics.drawString(font, cut, x + (width - font.width(cut)) / 2, nameY,
                    retired ? RETIRED_NAME : Theme.TEXT, false);
            if (!cut.equals(name) && within(card, x, nameY - 1, width, 10)) {
                UiTooltip.set(Component.literal(name));
            }
            if (dirty) {
                Draw.dot(graphics, x + width - 4, y + height - 4, 3, Theme.AMBER);
            }
            if (!problems.isEmpty()) {
                drawProblem(graphics, font, card, problems, x + 2, cellY + cell - 10);
            }
            if (card.dragged()) {
                graphics.fill(x, y, x + width, y + height, DRAGGED_WASH);
            }
        } finally {
            GuiPose.pop(graphics);
        }
    }

    private FigureEntry entry(FigureCollection preview) {
        if (entry == null || !preview.getId().equals(entryCollectionId)) {
            entry = new FigureEntry(List.of(), preview.getId());
            entry.setConfiguration(1.0f, 0.0f, CAMERA_YAW, 0.0f, CAMERA_X, CAMERA_Y, 0.0f);
            entryCollectionId = preview.getId();
        }
        return entry;
    }

    // "SKIN" or "MODEL" at the top left, the size at the top right, both at half size
    private static void drawTags(GuiGraphics graphics, Font font, FigureDraft figure, int x, int y, int width) {
        String kind = CreatorScreen.tr(figure.kind() == Kind.SKIN ? "figures.tag_skin" : "figures.tag_model")
                .getString();
        smallText(graphics, font, kind, x + 3, y + 2, TAG_TEXT);
        String size = scaleText(figure.scale());
        int sizeWidth = Math.round(font.width(size) * TAG_SCALE) + 4;
        int sizeX = x + width - 2 - sizeWidth;
        graphics.fill(sizeX, y + 1, sizeX + sizeWidth, y + 7, TAG_FILL);
        smallText(graphics, font, size, sizeX + 2, y + 2, Theme.TEXT_SOFT);
    }

    // A red "!" square whose tooltip lists the figure's problems
    private static void drawProblem(GuiGraphics graphics, Font font, UiCardGrid.Card card, List<Component> problems,
                                    int left, int top) {
        graphics.fill(left, top, left + 9, top + 9, PROBLEM);
        graphics.drawString(font, "!", left + 4 - font.width("!") / 2 + 1, top + 1, Theme.TEXT, false);
        if (within(card, left, top, 9, 9)) {
            UiTooltip.set(problems);
        }
    }

    private static boolean within(UiCardGrid.Card card, int left, int top, int width, int height) {
        return card.mouseX() >= left && card.mouseX() < left + width && card.mouseY() >= top
                && card.mouseY() < top + height;
    }

    private static void smallText(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y);
        GuiPose.scale(graphics, TAG_SCALE, TAG_SCALE);
        graphics.drawString(font, text, 0, 0, colour, false);
        GuiPose.pop(graphics);
    }

    /** A size as a card's tag shows it, such as "1x" or "1.25x". */
    static String scaleText(float scale) {
        String text = String.format(Locale.ROOT, "%.2f", scale);
        // 0.50 reads 0.5 and 1.00 reads 1, while 0.75 and 1.25 keep both decimals
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "x";
    }

    private static int darken(int colour, float keep) {
        int red = (int) (((colour >> 16) & 0xFF) * keep);
        int green = (int) (((colour >> 8) & 0xFF) * keep);
        int blue = (int) ((colour & 0xFF) * keep);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
