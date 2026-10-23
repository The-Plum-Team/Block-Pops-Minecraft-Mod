package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An area taller content scrolls through: the wheel moves it 20 units a notch and it glides to
 * the new place rather than jumping, a thin bar on the right shows where it is, and dragging that
 * bar or clicking its track moves it too. Content outside the area is clipped.
 *
 * <p>Children are laid out where they would sit with the area at the top, in screen units: a
 * child at the area's own top edge is at {@code y}. The pane draws them moved up by
 * {@link #scrollY()} and hands them input moved down by the same amount, so a child never knows
 * it scrolls. A child that clips its own drawing calls {@link #clip} rather than
 * {@code enableScissor}, and a child that opens a menu anchors it with {@link #onScreen}.
 *
 * <p>The child under a press gets its drags and its release, and the child pressed last gets
 * the keys while the pane has the keyboard. With no child taking them, Page Up, Page Down, Home
 * and End scroll the pane.
 */
public class UiScrollPane extends UiNode.Box {
    /** How far one notch of the wheel scrolls. */
    public static final int NOTCH = 20;
    /** The width of the bar, which the content leaves free with a small gap. */
    public static final int BAR = 4;
    private static final int BAR_GAP = 2;
    private static final int MIN_THUMB = 12;
    private static final int THUMB = 0xFF7A7A7A;
    private static final int THUMB_HOVER = 0xFFBBBBBB;
    private static final int TRACK = 0xFF1E1E1E;
    // Where the pointer is for children when it is outside the pane, so none of them looks hovered
    private static final int AWAY = -100000;
    // How quickly the shown position catches up with the wanted one, per second
    private static final double GLIDE = 18.0;

    // How far the panes an event is passing through have scrolled in all, so a child can turn
    // its own bounds into the screen's: menus are drawn over everything, unscrolled
    private static int passingShift;
    // How far the panes being drawn have moved their content in all: before 1.21.4 a clip
    // ignores moved drawing, so a clip inside a pane is moved back by hand
    private static int drawingShift;

    protected final List<UiNode> children = new ArrayList<>();
    private double scroll;
    private double target;
    private long lastFrame;
    private int fixedContentHeight = -1;
    private boolean draggingThumb;
    private double thumbGrab;
    @Nullable
    private UiNode pressedChild;
    @Nullable
    private UiNode focusedChild;

    /** Adds a child above those added before it. */
    public <T extends UiNode> T add(T child) {
        children.add(child);
        return child;
    }

    public void remove(UiNode child) {
        children.remove(child);
        if (pressedChild == child) {
            pressedChild = null;
        }
        if (focusedChild == child) {
            focusedChild = null;
        }
    }

    /** Removes every child, keeping the scroll position for the children laid out next. */
    public void clear() {
        children.clear();
        pressedChild = null;
        focusedChild = null;
    }

    /**
     * Fixes the height of the content instead of measuring the children, for a pane whose
     * content is drawn by a subclass; -1 measures again.
     */
    public void setContentHeight(int height) {
        this.fixedContentHeight = height;
    }

    /** The width children can use: the pane's, less the bar and its gap. */
    public int contentWidth() {
        return Math.max(0, width - BAR - BAR_GAP);
    }

    /** How tall the content is, from the pane's top edge to the bottom of its lowest child. */
    protected int contentHeight() {
        if (fixedContentHeight >= 0) {
            return fixedContentHeight;
        }
        int bottom = y;
        for (UiNode child : children) {
            if (child.visible()) {
                bottom = Math.max(bottom, child.bounds().bottom());
            }
        }
        return bottom - y;
    }

    public int maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    /** How far the content is scrolled now, as drawn. */
    public int scrollY() {
        return (int) Math.round(scroll);
    }

    /** Scrolls to a position, gliding there unless asked to jump. */
    public void scrollTo(double position, boolean jump) {
        target = Math.max(0, Math.min(maxScroll(), position));
        if (jump) {
            scroll = target;
        }
    }

    /** Scrolls as little as it takes to show a band of the content, given in unscrolled units. */
    public void reveal(int top, int bottom) {
        double wanted = target;
        if (top - y < wanted) {
            wanted = top - y;
        } else if (bottom - y > wanted + height) {
            wanted = bottom - y - height;
        }
        scrollTo(wanted, false);
    }

    @Override
    @Nullable
    public UiNode focusedChild() {
        return focusedChild;
    }

    /** Gives the keys to a child, or to the pane itself. */
    protected void focusChild(@Nullable UiNode child) {
        this.focusedChild = child;
    }

    // Static helpers for children

    /** A child's bounds turned into the screen's, for anchoring a menu while it takes an event. */
    public static Rect onScreen(Rect bounds) {
        return new Rect(bounds.x(), bounds.y() - passingShift, bounds.width(), bounds.height());
    }

    /**
     * Clips drawing to a rectangle given in the unscrolled units of the panes being drawn, which
     * a child must use instead of {@code enableScissor}: before 1.21.4 a clip ignores the moved
     * drawing, so it is moved here by hand, through every pane the child sits in. Outside a pane
     * it is a plain clip. Pair it with {@code graphics.disableScissor()}.
     */
    public static void clip(GuiGraphics graphics, int left, int top, int right, int bottom) {
        //? if >=1.21.4 {
        /*graphics.enableScissor(left, top, right, bottom);
        *///? } else {
        graphics.enableScissor(left, top - drawingShift, right, bottom - drawingShift);
        //? }
    }

    /**
     * How far the panes being drawn have moved their content up in all. Before 1.21.4 a clip a
     * child cannot route through {@link #clip}, such as the one a figure preview sets itself,
     * has to be drawn with the content moved back down by this much.
     */
    static int drawingShift() {
        return drawingShift;
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        glide();
        int shift = scrollY();
        boolean inside = bounds().contains(mouseX, mouseY) && !overBar(mouseX, mouseY);
        int innerX = inside ? mouseX : AWAY;
        int innerY = inside ? mouseY + shift : AWAY;
        // The clip before the content moves, in the units of whatever holds the pane
        clip(graphics, x, y, x + width, y + height);
        GuiPose.push(graphics);
        GuiPose.translate(graphics, 0, -shift);
        drawingShift += shift;
        try {
            drawContent(graphics, innerX, innerY, partialTick);
        } finally {
            drawingShift -= shift;
            GuiPose.pop(graphics);
            graphics.disableScissor();
        }
        drawBar(graphics, mouseX, mouseY);
    }

    /** Draws the content in unscrolled units; the pointer is in the same units. */
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (UiNode child : children) {
            if (child.visible()) {
                child.draw(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void glide() {
        long now = Util.getMillis();
        double seconds = lastFrame == 0 ? 1.0 : Math.min(1.0, (now - lastFrame) / 1000.0);
        lastFrame = now;
        // The content may have shrunk since the last frame
        int max = maxScroll();
        target = Math.max(0, Math.min(max, target));
        double gap = target - scroll;
        scroll = Math.abs(gap) < 0.5 ? target : scroll + gap * Math.min(1.0, seconds * GLIDE);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private int thumbHeight() {
        int content = Math.max(1, contentHeight());
        return Math.max(MIN_THUMB, Math.min(height, height * height / content));
    }

    private int thumbY() {
        int max = maxScroll();
        return max == 0 ? y : y + (int) Math.round((height - thumbHeight()) * scroll / max);
    }

    private boolean overBar(double mouseX, double mouseY) {
        return maxScroll() > 0 && mouseX >= x + width - BAR && mouseX < x + width && mouseY >= y
                && mouseY < y + height;
    }

    private void drawBar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (maxScroll() == 0) {
            return;
        }
        int barX = x + width - BAR;
        graphics.fill(barX, y, barX + BAR, y + height, TRACK);
        int thumbY = thumbY();
        boolean lit = draggingThumb || overBar(mouseX, mouseY);
        graphics.fill(barX, thumbY, barX + BAR, thumbY + thumbHeight(), lit ? THUMB_HOVER : THUMB);
    }

    // Input

    private UiInput.Click inner(UiInput.Click click) {
        return click.at(click.x(), click.y() + scrollY());
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressedChild = null;
        draggingThumb = false;
        if (!isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (overBar(click.x(), click.y())) {
            if (click.left()) {
                int thumbY = thumbY();
                int thumb = thumbHeight();
                boolean onThumb = click.y() >= thumbY && click.y() < thumbY + thumb;
                // A click on the track brings the thumb's middle to the pointer
                thumbGrab = onThumb ? click.y() - thumbY : thumb / 2.0;
                draggingThumb = true;
                moveThumb(click.y());
            }
            return true;
        }
        int shift = scrollY();
        passingShift += shift;
        try {
            if (!clickContent(click.at(click.x(), click.y() + shift))) {
                focusedChild = null;
            }
        } finally {
            passingShift -= shift;
        }
        // A click on the pane's empty space is still the pane's
        return true;
    }

    /** Hands a click in unscrolled units to the content; by default to the top child under it. */
    protected boolean clickContent(UiInput.Click click) {
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode child = children.get(i);
            if (child.visible() && child.click(click)) {
                pressedChild = child;
                focusedChild = children.contains(child) ? child : null;
                return true;
            }
        }
        return false;
    }

    private void moveThumb(double mouseY) {
        int room = height - thumbHeight();
        if (room <= 0) {
            return;
        }
        double fraction = (mouseY - thumbGrab - y) / room;
        scrollTo(fraction * maxScroll(), true);
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (draggingThumb) {
            moveThumb(click.y());
            return true;
        }
        return dragContent(inner(click), dragX, dragY);
    }

    /** Hands a drag in unscrolled units to the content; by default to the child pressed. */
    protected boolean dragContent(UiInput.Click click, double dragX, double dragY) {
        return pressedChild != null && pressedChild.drag(click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        if (draggingThumb) {
            draggingThumb = false;
            return true;
        }
        return releaseContent(inner(click));
    }

    /** Hands a release in unscrolled units to the content; by default to the child pressed. */
    protected boolean releaseContent(UiInput.Click click) {
        UiNode pressed = pressedChild;
        pressedChild = null;
        return pressed != null && pressed.release(click);
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (!visible()) {
            return false;
        }
        double innerY = mouseY + scrollY();
        // A child that scrolls itself, such as a pane inside this one, goes first
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode child = children.get(i);
            if (child.isMouseOver(mouseX, innerY) && child.scroll(mouseX, innerY, amount)) {
                return true;
            }
        }
        if (maxScroll() == 0) {
            return false;
        }
        scrollTo(target - amount * NOTCH, false);
        return true;
    }

    @Override
    public boolean key(UiInput.Key key) {
        UiNode focused = focusedChild;
        if (focused != null && focused.visible() && children.contains(focused)) {
            int shift = scrollY();
            passingShift += shift;
            try {
                if (focused.key(key)) {
                    return true;
                }
            } finally {
                passingShift -= shift;
            }
        }
        return keyContent(key);
    }

    /** Keys no child took: by default the page keys scroll. */
    protected boolean keyContent(UiInput.Key key) {
        if (maxScroll() == 0) {
            return false;
        }
        if (key.is(InputConstants.KEY_PAGEUP)) {
            scrollTo(target - height, false);
        } else if (key.is(InputConstants.KEY_PAGEDOWN)) {
            scrollTo(target + height, false);
        } else if (key.is(InputConstants.KEY_HOME)) {
            scrollTo(0, false);
        } else if (key.is(InputConstants.KEY_END)) {
            scrollTo(maxScroll(), false);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean typed(String text) {
        UiNode focused = focusedChild;
        return focused != null && focused.visible() && children.contains(focused) && focused.typed(text);
    }

    @Override
    public void tick() {
        for (UiNode child : List.copyOf(children)) {
            child.tick();
        }
    }

    /**
     * Passes the point on in unscrolled units, and only counts the zones that show at least in
     * part: a zone scrolled out of sight cannot take a file nobody saw it ask for.
     */
    @Override
    @Nullable
    public UiDropZone dropZones(double mouseX, double mouseY, List<UiDropZone> zones) {
        if (!visible()) {
            return null;
        }
        int shift = scrollY();
        boolean inside = bounds().contains(mouseX, mouseY);
        double innerY = inside ? mouseY + shift : Double.NaN;
        List<UiDropZone> found = new ArrayList<>();
        UiDropZone under = null;
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode child = children.get(i);
            if (!child.visible()) {
                continue;
            }
            UiDropZone hit = child.dropZones(mouseX, innerY, found);
            if (under == null) {
                under = hit;
            }
        }
        int top = y + shift;
        for (UiDropZone zone : found) {
            Rect at = zone.bounds();
            if (at.bottom() > top && at.y() < top + height) {
                zones.add(zone);
            }
        }
        return under;
    }
}
