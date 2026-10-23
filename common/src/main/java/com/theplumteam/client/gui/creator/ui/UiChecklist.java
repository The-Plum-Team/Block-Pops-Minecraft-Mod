package com.theplumteam.client.gui.creator.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of what is ready and what is not, as the publish screen's checklist: each row is a
 * mark (done, a warning, a blocker or still to do) and a line of text in the mark's colour,
 * wrapped onto up to three lines. A row can end in a link, such as "View", that takes the
 * operator to what the row is about.
 */
public class UiChecklist extends UiNode.Box {
    /** How a row stands, with its glyph and colour. */
    public enum Mark {
        DONE("✔", 0xFF9CFF8A),
        WARNING("⚠", 0xFFFFCC66),
        BLOCKER("✖", 0xFFFF7A7A),
        TODO("○", Theme.MUTED);

        final String glyph;
        final int colour;

        Mark(String glyph, int colour) {
            this.glyph = glyph;
            this.colour = colour;
        }
    }

    private static final int MARK_WIDTH = 12;
    private static final int ROW_GAP = 3;
    private static final int MAX_LINES = 3;
    private static final String SEPARATOR = " · ";

    private record Row(Mark mark, Component text, @Nullable Component link, @Nullable Runnable onLink) {
    }

    /** Where a row's lines and link went, at the present width. */
    private record Placed(Row row, List<String> lines, int top, @Nullable Rect link) {
    }

    private final List<Row> rows = new ArrayList<>();

    public UiChecklist row(Mark mark, Component text) {
        rows.add(new Row(mark, text, null, null));
        return this;
    }

    /** A row ending in a link that runs an action, such as showing the figure the row is about. */
    public UiChecklist row(Mark mark, Component text, Component link, Runnable onLink) {
        rows.add(new Row(mark, text, link, onLink));
        return this;
    }

    public void clear() {
        rows.clear();
    }

    /** Whether any row is a blocker, which a screen uses to hold back its main action. */
    public boolean blocked() {
        for (Row row : rows) {
            if (row.mark() == Mark.BLOCKER) {
                return true;
            }
        }
        return false;
    }

    /** The height the rows take at a width, so a screen can lay out what follows the list. */
    public int heightFor(int forWidth) {
        List<Placed> placed = layout(Minecraft.getInstance().font, x, y, forWidth);
        if (placed.isEmpty()) {
            return 0;
        }
        Placed last = placed.get(placed.size() - 1);
        return last.top() - y + lineCount(last) * Theme.LINE_HEIGHT;
    }

    private static int lineCount(Placed placed) {
        boolean ownLine = placed.link() != null && placed.link().y() >= placed.top() + placed.lines().size()
                * Theme.LINE_HEIGHT;
        return placed.lines().size() + (ownLine ? 1 : 0);
    }

    // Wraps each row and finds its link: after the last line when it fits there, else under it
    private List<Placed> layout(Font font, int left, int top, int forWidth) {
        List<Placed> placed = new ArrayList<>();
        int textX = left + MARK_WIDTH;
        int textWidth = Math.max(20, forWidth - MARK_WIDTH);
        for (Row row : rows) {
            List<String> lines = Draw.fit(font, row.text().getString(), textWidth, MAX_LINES);
            Rect link = null;
            if (row.link() != null) {
                int linkWidth = font.width(row.link());
                String last = lines.get(lines.size() - 1);
                int after = font.width(last + SEPARATOR);
                if (after + linkWidth <= textWidth) {
                    link = new Rect(textX + after, top + (lines.size() - 1) * Theme.LINE_HEIGHT, linkWidth, 10);
                } else {
                    link = new Rect(textX, top + lines.size() * Theme.LINE_HEIGHT, linkWidth, 10);
                }
            }
            Placed one = new Placed(row, lines, top, link);
            placed.add(one);
            top += lineCount(one) * Theme.LINE_HEIGHT + ROW_GAP;
        }
        return placed;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int textX = x + MARK_WIDTH;
        for (Placed placed : layout(font, x, y, width)) {
            int colour = placed.row().mark().colour;
            graphics.drawString(font, placed.row().mark().glyph, x, placed.top(), colour, false);
            int lineY = placed.top();
            for (String line : placed.lines()) {
                graphics.drawString(font, line, textX, lineY, colour, false);
                lineY += Theme.LINE_HEIGHT;
            }
            Rect link = placed.link();
            if (link != null) {
                if (link.x() > textX) {
                    String last = placed.lines().get(placed.lines().size() - 1);
                    graphics.drawString(font, SEPARATOR, textX + font.width(last), link.y(), colour, false);
                }
                boolean hovered = link.contains(mouseX, mouseY);
                int linkColour = hovered ? Theme.GOLD_LIGHT : Theme.GOLD;
                graphics.drawString(font, placed.row().link(), link.x(), link.y(), linkColour, false);
                graphics.fill(link.x(), link.y() + 9, link.right(), link.y() + 10, linkColour);
            }
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        for (Placed placed : layout(Minecraft.getInstance().font, x, y, width)) {
            Rect link = placed.link();
            if (link != null && link.contains(click.x(), click.y()) && placed.row().onLink() != null) {
                placed.row().onLink().run();
                return true;
            }
        }
        return false;
    }
}
