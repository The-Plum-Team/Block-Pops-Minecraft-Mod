package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A dialog that stands on the part of the screen it belongs to, with a tail pointing down at it,
 * as the publish popover stands on its button. It is drawn as the menus' dialogs are
 * ({@link DialogFrame}): a header strip in an accent with a pixel icon and the title in the pixel
 * font, then a body of rows and one row of buttons, an optional one on the left and the others on
 * the right, each as wide as its label needs.
 *
 * <p>It is not a {@link UiModal}: a modal closes before its button's action runs, keeps the body
 * it was made with and centres buttons of one width. A popover's button decides itself whether
 * the popover closes, and its body follows what it shows: the filler given to {@link #content}
 * lays the rows out again when it opens, on every tick while it is open and on {@link #refresh}.
 * Buttons the filler adds are its own and go with the rows it replaces; buttons added outside it
 * stay. While a row is pressed, as a slider being dragged, the body keeps its nodes and is laid
 * out again from the filler once the press ends.
 *
 * <p>{@link CreatorScreen#openPopover} shows it above everything but the tooltip, in the one slot it
 * shares with modal dialogs, and dims the rest of the screen a little. While it is open nothing
 * behind it takes input: a click outside it, Escape or a cancel button cancels it, unless it is
 * {@link #hold held} open while it waits for something; Enter presses its primary button. Whatever
 * closes it, its {@link #onClose} runs, so a screen can undo what it showed while it was open.
 *
 * <p>It sits above its anchor and is kept on the screen. Its lines wrap to up to four lines while
 * it still stands on its anchor, and to fewer when that is what it takes; one with no room above
 * its anchor even then is pushed down to the screen's top and loses its tail. Rows past what the
 * screen holds are left out, and a last line says how many there are, with their text in its
 * tooltip, so the buttons always stay on the screen.
 */
public class UiPopover implements UiNode {
    /** The height of the popover's buttons. */
    public static final int BUTTON_HEIGHT = 18;
    /** The height of one line of text in the body. */
    public static final int LINE_HEIGHT = 13;
    private static final int PREFERRED_WIDTH = 300;
    private static final int PADDING = DialogFrame.PADDING;
    // From the header's line to the first row, and from the last row to the buttons
    private static final int BODY_TOP = 7;
    private static final int BODY_GAP = 6;
    private static final int BUTTON_GAP = 12;
    // The room kept free at the screen's edges, and how far the tail reaches below the frame
    private static final int SCREEN_EDGE = 4;
    private static final int TAIL = 5;
    // The tail keeps clear of the frame's corners
    private static final int TAIL_INSET = 12;
    // Theme.MODAL_DIM at half its alpha: the popover belongs to the screen, so it dims it less
    private static final int DIM = 0x40000000;
    // The most lines a line of text wraps to, and the fewest it is cut to before rows are left out
    private static final int MAX_LINES = 4;
    private static final int MIN_LINES = 2;

    /** A row of the body: a node and the height it takes, or a line of text that measures itself. */
    private record Row(UiNode.Box node, int height) {
    }

    private Component title;
    private final Supplier<Rect> anchor;
    private DialogFrame.Accent accent = DialogFrame.Accent.SETTINGS;
    private String[] icon = PixelIcon.BOX;
    private int preferredWidth = PREFERRED_WIDTH;
    // The popover's left edge stays at or right of this, as a panel's edge it lines up with
    private int leftEdge = SCREEN_EDGE;
    private final List<Row> rows = new ArrayList<>();
    private final List<UiButton> buttons = new ArrayList<>();
    // The buttons the filler added, which go when it lays the body out again
    private final List<UiButton> fillerButtons = new ArrayList<>();
    @Nullable
    private UiButton leftButton;
    private boolean leftFromFiller;
    @Nullable
    private UiButton primary;
    private boolean primaryFromFiller;
    @Nullable
    private Consumer<UiPopover> filler;
    private boolean filling;
    // The filler has news the body could not take while a row was pressed
    private boolean stale;
    @Nullable
    private Runnable onCancel;
    @Nullable
    private Runnable onClose;
    private boolean held;
    @Nullable
    private CreatorScreen host;
    // The row node that took the last press, which gets its drags and its release
    @Nullable
    private UiNode pressed;
    private int x;
    private int y;
    private int width;
    private int height;
    private int screenWidth;
    private int screenHeight;
    private boolean tail;
    // How many lines a line of text wraps to, how many rows show, and the line for the rest
    private int lineLimit = MAX_LINES;
    private int shownRows;
    @Nullable
    private Line overflow;

    /**
     * @param anchor where the popover belongs on the screen, asked again whenever it is laid out,
     *               so it follows a button the screen builds again
     */
    public UiPopover(Component title, Supplier<Rect> anchor) {
        this.title = title;
        this.anchor = anchor;
    }

    /** The header's colour and icon, which say what the popover does, as a dialog's do. */
    public UiPopover accent(DialogFrame.Accent accent, String[] icon) {
        this.accent = accent;
        this.icon = icon;
        return this;
    }

    /** The width the popover would like, kept within the screen; 300 unless set. */
    public UiPopover width(int width) {
        this.preferredWidth = width;
        return this;
    }

    /**
     * Keeps the popover's left edge at or right of this x, so it lines up with the panel its
     * anchor is on rather than hanging past it; the screen's edge unless set. The tail still
     * points at the anchor.
     */
    public UiPopover leftEdge(int x) {
        this.leftEdge = x;
        return this;
    }

    public void setTitle(Component title) {
        this.title = title;
    }

    /**
     * What lays the body's rows out: it runs with the rows cleared when the popover opens, on
     * every tick while it is open and on {@link #refresh}, so the body follows what it shows. The
     * buttons it adds are its own, removed with the rows each time it runs again.
     */
    public UiPopover content(@Nullable Consumer<UiPopover> filler) {
        this.filler = filler;
        return this;
    }

    /** What runs when the popover is cancelled, by Escape, a click outside it or a cancel button. */
    public UiPopover onCancel(@Nullable Runnable onCancel) {
        this.onCancel = onCancel;
        return this;
    }

    /**
     * What runs whenever the popover closes, whatever closes it: one of its buttons, a cancel,
     * or a dialog or another popover opened over the screen. A screen puts back here what it
     * showed while the popover was open, such as its button drawn pressed.
     */
    public UiPopover onClose(@Nullable Runnable onClose) {
        this.onClose = onClose;
        return this;
    }

    // Body

    /** Adds a line of text in a colour, wrapped to as many lines as the popover has room for. */
    public Line line(Component text, int colour) {
        Line line = new Line(text, colour, null);
        rows.add(new Row(line, -1));
        return line;
    }

    /** Adds a line that runs an action when clicked, such as a problem that leads to its fix. */
    public Line line(Component text, int colour, Runnable action) {
        Line line = new Line(text, colour, action);
        rows.add(new Row(line, -1));
        return line;
    }

    /** Adds a node in a row of its own of the given height. */
    public <T extends UiNode.Box> T node(T node, int rowHeight) {
        rows.add(new Row(node, Math.max(0, rowHeight)));
        return node;
    }

    /**
     * Removes every row, and the buttons the filler added, so the filler given to
     * {@link #content} starts from none.
     */
    public void clearBody() {
        rows.clear();
        buttons.removeAll(fillerButtons);
        fillerButtons.clear();
        if (leftFromFiller) {
            leftButton = null;
            leftFromFiller = false;
        }
        if (primaryFromFiller) {
            primary = null;
            primaryFromFiller = false;
        }
        pressed = null;
    }

    // Buttons

    /** Adds a button on the right, after those added before it. Its action decides whether the popover closes. */
    public UiButton button(UiButton button) {
        buttons.add(button);
        if (filling) {
            fillerButtons.add(button);
        }
        return button;
    }

    /** Puts a button on the left of the row, apart from the others; null removes it. */
    @Nullable
    public UiButton leftButton(@Nullable UiButton button) {
        this.leftButton = button;
        this.leftFromFiller = filling && button != null;
        return button;
    }

    /** The button Enter presses; the last one on the right unless set. */
    public UiButton primary(UiButton button) {
        this.primary = button;
        this.primaryFromFiller = filling;
        return button;
    }

    // Opening and closing

    void attach(@Nullable CreatorScreen host) {
        this.host = host;
        if (host != null) {
            refresh();
        }
    }

    // CreatorScreen tells the popover it closed, whatever closed it
    void closed() {
        pressed = null;
        if (onClose != null) {
            onClose.run();
        }
    }

    /** Whether the popover is open on a screen now. */
    public boolean isOpen() {
        return host != null;
    }

    /**
     * Keeps the popover open, or lets it go again: while held, Escape and a click outside it do
     * nothing, as while it waits for an answer from the server.
     */
    public void hold(boolean held) {
        this.held = held;
    }

    public boolean isHeld() {
        return held;
    }

    /** Closes the popover without running its cancel action; its {@link #onClose} runs. */
    public void close() {
        if (host != null) {
            host.closePopover(this);
        }
    }

    /** Closes the popover and runs its cancel action, after its {@link #onClose}. */
    public void cancel() {
        close();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    /**
     * Lays the body out again from the filler, and the popover with it; for a change it shows.
     * While a row is pressed the body keeps its nodes, and the filler runs once the press ends.
     */
    public void refresh() {
        if (filler != null) {
            if (pressed != null) {
                stale = true;
            } else {
                clearBody();
                filling = true;
                try {
                    filler.accept(this);
                } finally {
                    filling = false;
                }
                stale = false;
            }
        }
        if (host != null) {
            place(host.width, host.height);
        }
    }

    // Layout

    /** Lays the popover out above its anchor on a screen of this size. {@link CreatorScreen} calls this. */
    void place(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        Font font = Minecraft.getInstance().font;
        this.width = Math.max(120, Math.min(preferredWidth, screenWidth - 2 * SCREEN_EDGE));
        int inner = width - 2 * PADDING;
        boolean withButtons = leftButton != null || !buttons.isEmpty();
        int frame = DialogFrame.HEADER_HEIGHT + 2 + Surfaces.LIP_HEIGHT + BODY_TOP
                + (withButtons ? BODY_GAP + BUTTON_HEIGHT : 0) + PADDING - 2;
        Rect at = anchor.get();
        // The body's room standing on the anchor, and at the screen's top
        int above = at.y() - TAIL - SCREEN_EDGE - frame;
        int most = screenHeight - 2 * SCREEN_EDGE - frame;
        int limit = MAX_LINES;
        while (limit > MIN_LINES && bodyHeight(font, inner, limit, rows.size()) > above) {
            limit--;
        }
        boolean standing = bodyHeight(font, inner, limit, rows.size()) <= above;
        if (!standing) {
            // At the screen's top anyway: the lines take what room the screen has
            limit = MAX_LINES;
            while (limit > MIN_LINES && bodyHeight(font, inner, limit, rows.size()) > most) {
                limit--;
            }
        }
        this.lineLimit = limit;
        int room = standing ? above : most;
        this.shownRows = rows.size();
        this.overflow = null;
        if (bodyHeight(font, inner, limit, shownRows) > room) {
            while (shownRows > 0 && bodyHeight(font, inner, limit, shownRows) + LINE_HEIGHT > room) {
                shownRows--;
            }
            this.overflow = overflowLine(font, rows.size() - shownRows);
        }
        int bodyHeight = bodyHeight(font, inner, limit, shownRows) + (overflow != null ? LINE_HEIGHT : 0);
        this.height = frame + bodyHeight;
        this.x = Math.max(Math.max(SCREEN_EDGE, leftEdge), Math.min(at.centerX() - width / 2,
                screenWidth - width - SCREEN_EDGE));
        this.x = Math.min(this.x, Math.max(SCREEN_EDGE, screenWidth - width - SCREEN_EDGE));
        this.tail = standing;
        this.y = standing ? at.y() - TAIL - height : SCREEN_EDGE;

        int top = DialogFrame.bodyTop(y) + BODY_TOP;
        for (int i = 0; i < shownRows; i++) {
            Row row = rows.get(i);
            if (row.node() instanceof Line line) {
                line.maxLines = limit;
            }
            int rowHeight = rowHeight(font, row, inner, limit);
            row.node().at(x + PADDING, top, inner, rowHeight);
            top += rowHeight;
        }
        if (overflow != null) {
            overflow.at(x + PADDING, top, inner, LINE_HEIGHT);
        }
        placeButtons(font, inner);
    }

    private int bodyHeight(Font font, int inner, int limit, int count) {
        int total = 0;
        for (int i = 0; i < count && i < rows.size(); i++) {
            total += rowHeight(font, rows.get(i), inner, limit);
        }
        return total;
    }

    private static int rowHeight(Font font, Row row, int inner, int limit) {
        if (row.height() >= 0) {
            return row.height();
        }
        return row.node() instanceof Line line ? line.lineCount(font, inner, limit) * LINE_HEIGHT : 0;
    }

    // "... +N" for the rows left out, with each one's whole text in the tooltip
    private Line overflowLine(Font font, int count) {
        List<Component> rest = new ArrayList<>();
        for (int i = shownRows; i < rows.size(); i++) {
            if (rows.get(i).node() instanceof Line line) {
                rest.addAll(SidebarScreen.tooltipLines(line.text));
            }
        }
        Line line = new Line(Component.literal("... +" + count), Theme.MUTED, null);
        line.maxLines = 1;
        line.tooltipLines = rest.isEmpty() ? null : List.copyOf(rest);
        return line;
    }

    // The left button at the left edge, the others right-aligned, each as wide as it needs;
    // a row too wide for the popover gives up room in proportion
    private void placeButtons(Font font, int inner) {
        int buttonY = DialogFrame.bodyBottom(y, height) - (PADDING - 2) - BUTTON_HEIGHT;
        List<UiButton> all = new ArrayList<>();
        if (leftButton != null) {
            all.add(leftButton);
        }
        all.addAll(buttons);
        if (all.isEmpty()) {
            return;
        }
        int[] widths = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            widths[i] = buttonWidth(font, all.get(i));
        }
        int gaps = BUTTON_GAP * (all.size() - 1);
        int[] fitted = UiButton.fitRow(inner - gaps, widths);
        int index = 0;
        if (leftButton != null) {
            leftButton.at(x + PADDING, buttonY, fitted[0], BUTTON_HEIGHT);
            index = 1;
        }
        int right = x + width - PADDING;
        for (int i = all.size() - 1; i >= index; i--) {
            UiButton button = all.get(i);
            int buttonWidth = fitted[i];
            // A link's text sits on the line of the raised buttons' labels, above their lips
            int lift = button.style() == UiButton.Style.LINK || button.style() == UiButton.Style.DANGER_TEXT ? 1 : 0;
            button.at(right - buttonWidth, buttonY - lift, buttonWidth, BUTTON_HEIGHT);
            right -= buttonWidth + BUTTON_GAP;
        }
    }

    private static int buttonWidth(Font font, UiButton button) {
        if (button.style() == UiButton.Style.LINK || button.style() == UiButton.Style.DANGER_TEXT) {
            return font.width(button.label());
        }
        // The main action a little wider, as it is the one green on the screen
        return button.naturalWidth() + (button.style() == UiButton.Style.PRIMARY ? 4 : 0);
    }

    @Override
    public Rect bounds() {
        return new Rect(x, y, width, height);
    }

    // The rows shown now, which a screen too short for all of them cuts
    private List<UiNode.Box> shownNodes() {
        List<UiNode.Box> shown = new ArrayList<>();
        for (int i = 0; i < shownRows && i < rows.size(); i++) {
            shown.add(rows.get(i).node());
        }
        if (overflow != null) {
            shown.add(overflow);
        }
        return shown;
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, screenWidth, screenHeight, DIM);
        DialogFrame.draw(graphics, x, y, width, height, accent, icon, Component.empty());
        // The title cut to the header's room after the icon, as a modal's is
        int titleX = x + 10 + PixelIcon.width(icon) + 6;
        float middle = (y + 2 + y + DialogFrame.HEADER_HEIGHT - 1) / 2 + 0.5f;
        Draw.heading(graphics, title, titleX, middle, Math.max(0, x + width - PADDING - titleX), 1.0f, Theme.TEXT);
        if (tail) {
            drawTail(graphics);
        }
        for (UiNode.Box node : shownNodes()) {
            node.draw(graphics, mouseX, mouseY, partialTick);
        }
        if (leftButton != null) {
            leftButton.draw(graphics, mouseX, mouseY, partialTick);
        }
        for (UiButton button : buttons) {
            button.draw(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * The tail: a triangle of the popover's face that opens through its lip and border, edged in
     * the border colour inside a black outline, its tip just above the anchor.
     */
    private void drawTail(GuiGraphics graphics) {
        Rect at = anchor.get();
        int centre = Math.max(x + TAIL_INSET, Math.min(at.centerX(), x + width - TAIL_INSET));
        int bottom = y + height;
        int tip = bottom + TAIL;
        for (int row = bottom - TAIL; row < tip; row++) {
            int half = tip - row;
            graphics.fill(centre - half - 1, row, centre + half + 1, row + 1, Surfaces.OUTLINE);
            graphics.fill(centre - half, row, centre + half, row + 1, Surfaces.BORDER);
            if (half > 1) {
                graphics.fill(centre - half + 1, row, centre + half - 1, row + 1, Surfaces.DIALOG_FACE);
            }
        }
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        pressed = null;
        if (!bounds().contains(click.x(), click.y())) {
            if (!held) {
                cancel();
            }
            return true;
        }
        if (leftButton != null && leftButton.click(click)) {
            return true;
        }
        for (UiButton button : List.copyOf(buttons)) {
            if (button.click(click)) {
                return true;
            }
        }
        for (UiNode.Box node : shownNodes()) {
            if (node.visible() && node.click(click)) {
                pressed = node;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        return pressed != null && pressed.drag(click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        UiNode node = pressed;
        pressed = null;
        boolean used = node != null && node.release(click);
        if (stale && host != null) {
            // What the filler had to show while the row was pressed
            refresh();
        }
        return used;
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (key.escape()) {
            if (!held) {
                cancel();
            }
            return true;
        }
        if (key.enter()) {
            UiButton chosen = primary != null ? primary : buttons.isEmpty() ? null : buttons.get(buttons.size() - 1);
            if (chosen != null) {
                chosen.press();
            }
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        refresh();
        for (Row row : List.copyOf(rows)) {
            row.node().tick();
        }
    }

    /**
     * A line of the body in one colour, after an optional pixel icon: wrapped to as many lines as
     * the popover has room for and cut after them, with the whole text in its tooltip when cut.
     * Given an action it is a link to something, such as a problem that leads to its fix:
     * underlined under the mouse, it clicks.
     */
    public static final class Line extends UiNode.Box {
        private static final int GLYPH_ROOM = 12;
        private final Component text;
        private final int colour;
        @Nullable
        private final Runnable action;
        @Nullable
        private String[] glyph;
        @Nullable
        private Component tooltip;
        // A tooltip of several lines, as the line for the rows left out lists theirs
        @Nullable
        private List<Component> tooltipLines;
        // How many lines the popover gives it now
        private int maxLines = MIN_LINES;

        private Line(Component text, int colour, @Nullable Runnable action) {
            this.text = text;
            this.colour = colour;
            this.action = action;
        }

        /** A pixel icon before the text, in the text's colour, such as a check mark. */
        public Line glyph(@Nullable String[] glyph) {
            this.glyph = glyph;
            return this;
        }

        /** A tooltip for the line, in place of the whole text a cut line shows. */
        public Line tooltip(@Nullable Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        private int textX() {
            return x + (glyph != null ? GLYPH_ROOM : 0);
        }

        private int textWidth() {
            return Math.max(1, width - (glyph != null ? GLYPH_ROOM : 0));
        }

        int lineCount(Font font, int rowWidth, int limit) {
            int room = Math.max(1, rowWidth - (glyph != null ? GLYPH_ROOM : 0));
            return Math.max(1, Draw.fit(font, text.getString(), room, limit).size());
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean hovered = hovered(mouseX, mouseY);
            String whole = text.getString();
            List<String> shown = Draw.fit(font, whole, textWidth(), maxLines);
            int top = y + (LINE_HEIGHT - 8) / 2;
            if (glyph != null) {
                PixelIcon.draw(graphics, glyph, x, top - 1, colour);
            }
            int lineY = top;
            for (String part : shown) {
                Draw.string(graphics, font, part, textX(), lineY, colour);
                if (hovered && action != null) {
                    graphics.fill(textX(), lineY + 9, textX() + font.width(part), lineY + 10, colour);
                }
                lineY += LINE_HEIGHT;
            }
            if (hovered) {
                if (tooltipLines != null) {
                    UiTooltip.set(tooltipLines);
                } else if (tooltip != null) {
                    UiTooltip.set(SidebarScreen.tooltipLines(tooltip));
                } else if (!String.join(" ", shown).equals(whole)) {
                    UiTooltip.set(SidebarScreen.tooltipLines(text));
                }
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (action == null || !click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            action.run();
            return true;
        }
    }
}
