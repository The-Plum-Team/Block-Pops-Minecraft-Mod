package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.renderer.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The front of a collection's box, flat and at its real proportions, with the logo on it to drag:
 * what a {@link UiBoxView} shows in place of the box while the operator places the logo
 * ({@link UiBoxView#frontEditor}). The face is the front's texels of the box texture as chosen
 * now, over the dark inside of the box seen through its window, and the logo is the drawn part of
 * its texture, placed by the box renderer's own rule ({@link LogoLayout#onFace}), so it sits where
 * the box draws it. It is outlined in the selection's blue, lighter while the pointer is over the
 * face, and while it is away from its usual place, at the front's bottom left, that place shows
 * dashed.
 *
 * <p>A press on the face, or near it, starts a drag that moves the logo in tenths of a face pixel,
 * right and up, within the offsets a layout allows; each drag is one change. While the view has
 * the keyboard the arrow keys nudge it a tenth, or a whole pixel with Shift, a run of nudges being
 * one change, and Enter or Escape is "Done". "Reset" puts the logo back in its usual place, keeping
 * its size. Above the face are its title and a hint; under it, how far the logo is moved.
 *
 * <p>The face and the logo are drawn with the scene ({@link #drawFace}), the text above it with the
 * view's overlay ({@link #drawText}), so the text stays above any model on every version. Its two
 * buttons are controls of the view.
 */
public class UiBoxFrontEditor extends UiNode.Box {
    /** A logo's texture and the size of its image, in texels. */
    public record Logo(ResourceLocation texture, int width, int height) {
    }

    /** What the editor shows and where its changes go. */
    public interface Model {
        /** The box texture as chosen now, {@link LogoLayout#BOX_TEXTURE_SIZE} a side. */
        ResourceLocation boxTexture();

        /** The logo, or null while there is none to draw, as while its texture is still arriving. */
        @Nullable
        Logo logo();

        /** The logo's size and place now. */
        WorldCollectionRecord.LogoLayout layout();

        /** Moves the logo to an offset, in tenths of a face pixel, already within the allowed offsets. */
        void moveTo(int offsetX, int offsetY);

        /** A change starts: a drag, or the first nudge of a run, which is one undo step. */
        void beginChange();

        /** A drag ended, which saves the place sooner, as a slider let go does. */
        void released();

        /** Puts the logo back in its usual place, keeping its size: one change of its own. */
        void reset();

        /** "Done": the operator is finished placing the logo. */
        void done();

        /** Whether the screen's own main action is green now, which leaves "Done" grey. */
        boolean footerPrimary();
    }

    private static final int INSET = 6;
    // The inside of the box seen through its window, behind the front's frame
    private static final int INTERIOR = 0xFF141418;
    // The usual place of a logo moved away from it, dashed in white at three quarters
    private static final int HOME_EDGE = 0xC0FFFFFF;
    // A press this close to the face still grabs the logo
    private static final int GRAB = 8;
    // A wide view's face sits in a band under the title and its hint, over the offset and "Reset"
    private static final int BAND_TOP = 47;
    private static final int BAND_ROOM = 85;
    private static final int WIDE_SIDES = 28;
    private static final int NARROW_MAX_WIDTH = 72;
    private static final int TITLE_LEFT = 8;
    private static final int NARROW_HINT_LINES = 3;
    private static final int WIDE_HINT_LINES = 2;
    private static final int NARROW_VALUE_LINES = 2;

    private final Model model;
    private final UiButton done;
    private final UiButton reset;
    private boolean open;
    private boolean narrow;
    // A drag of the logo: where it started, and the offsets then
    private boolean dragging;
    private double pressX;
    private double pressY;
    private int pressOffsetX;
    private int pressOffsetY;
    // A run of nudges with the arrow keys is one change, until a click, another key or the pointer comes between
    private boolean nudging;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;

    public UiBoxFrontEditor(Model model) {
        this.model = model;
        this.done = new UiButton(CreatorScreen.tr("author.done"), UiButton.Style.SECONDARY, model::done);
        // A small button as "Reset" is, in the same letters, also while it is the green one
        this.done.setPlainLabel(true);
        this.reset = new UiButton(CreatorScreen.tr("look.position_reset"), UiButton.Style.SECONDARY, model::reset);
        this.reset.setTooltip(CreatorScreen.tr("look.position_reset_tip"));
        this.done.setVisible(false);
        this.reset.setVisible(false);
    }

    /** "Done" and "Reset", which the view adds to its controls. */
    public List<UiNode> controls() {
        return List.of(this.done, this.reset);
    }

    public boolean isOpen() {
        return this.open;
    }

    /** Shows the front in place of the box; the view's own controls step aside. */
    public void open() {
        this.open = true;
        this.dragging = false;
        this.nudging = false;
    }

    /** Shows the box again. */
    public void close() {
        this.open = false;
        this.dragging = false;
        this.nudging = false;
        this.done.setVisible(false);
        this.reset.setVisible(false);
    }

    /** A narrow view: a smaller face, with the text on its left. */
    public UiBoxFrontEditor narrow(boolean narrow) {
        this.narrow = narrow;
        return this;
    }

    // Layout

    /** Places the buttons for the view's bounds, which are the editor's; runs before every draw and click. */
    public void layout() {
        Font font = Minecraft.getInstance().font;
        this.done.setStyle(this.model.footerPrimary() ? UiButton.Style.SECONDARY : UiButton.Style.PRIMARY);
        int doneWidth = UiButton.widthFor(font, this.done.label(), false);
        this.done.at(x + width - INSET - doneWidth, y + INSET, doneWidth, UiButton.SMALL_HEIGHT);
        int resetWidth = UiButton.widthFor(font, this.reset.label(), false);
        this.reset.at(x + width - INSET - resetWidth, y + height - INSET - UiButton.SMALL_HEIGHT, resetWidth,
                UiButton.SMALL_HEIGHT);
        this.done.setVisible(this.open);
        this.reset.setVisible(this.open);
    }

    /**
     * The face on the screen: in a wide view the largest rectangle of the front's proportions in
     * the band under the title, in a narrow one as high as the view allows, at most
     * {@value #NARROW_MAX_WIDTH} wide; centred.
     */
    public Rect face() {
        int top;
        int maxWidth;
        int maxHeight;
        if (this.narrow) {
            top = y + INSET;
            maxWidth = NARROW_MAX_WIDTH;
            maxHeight = Math.max(10, height - 2 * INSET);
        } else {
            top = y + BAND_TOP;
            maxWidth = width - WIDE_SIDES;
            maxHeight = Math.max(10, height - BAND_ROOM);
        }
        int faceHeight = Math.min(maxHeight, Math.round(maxWidth * LogoLayout.FRONT_HEIGHT
                / (float) LogoLayout.FRONT_WIDTH));
        int faceWidth = Math.round(faceHeight * LogoLayout.FRONT_WIDTH / (float) LogoLayout.FRONT_HEIGHT);
        return new Rect(x + (width - faceWidth) / 2, top + (maxHeight - faceHeight) / 2, faceWidth, faceHeight);
    }

    // The logo's drawn part on the face, at its place or at its usual one
    private Rect logoRect(Rect face, Logo logo, boolean atHome) {
        WorldCollectionRecord.LogoLayout layout = this.model.layout();
        WorldCollectionRecord.LogoLayout shown = atHome
                ? new WorldCollectionRecord.LogoLayout(layout.scalePct(), 0, 0) : layout;
        float[] place = LogoLayout.onFace(LogoLayout.bounds(logo.texture()), shown);
        float pixel = face.width() / LogoLayout.FACE_WIDTH;
        int left = Math.round(face.x() + place[0] * pixel);
        int bottom = Math.round(face.bottom() - place[1] * pixel);
        int logoWidth = Math.max(1, Math.round(place[2] * pixel));
        int logoHeight = Math.max(1, Math.round(place[3] * pixel));
        return new Rect(left, bottom - logoHeight, logoWidth, logoHeight);
    }

    // Drawing

    /** Draws the face and the logo on it, with the scene. */
    public void drawFace(GuiGraphics graphics, int mouseX, int mouseY) {
        if (mouseX != this.lastMouseX || mouseY != this.lastMouseY) {
            // The pointer came between two nudges: the next one starts a change of its own
            this.nudging = false;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
        }
        Rect face = face();
        graphics.fill(face.x() + 1, face.y() + 1, face.right() - 1, face.bottom() - 1, INTERIOR);
        Draw.texture(graphics, this.model.boxTexture(), face.x(), face.y(), face.width(), face.height(),
                LogoLayout.FRONT_U, LogoLayout.FRONT_V, LogoLayout.FRONT_WIDTH, LogoLayout.FRONT_HEIGHT,
                LogoLayout.BOX_TEXTURE_SIZE, LogoLayout.BOX_TEXTURE_SIZE);
        Logo logo = this.model.logo();
        if (logo == null) {
            return;
        }
        WorldCollectionRecord.LogoLayout layout = this.model.layout();
        if (layout.offsetX() != 0 || layout.offsetY() != 0) {
            Rect home = logoRect(face, logo, true);
            Draw.dashed(graphics, home.x() - 1, home.y() - 1, home.width() + 2, home.height() + 2, HOME_EDGE, 2);
        }
        Rect at = logoRect(face, logo, false);
        int[] bounds = LogoLayout.bounds(logo.texture());
        Draw.texture(graphics, logo.texture(), at.x(), at.y(), at.width(), at.height(), bounds[2], bounds[3],
                bounds[4] - bounds[2], bounds[5] - bounds[3], bounds[0], bounds[1]);
        boolean over = this.dragging || face.contains(mouseX, mouseY);
        Draw.outline(graphics, at.x() - 2, at.y() - 2, at.width() + 4, at.height() + 4,
                over ? Theme.SELECTED_LIGHT : Theme.SELECTED);
        Draw.outline(graphics, at.x() - 1, at.y() - 1, at.width() + 2, at.height() + 2, Theme.SELECTED);
    }

    /** Draws the title, the hint and the offset, with the view's overlay, above the scene. */
    public void drawText(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        Rect face = face();
        WorldCollectionRecord.LogoLayout layout = this.model.layout();
        Component title = CreatorScreen.tr("look.position_title");
        String hint = CreatorScreen.tr("look.position_hint").getString();
        String x10 = tenths(layout.offsetX());
        String y10 = tenths(layout.offsetY());
        int left = x + TITLE_LEFT;
        if (this.narrow) {
            int room = Math.max(0, face.x() - 10 - left);
            SidebarScreen.smallLabel(graphics, title, left, y + INSET + 5, room, Theme.TEXT_SOFT);
            Draw.lines(graphics, font, hint, left, y + INSET + 14, room, NARROW_HINT_LINES, Theme.MUTED);
            // Beside the face there is room for the numbers only, on two lines when they need them
            String value = CreatorScreen.tr("look.offset_short", x10, y10).getString();
            int lines = Math.min(NARROW_VALUE_LINES, Draw.wrap(font, value, room).size());
            Draw.lines(graphics, font, value, left, y + height - INSET - 9 - (lines - 1) * Theme.LINE_HEIGHT, room,
                    NARROW_VALUE_LINES, Theme.FAINT);
        } else {
            String value = CreatorScreen.tr("xy.value", x10, y10).getString();
            SidebarScreen.smallLabel(graphics, title, left, y + INSET + 7,
                    Math.max(0, this.done.bounds().x() - 6 - left), Theme.TEXT_SOFT);
            Draw.lines(graphics, font, hint, left, y + INSET + UiButton.SMALL_HEIGHT + 5, width - 2 * INSET - 4,
                    WIDE_HINT_LINES, Theme.MUTED);
            Draw.centred(graphics, font, value, x + width / 2, face.bottom() + 4, width - 2 * INSET, Theme.FAINT);
        }
        if (face.contains(mouseX, mouseY) && !this.dragging) {
            UiTooltip.set(SidebarScreen.tooltipLines(CreatorScreen.tr("look.position_drag")));
        }
    }

    // A tenth of a pixel as pixels with one decimal
    private static String tenths(int value) {
        return String.format(Locale.ROOT, "%.1f", value / 10.0);
    }

    // Input

    /**
     * A press on the view while the editor is open: on the face or near it, it starts a drag of
     * the logo; anywhere else on the view it does nothing, and the view keeps the keyboard.
     */
    public boolean press(UiInput.Click click) {
        this.nudging = false;
        if (!click.left() || this.model.logo() == null) {
            return true;
        }
        if (!face().inset(-GRAB).contains(click.x(), click.y())) {
            return true;
        }
        WorldCollectionRecord.LogoLayout layout = this.model.layout();
        this.model.beginChange();
        this.dragging = true;
        this.pressX = click.x();
        this.pressY = click.y();
        this.pressOffsetX = layout.offsetX();
        this.pressOffsetY = layout.offsetY();
        return true;
    }

    /** The pointer moved with the logo held: it follows, in tenths of a face pixel. */
    public boolean drag(UiInput.Click click) {
        if (!this.dragging) {
            return false;
        }
        float pixel = face().width() / LogoLayout.FACE_WIDTH;
        int across = Math.round((float) ((click.x() - this.pressX) / pixel * 10.0));
        int up = Math.round((float) ((this.pressY - click.y()) / pixel * 10.0));
        moveTo(this.pressOffsetX + across, this.pressOffsetY + up);
        return true;
    }

    /** The logo was let go: its place is saved sooner. */
    public boolean release() {
        if (!this.dragging) {
            return false;
        }
        this.dragging = false;
        this.model.released();
        return true;
    }

    /** Arrows nudge the logo, Enter and Escape are "Done". */
    public boolean key(UiInput.Key key) {
        if (!this.open) {
            return false;
        }
        if (key.enter() || key.escape()) {
            this.nudging = false;
            this.model.done();
            return true;
        }
        int step = key.shift() ? 10 : 1;
        int dx = key.is(InputConstants.KEY_LEFT) ? -step : key.is(InputConstants.KEY_RIGHT) ? step : 0;
        int dy = key.is(InputConstants.KEY_DOWN) ? -step : key.is(InputConstants.KEY_UP) ? step : 0;
        if (dx == 0 && dy == 0) {
            this.nudging = false;
            return false;
        }
        if (this.model.logo() == null) {
            return true;
        }
        if (!this.nudging) {
            this.model.beginChange();
            this.nudging = true;
        }
        WorldCollectionRecord.LogoLayout layout = this.model.layout();
        moveTo(layout.offsetX() + dx, layout.offsetY() + dy);
        return true;
    }

    private void moveTo(int offsetX, int offsetY) {
        int most = WorldCollectionRecord.LogoLayout.MAX_OFFSET;
        this.model.moveTo(Math.max(-most, Math.min(most, offsetX)), Math.max(-most, Math.min(most, offsetY)));
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Drawn by the view, in two passes: drawFace with the scene, drawText with the overlay
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // Part of the view, which takes its input
        return false;
    }
}
