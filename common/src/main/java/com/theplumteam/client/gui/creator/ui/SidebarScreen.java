package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A creator screen in the layout of Quick Skin's settings: a sidebar panel on the left, with a
 * heading, a list, a one-line status slot and one button at its foot, and one content panel on
 * the right, both raised charcoal panels on the creator's starry background. Subclasses fill the
 * two panels in {@link #buildSidebar} and {@link #buildContent} from the geometry this class
 * works out for the canvas, and draw their labels in {@link #drawSidebar} and
 * {@link #drawContent}.
 *
 * <p>The panels are laid out from the canvas the menus' scale gives: 640x360 GUI units in most
 * windows, 400x300 in an 800x600 one and 427x240 in Minecraft's default window. The content is
 * {@link #narrow} below 360 units wide, which tightens its padding, and the canvas is
 * {@link #isShort} below 300 high, which raises the content's title; every bottom button of
 * either panel sits on one baseline, {@link #footerY}.
 *
 * <p>The status slot shows the screen's {@link #sideStatus}. A line {@link #status} sets takes
 * its place for as long as a toast would show, unless the state is {@link SideStatus#urgent},
 * as no connection or a save that failed, which nothing covers. Toasts are centred over the
 * content panel, above its buttons, or over its foot on a small canvas when the screen has no
 * buttons there ({@link #toastOverFooter}).
 *
 * <p>From 1.21.6 every figure or box drawn into a screen takes a render target of its own, so a
 * screen keeps to a budget of {@link #MAX_MODELS}. The nodes that draw models ({@link Budgeted})
 * are given theirs at each build, in a fixed order whatever order they were built in: the big
 * preview or the showcase's box first, then the showcase's figures, the figure strip's thumbs and
 * last the sidebar's boxes. A node left without one draws a flat stand-in, and while a dialog that
 * draws models of its own is open the screen behind it gets none ({@link #coverModels}).
 */
public abstract class SidebarScreen extends CreatorScreen {
    /** How many models a screen draws at most. */
    public static final int MAX_MODELS = 15;
    /** The space between the screen's edge and the panels, and between the two panels. */
    public static final int MARGIN = 8;
    public static final int GAP = 6;
    /** How far above the panels' lips their bottom buttons sit. */
    public static final int FOOTER_INSET = 10;
    /** The size of a glyph button beside the sidebar's heading, and their step. */
    public static final int GLYPH_SIZE = 15;
    public static final int GLYPH_STEP = 17;
    /** The heading glyph's width before the heading of a screen that goes back. */
    private static final int BACK_ROOM = 14;
    private static final int MAX_PANEL_WIDTH = 900;
    private static final int MAX_PANEL_HEIGHT = 520;
    private static final int MIN_SIDE_WIDTH = 116;
    private static final int MAX_SIDE_WIDTH = 156;
    private static final int NARROW_CONTENT = 360;
    private static final int SHORT_HEIGHT = 300;
    // The sidebar's heading, its rule, its list and its status slot, from the sidebar's top
    private static final int HEADING_MIDDLE = 18;
    private static final int RULE_Y = 31;
    private static final int LIST_TOP = 38;
    private static final int STATUS_ABOVE_FOOTER = 14;
    private static final int STATUS_GLYPH_ROOM = 12;
    // The content title at the sidebar heading's size, as Quick Skin's settings title theirs
    private static final float TITLE_SCALE = 1.0f;
    private static final float SMALL_LABEL_SCALE = 0.67f;
    // How long a line set by status() keeps the status slot: as long as a toast shows
    private static final long MESSAGE_MS = UiToast.LIFETIME_MS;
    // The widest a tooltip's line is before it wraps
    private static final int TOOLTIP_WIDTH = 200;

    /** The two panels, in GUI units. */
    protected UiNode.Rect side = UiNode.Rect.EMPTY;
    protected UiNode.Rect content = UiNode.Rect.EMPTY;
    private boolean narrow;
    private boolean isShort;
    private int pad;
    private int footerY;
    private int slotY;
    private final List<UiButton> headingGlyphs = new ArrayList<>();
    @Nullable
    private Heading heading;
    private Component message = Component.empty();
    private int messageColour = Theme.MUTED;
    private long messageAt;
    private final ModelBudget models = new ModelBudget();

    protected SidebarScreen(Component title, @Nullable Screen parent) {
        super(title, parent);
    }

    // Building

    @Override
    protected int panelMargin() {
        return MARGIN;
    }

    @Override
    protected final void build() {
        layoutPanels();
        this.headingGlyphs.clear();
        this.heading = null;
        this.models.clear();
        buildSidebar();
        buildContent();
        addNode(new StatusSlot());
        this.models.settle();
    }

    /** Adds the sidebar's heading, list, status and footer button; runs on every rebuild. */
    protected abstract void buildSidebar();

    /** Adds the content panel's nodes and widgets; runs on every rebuild, after the sidebar. */
    protected abstract void buildContent();

    // The two panels, capped and centred on a large canvas
    private void layoutPanels() {
        int panelWidth = Math.max(0, Math.min(this.width - 2 * MARGIN, MAX_PANEL_WIDTH));
        int panelHeight = Math.max(0, Math.min(this.height - 2 * MARGIN, MAX_PANEL_HEIGHT));
        this.panelX = (this.width - panelWidth) / 2;
        this.panelY = (this.height - panelHeight) / 2;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        int sideWidth = clamp(panelWidth / 4 + 20, MIN_SIDE_WIDTH, MAX_SIDE_WIDTH);
        this.side = new UiNode.Rect(this.panelX, this.panelY, sideWidth, panelHeight);
        int contentX = this.panelX + sideWidth + GAP;
        this.content = new UiNode.Rect(contentX, this.panelY, Math.max(0, this.panelX + panelWidth - contentX),
                panelHeight);
        this.narrow = this.content.width() < NARROW_CONTENT;
        this.isShort = this.height < SHORT_HEIGHT;
        this.pad = this.narrow ? 10 : 14;
        this.footerY = bottom() - FOOTER_INSET - UiButton.HEIGHT;
        this.slotY = this.footerY - STATUS_ABOVE_FOOTER;
    }

    /** A number kept within two bounds, on Java 17 too, which has no {@code Math.clamp}. */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // Geometry

    /** Whether the content panel is under 360 units wide, as on a 400x300 or 427x240 canvas. */
    public final boolean narrow() {
        return this.narrow;
    }

    /** Whether the canvas is under 300 units high, as Minecraft's default window makes it. */
    public final boolean isShort() {
        return this.isShort;
    }

    /** The content panel's padding: 14, or 10 when it is narrow. */
    public final int pad() {
        return this.pad;
    }

    /** The width inside the content panel's padding. */
    public final int inner() {
        return Math.max(0, this.content.width() - 2 * this.pad);
    }

    /** The content's left edge, inside its padding. */
    public final int left() {
        return this.content.x() + this.pad;
    }

    /** The content's right edge, inside its padding. */
    public final int right() {
        return this.content.right() - this.pad;
    }

    /** The bottom of both panels' faces, above their lips. */
    public final int bottom() {
        return this.panelY + this.panelHeight - 2 - Surfaces.LIP_HEIGHT;
    }

    /** The top of every bottom button of either panel, which is {@link UiButton#HEIGHT} high. */
    public final int footerY() {
        return this.footerY;
    }

    /** The top of the sidebar's status line. */
    public final int slotY() {
        return this.slotY;
    }

    /** Where the sidebar's list of rows goes: under the heading's rule, down to above the status line. */
    public final UiNode.Rect sideList() {
        int top = this.side.y() + LIST_TOP;
        return new UiNode.Rect(this.side.x() + 5, top, Math.max(0, this.side.width() - 10),
                Math.max(0, this.slotY - 4 - top));
    }

    @Override
    protected int statusY() {
        return this.slotY;
    }

    // The sidebar

    /** The sidebar's heading, in the pixel font over a rule. */
    protected final void sidebarHeading(Component text) {
        this.heading = addNode(new Heading(text, null, null));
    }

    /** A heading that goes back somewhere, after an arrow, as an editor's goes back to the collections. */
    protected final void sidebarHeading(Component text, Component backTooltip, Runnable back) {
        this.heading = addNode(new Heading(text, backTooltip, back));
    }

    /**
     * Adds a small glyph button at the heading's right, such as help or close; each one added
     * goes left of the one before it, and the heading's text stops short of them.
     */
    protected final UiButton headingGlyph(String[] glyph, Component tooltip, Runnable action) {
        UiButton button = new UiButton(Component.empty(), UiButton.Style.SECONDARY, action);
        button.setGlyph(glyph);
        button.setTooltip(tooltip);
        int buttonX = this.side.right() - 8 - GLYPH_SIZE - this.headingGlyphs.size() * GLYPH_STEP;
        button.at(buttonX, this.side.y() + HEADING_MIDDLE - GLYPH_SIZE / 2, GLYPH_SIZE, GLYPH_SIZE);
        this.headingGlyphs.add(button);
        return addNode(button);
    }

    /** Places the sidebar's one button at its foot and adds it. */
    protected final UiButton sideFooter(UiButton button) {
        button.at(this.side.x() + 6, this.footerY, Math.max(0, this.side.width() - 12), UiButton.HEIGHT);
        return addNode(button);
    }

    /**
     * What the sidebar's status slot shows, asked on every frame; null leaves it empty. A line
     * {@link #status} set covers it for a while, unless it is {@link SideStatus#urgent}.
     */
    @Nullable
    protected SideStatus sideStatus() {
        return null;
    }

    /** The line {@link #status} set last while it still shows; empty once it has run its time. */
    protected final Component statusMessage() {
        Component live = liveMessage();
        return live != null ? live : Component.empty();
    }

    /**
     * The status line goes to the sidebar's slot, over {@link #sideStatus} for as long as a toast
     * would show, so a sentence about the last action never hides the save state for long.
     */
    @Override
    protected void statusSink(Component text, int colour) {
        this.message = text;
        this.messageColour = colour;
        this.messageAt = Util.getMillis();
    }

    /** Whether a line {@link #status} set still shows in the status slot. */
    @Override
    protected boolean hasStatus() {
        return liveMessage() != null;
    }

    // The line status() set, or null when none is or it has run its time
    @Nullable
    private Component liveMessage() {
        if (this.message.getString().isEmpty()) {
            return null;
        }
        if (Util.getMillis() - this.messageAt >= MESSAGE_MS) {
            this.message = Component.empty();
            return null;
        }
        return this.message;
    }

    // The content

    /**
     * Draws the content panel's title in the pixel font, at the sidebar heading's size, cut to
     * leave room for a badge of the given width after it.
     *
     * @param badgeWidth the width of a badge drawn 8 after the title, or 0 for none
     * @return the title's right edge, where such a badge starts 8 later
     */
    protected final int contentTitle(GuiGraphics graphics, Component title, int badgeWidth) {
        int badgeRoom = badgeWidth > 0 ? 8 + badgeWidth : 0;
        int room = Math.max(0, inner() - badgeRoom);
        return left() + Draw.heading(graphics, title, left(), contentTitleMiddle(), room, TITLE_SCALE, Theme.TEXT);
    }

    /** The middle of the content title's capitals, for a badge beside it. */
    protected final int contentTitleMiddle() {
        return this.content.y() + (this.isShort ? 14 : 24);
    }

    /** A small label in the pixel font, as a field's name over it. */
    public static void smallLabel(GuiGraphics graphics, Component text, int x, int middleY, int width, int colour) {
        Draw.heading(graphics, text, x, middleY, width, SMALL_LABEL_SCALE, colour);
    }

    /**
     * A tooltip's text in lines no wider than a tooltip should be, so a long sentence, such as a
     * cut line's whole text or a setting's note, stays on the screen rather than running off it.
     */
    public static List<Component> tooltipLines(Component text) {
        Font font = Minecraft.getInstance().font;
        String whole = text.getString();
        if (UiText.width(font, whole) <= TOOLTIP_WIDTH) {
            return List.of(text);
        }
        List<Component> lines = new ArrayList<>();
        for (String line : UiText.wrap(font, whole, TOOLTIP_WIDTH)) {
            lines.add(Component.literal(line));
        }
        return lines;
    }

    // Drawing

    @Override
    protected void drawPanel(GuiGraphics graphics) {
        Draw.raised(graphics, this.side.x(), this.side.y(), this.side.width(), this.side.height(), Surfaces.BORDER,
                Surfaces.PANEL_FACE);
        Draw.raised(graphics, this.content.x(), this.content.y(), this.content.width(), this.content.height(),
                Surfaces.BORDER, Theme.PANEL);
    }

    @Override
    protected final void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawSidebar(graphics, mouseX, mouseY, partialTick);
        drawContent(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the sidebar's own labels, above its widgets and below its nodes. */
    protected void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Draws the content panel's own labels, such as its title, above its widgets and below its nodes. */
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * Whether a toast may sit over the content panel's foot on a narrow or short canvas, where
     * above the footer it would cover what scrolls there: true for a screen with no buttons on
     * the content's footer baseline, such as the editor; false keeps it above them everywhere.
     */
    protected boolean toastOverFooter() {
        return false;
    }

    @Override
    protected int toastBottom() {
        if (toastOverFooter() && (this.narrow || this.isShort)) {
            return bottom() - 4;
        }
        // Above the content's buttons, so a toast never covers them
        return this.footerY - 6;
    }

    @Override
    protected int toastLeft() {
        return this.content.x();
    }

    @Override
    protected int toastWidth() {
        return this.content.width();
    }

    // The model budget

    /**
     * Gives a node that draws models its share of the budget; call it while building, for every
     * such node on the screen, in any order, once the node is placed and filled, since how many
     * models it asks for follows its size and contents.
     */
    protected final <T extends Budgeted> T budget(T node) {
        node.requestModels(this.models);
        return node;
    }

    /**
     * Takes every model from the screen while a dialog over it draws models of its own, such as
     * the test draw's machine, or gives them back once it has closed.
     */
    public final void coverModels(boolean covered) {
        if (this.models.covered != covered) {
            this.models.covered = covered;
            this.models.settle();
        }
    }

    /** A node that draws models, which asks the screen's budget for them while the screen builds. */
    public interface Budgeted {
        /** Asks for as many models as the node would draw, by rank; see {@link ModelBudget#request}. */
        void requestModels(ModelBudget budget);
    }

    /** The models one screen may draw, shared out by rank among the nodes that ask. */
    public static final class ModelBudget {
        /** Who gets models first: the ranks in their order. */
        public enum Rank {
            /** The big preview, or the showcase's box. */
            PREVIEW,
            /** The showcase's figures. */
            SHOWCASE,
            /** The figure strip's thumbs: the selected one, then the others in sight from the left. */
            STRIP,
            /** The sidebar's boxes in sight, from the top. */
            SIDEBAR
        }

        private record Request(Rank rank, int wanted, IntConsumer grant) {
        }

        private final List<Request> requests = new ArrayList<>();
        private boolean covered;

        /**
         * Asks for models: once the screen is built, {@code grant} is told how many of those
         * wanted it may draw, and told again whenever the budget changes.
         */
        public void request(Rank rank, int wanted, IntConsumer grant) {
            this.requests.add(new Request(rank, Math.max(0, wanted), grant));
        }

        void clear() {
            this.requests.clear();
        }

        // Shares the budget out by rank, and within a rank in the order asked
        void settle() {
            int left = this.covered ? 0 : MAX_MODELS;
            for (Rank rank : Rank.values()) {
                for (Request request : List.copyOf(this.requests)) {
                    if (request.rank() == rank) {
                        int given = Math.min(left, request.wanted());
                        left -= given;
                        request.grant().accept(given);
                    }
                }
            }
        }
    }

    /**
     * What the sidebar's status slot says: a short label after an optional pixel glyph, in a
     * colour, with the whole sentence in its tooltip and a click that acts on it, and a thin bar
     * under it for work under way.
     *
     * @param progress from 0 to 1 for a bar under the label, or below 0 for none
     * @param urgent   whether the state matters more than any passing line {@link #status} sets,
     *                 as no connection, a save that failed or a change that cannot be saved:
     *                 such a state is never covered
     */
    public record SideStatus(Component label, int colour, @Nullable String[] glyph, @Nullable Component tooltip,
                             @Nullable Runnable action, float progress, boolean urgent) {
        /** A label alone, in a colour. */
        public static SideStatus of(Component label, int colour) {
            return new SideStatus(label, colour, null, null, null, -1.0f, false);
        }

        public SideStatus withGlyph(@Nullable String[] icon) {
            return new SideStatus(label, colour, icon, tooltip, action, progress, urgent);
        }

        public SideStatus withTooltip(@Nullable Component text) {
            return new SideStatus(label, colour, glyph, text, action, progress, urgent);
        }

        public SideStatus withAction(@Nullable Runnable onClick) {
            return new SideStatus(label, colour, glyph, tooltip, onClick, progress, urgent);
        }

        public SideStatus withProgress(float fraction) {
            return new SideStatus(label, colour, glyph, tooltip, action, fraction, urgent);
        }

        /** Marks the state as one no passing line covers. */
        public SideStatus asUrgent() {
            return new SideStatus(label, colour, glyph, tooltip, action, progress, true);
        }
    }

    // The heading: the pixel font over a rule, after a back arrow that takes the click when it goes back
    private final class Heading extends UiNode.Box {
        private final Component text;
        @Nullable
        private final Component backTooltip;
        @Nullable
        private final Runnable back;

        Heading(Component text, @Nullable Component backTooltip, @Nullable Runnable back) {
            this.text = text;
            this.backTooltip = backTooltip;
            this.back = back;
            at(side.x() + 6, side.y() + 6, Math.max(0, side.width() - 12), RULE_Y - 8);
        }

        // The heading's room ends before the glyph buttons beside it
        private int textRight() {
            int right = side.right() - 8;
            for (UiButton glyph : headingGlyphs) {
                right = Math.min(right, glyph.bounds().x() - 4);
            }
            return right;
        }

        @Override
        public Rect bounds() {
            // Only as wide as the heading, so the glyph buttons beside it keep their clicks
            return new Rect(x, y, Math.max(0, textRight() - x), height);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = back != null && hovered(mouseX, mouseY);
            int textX = side.x() + 10;
            int middle = side.y() + HEADING_MIDDLE;
            if (back != null) {
                // The arrow lights up in the selection's blue under the mouse, as a link would
                PixelIcon.draw(graphics, PixelIcon.ARROW_LEFT, textX, middle - 3,
                        hovered ? Theme.SELECTED_LIGHT : Theme.TEXT_SOFT);
                textX += BACK_ROOM;
                if (hovered && backTooltip != null) {
                    UiTooltip.set(backTooltip);
                }
            }
            Draw.heading(graphics, text, textX, middle, Math.max(0, textRight() - textX), 1.0f, Theme.TEXT);
            graphics.fill(side.x() + 6, side.y() + RULE_Y, side.right() - 6, side.y() + RULE_Y + 1,
                    Surfaces.BORDER_DISABLED);
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (back == null || !click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            back.run();
            return true;
        }
    }

    // The status slot: one line over the sidebar's button, which clicks when its state acts
    private final class StatusSlot extends UiNode.Box {
        StatusSlot() {
            at(side.x() + 10, slotY - 2, Math.max(0, side.width() - 20), 13);
        }

        // The screen's state, covered for a while by a line status() set unless it is urgent
        @Nullable
        private SideStatus shown() {
            SideStatus state = sideStatus();
            if (state != null && state.urgent()) {
                return state;
            }
            Component live = liveMessage();
            return live != null ? SideStatus.of(live, messageColour) : state;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            SideStatus status = shown();
            if (status == null || status.label().getString().isEmpty()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            boolean hovered = hovered(mouseX, mouseY);
            int textX = x;
            if (status.glyph() != null) {
                PixelIcon.draw(graphics, status.glyph(), x, slotY - 1, status.colour());
                textX += STATUS_GLYPH_ROOM;
            }
            int room = Math.max(0, x + width - textX);
            String whole = status.label().getString();
            String cut = UiText.fit(font, whole, room);
            Draw.string(graphics, font, cut, textX, slotY, status.colour());
            if (hovered && status.action() != null) {
                graphics.fill(textX, slotY + 9, textX + UiText.width(font, cut), slotY + 10, status.colour());
            }
            if (status.progress() >= 0.0f) {
                Draw.progressBar(graphics, textX, slotY + 11, room, 1, status.progress(), Surfaces.WELL,
                        status.colour());
            }
            if (hovered) {
                Component tip = status.tooltip() != null ? status.tooltip() : !cut.equals(whole) ? status.label() : null;
                if (tip != null) {
                    UiTooltip.set(tooltipLines(tip));
                }
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            SideStatus status = shown();
            if (status == null || status.action() == null || !click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            status.action().run();
            return true;
        }
    }
}
