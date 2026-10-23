package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A button in the menus' style, drawn as {@link com.theplumteam.client.gui.style.StyledButton}
 * draws its own: a raised face in a thin border and a black outline, on a darker lip, that
 * lightens under the mouse and sinks onto the lip while pressed. The main action of a page is
 * green with its label in the blocky pixel font; a positive action is green too, one that takes
 * something away red, and anything else charcoal. A link or a destructive action can also be
 * plain text. A disabled button stays visible and says why it cannot be used in its tooltip. It
 * may show an item or a pixel icon before its label.
 */
public class UiButton extends UiNode.Box {
    /** How a button looks. */
    public enum Style {
        PRIMARY(0xFF3C8A3C, 0xFF48A048, 0xFF6FCF6F, 0xFF9AE09A, 0xFF1E4A1E),
        GREEN(0xFF3C8A3C, 0xFF48A048, 0xFF6FCF6F, 0xFF9AE09A, 0xFF1E4A1E),
        SECONDARY(Surfaces.FACE, Surfaces.FACE_HOVER, Surfaces.BORDER, Surfaces.BORDER_HOVER, Surfaces.LIP),
        DANGER(0xFFB83030, 0xFFD84040, 0xFFE06060, 0xFFFF9090, 0xFF5A1414),
        DANGER_TEXT(0, 0, 0, 0, 0),
        LINK(0, 0, 0, 0, 0);

        final int face;
        final int faceHover;
        final int border;
        final int borderHover;
        final int lip;

        Style(int face, int faceHover, int border, int borderHover, int lip) {
            this.face = face;
            this.faceHover = faceHover;
            this.border = border;
            this.borderHover = borderHover;
            this.lip = lip;
        }

        boolean isText() {
            return this == DANGER_TEXT || this == LINK;
        }
    }

    public static final int HEIGHT = 20;
    public static final int SMALL_HEIGHT = 15;
    private static final int OFF_TEXT = 0xFFA8A8A8;
    private static final int DANGER = 0xFFFF7A7A;
    private static final int DANGER_HOVER = 0xFFFFB3B3;
    private static final int ICON_GAP = 4;
    private static final int PADDING = 8;
    /** How large a main action's pixel label is drawn, as a dialog's buttons draw theirs. */
    private static final float PIXEL_LABEL_SCALE = 0.8f;

    private Component label;
    private Style style;
    private final Runnable onPress;
    @Nullable
    private Component disabledReason;
    private boolean enabled = true;
    @Nullable
    private Component tooltip;
    private ItemStack icon = ItemStack.EMPTY;
    @Nullable
    private String[] glyph;
    private boolean pixelLabel;

    public UiButton(Component label, Style style, Runnable onPress) {
        this.label = label;
        this.style = style;
        this.onPress = onPress;
        this.height = HEIGHT;
    }

    /** The width a button needs to show its label, and its icon when it has one. */
    public static int widthFor(Font font, Component label, boolean withIcon) {
        return font.width(label) + 2 * PADDING + (withIcon ? 16 + ICON_GAP : 0);
    }

    /**
     * The width this button needs for its label and icon. A pixel label is measured in the
     * pixel font, which is wider than Minecraft's.
     */
    public int naturalWidth() {
        Font font = Minecraft.getInstance().font;
        boolean withIcon = !icon.isEmpty() || glyph != null;
        int width = widthFor(font, label, withIcon);
        if (pixelLabel || style == Style.PRIMARY) {
            int iconWidth = !icon.isEmpty() ? 16 + ICON_GAP : glyph != null ? PixelIcon.width(glyph) + ICON_GAP : 0;
            width = Math.max(width, PixelFont.width(label, PixelFont.crispScale(PIXEL_LABEL_SCALE)) + 2 * PADDING
                    + iconWidth);
        }
        return width;
    }

    public void setLabel(Component label) {
        this.label = label;
    }

    public Component label() {
        return label;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public Style style() {
        return style;
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon;
    }

    /** A pixel icon before the label, or alone when the label is empty; see {@code PixelIcon}. */
    public void setGlyph(@Nullable String[] glyph) {
        this.glyph = glyph;
    }

    /**
     * Draws the label in the blocky pixel font, as a dialog's buttons are; a main action's label
     * always is.
     */
    public UiButton pixelLabel() {
        this.pixelLabel = true;
        return this;
    }

    /** Lets the button be used again, or not, with no reason given. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            this.disabledReason = null;
        }
    }

    /** Disables the button and says why in its tooltip; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.enabled = reason == null;
        this.disabledReason = reason;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A tooltip shown while the button can be used. */
    public void setTooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
    }

    /** Presses the button as a click would, if it can be used; for keyboard shortcuts. */
    public boolean press() {
        if (!enabled || !visible()) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        onPress.run();
        return true;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hovered = hovered(mouseX, mouseY);
        if (hovered) {
            Component hint = enabled ? tooltip : disabledReason;
            if (hint != null) {
                UiTooltip.set(hint);
            }
        }
        if (style.isText()) {
            drawText(graphics, font, hovered);
            return;
        }
        boolean pressed = hovered && enabled && Minecraft.getInstance().mouseHandler.isLeftPressed();
        int lipHeight = height < 18 ? 1 : Surfaces.LIP_HEIGHT;
        int sink = pressed ? lipHeight : 0;
        int right = x + width;
        int bottom = y + height;
        // Outline, then the lip along the bottom, then the face resting on it
        graphics.fill(x, y, right, bottom, Surfaces.OUTLINE);
        int faceTop = y + 1 + sink;
        int faceBottom = bottom - 1 - lipHeight + sink;
        if (!pressed) {
            graphics.fill(x + 1, faceBottom, right - 1, bottom - 1, enabled ? style.lip : Surfaces.LIP_DISABLED);
        }
        int face = !enabled ? Surfaces.FACE_DISABLED : hovered ? style.faceHover : style.face;
        int border = !enabled ? Surfaces.BORDER_DISABLED : hovered ? style.borderHover : style.border;
        graphics.fill(x + 1, faceTop, right - 1, faceBottom, border);
        graphics.fill(x + 2, faceTop + 1, right - 2, faceBottom - 1, face);

        int colour = enabled ? Theme.TEXT : OFF_TEXT;
        int faceHeight = faceBottom - faceTop;
        boolean withIcon = !icon.isEmpty();
        int iconSize = Math.min(16, faceHeight - 2);
        int iconWidth = withIcon ? iconSize + ICON_GAP : glyph != null ? PixelIcon.width(glyph) + ICON_GAP : 0;
        String plain = label.getString();
        if (plain.isEmpty()) {
            if (withIcon) {
                Draw.item(graphics, icon, x + (width - iconSize) / 2, faceTop + (faceHeight - iconSize) / 2, iconSize);
            } else if (glyph != null) {
                PixelIcon.drawCentered(graphics, glyph, x, faceTop, width, faceHeight, colour);
            }
            return;
        }
        int room = Math.max(0, width - 2 * PADDING - iconWidth);
        int labelWidth;
        String text = null;
        float pixelScale = 0;
        if (pixelLabel || style == Style.PRIMARY) {
            pixelScale = PixelFont.crispScale(PIXEL_LABEL_SCALE);
            labelWidth = Math.min(room, PixelFont.width(label, pixelScale));
        } else {
            text = Draw.cut(font, plain, room);
            labelWidth = font.width(text);
        }
        int left = x + (width - labelWidth - iconWidth) / 2;
        if (withIcon) {
            Draw.item(graphics, icon, left, faceTop + (faceHeight - iconSize) / 2, iconSize);
        } else if (glyph != null) {
            PixelIcon.draw(graphics, glyph, left, faceTop + (faceHeight - glyph.length) / 2, colour);
        }
        left += iconWidth;
        if (text == null) {
            PixelFont.drawCentered(graphics, label, left + labelWidth / 2, faceTop + faceHeight / 2.0f, room,
                    pixelScale, colour, true);
        } else {
            Draw.string(graphics, font, text, left, faceTop + (faceHeight - 8) / 2, colour);
        }
    }

    private void drawText(GuiGraphics graphics, Font font, boolean hovered) {
        int colour;
        if (!enabled) {
            colour = OFF_TEXT;
        } else if (style == Style.LINK) {
            colour = hovered ? Theme.LINK_HOVER : Theme.LINK;
        } else {
            colour = hovered ? DANGER_HOVER : DANGER;
        }
        String text = Draw.cut(font, label.getString(), width);
        int textY = y + (height - 8) / 2;
        Draw.string(graphics, font, text, x, textY, colour);
        if (style == Style.LINK && enabled) {
            graphics.fill(x, textY + 9, x + font.width(text), textY + 10, colour);
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!isMouseOver(click.x(), click.y()) || !click.left()) {
            return false;
        }
        // A disabled button still takes its click, so nothing under it reacts
        press();
        return true;
    }
}
