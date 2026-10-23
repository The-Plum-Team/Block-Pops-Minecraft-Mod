package com.theplumteam.client.gui.style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The menus' button, drawn like a Bedrock Edition button in dark-mode greys, as Quick Skin draws
 * its own: a flat charcoal face inside a thin light-grey border and a black outline, on a darker
 * lip that gives it depth. The face lightens under the mouse and sinks onto the lip while
 * pressed; a selected button keeps a bright border. A {@link Tone} colours the main action green
 * and a dangerous one red.
 *
 * <p>The label is in Minecraft's font with the icons' shadow, or, for a screen's main actions, in
 * the blocky {@link PixelFont} at the largest crisp size that fills about five eighths of the face.
 * An optional {@link PixelIcon} stands before the label, or alone when the label is empty.
 */
public class StyledButton extends Button {
    /** The button's colours. */
    public enum Tone {
        NEUTRAL(Surfaces.FACE, Surfaces.FACE_HOVER, Surfaces.BORDER, Surfaces.BORDER_HOVER, Surfaces.LIP),
        /** The screen's main action, such as Done: the green of the settings' toggles. */
        PRIMARY(0xFF3C8A3C, 0xFF48A048, 0xFF6FCF6F, 0xFF9AE09A, 0xFF1E4A1E),
        /** A dangerous action, such as deleting. */
        DANGER(0xFFB83030, 0xFFD84040, 0xFFE06060, 0xFFFF9090, 0xFF5A1414),
        /** A highlighted action of its own kind, such as the guaranteed token: gold. */
        GOLD(0xFF9A7414, 0xFFB88A18, 0xFFE0B040, 0xFFFFD060, 0xFF4A3608),
        /** A blue action, such as the regular token. */
        BLUE(0xFF2C5C9C, 0xFF3A70B8, 0xFF6FA0FF, 0xFF9DC0FF, 0xFF142A4A);

        final int face;
        final int faceHover;
        final int border;
        final int borderHover;
        final int lip;

        Tone(int face, int faceHover, int border, int borderHover, int lip) {
            this.face = face;
            this.faceHover = faceHover;
            this.border = border;
            this.borderHover = borderHover;
            this.lip = lip;
        }
    }

    private static final int LABEL_MARGIN = 6;
    /** Room the pixel label leaves at the face's sides, together. */
    private static final int PIXEL_LABEL_MARGIN = 16;
    /** The room inside the border is at least this many times the pixel capitals' height. */
    private static final float ROOM_PER_CAPITALS = 1.6f;
    private static final float MAX_PIXEL_SCALE = 3.0f;
    private static final int ICON_GAP = 4;

    private Tone tone = Tone.NEUTRAL;
    private boolean selected;
    private boolean pixelLabel;
    private float labelScale = 1.0f;
    private int lipHeight = Surfaces.LIP_HEIGHT;
    @Nullable
    private String[] icon;

    public StyledButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    public StyledButton tone(Tone tone) {
        this.tone = tone;
        return this;
    }

    public Tone tone() {
        return tone;
    }

    /** Draws the label in the pixel font, as a screen's main actions are. */
    public StyledButton pixelLabel() {
        this.pixelLabel = true;
        return this;
    }

    /** Draws the pixel label smaller, by this share of its usual size, rounded to crisp pixels. */
    public StyledButton labelScale(float labelScale) {
        this.labelScale = labelScale;
        return this;
    }

    /** A pixel icon before the label, or alone when the label is empty. */
    public StyledButton icon(@Nullable String[] icon) {
        this.icon = icon;
        return this;
    }

    /** A thinner lip, for a small button. */
    public StyledButton lipHeight(int lipHeight) {
        this.lipHeight = lipHeight;
        return this;
    }

    public StyledButton tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        return this;
    }

    /** Keeps the border bright, as on the tab or mode the button stands for. */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    /**
     * The height of a button whose pixel label is drawn at about {@code guiPixelsPerFontPixel},
     * with the label's usual room above and below.
     */
    public static int heightForPixelLabel(float guiPixelsPerFontPixel) {
        float scale = PixelFont.crispScale(guiPixelsPerFontPixel);
        return (int) Math.ceil(PixelFont.CAPITALS_HEIGHT * ROOM_PER_CAPITALS * scale - 1e-4) + 4 + Surfaces.LIP_HEIGHT;
    }

    @Override
    //? if >=26 {
    /*protected void extractContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    *///? } else {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //? }
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;
        boolean hovered = this.active && this.isHovered();
        boolean pressed = hovered && Minecraft.getInstance().mouseHandler.isLeftPressed();
        int sink = pressed ? lipHeight : 0;

        // Outline, then the lip along the bottom, then the face resting on it
        graphics.fill(x, y, right, bottom, Surfaces.OUTLINE);
        int faceTop = y + 1 + sink;
        int faceBottom = bottom - 1 - lipHeight + sink;
        if (!pressed && lipHeight > 0) {
            graphics.fill(x + 1, faceBottom, right - 1, bottom - 1, this.active ? tone.lip : Surfaces.LIP_DISABLED);
        }
        int face;
        int border;
        if (!this.active) {
            face = Surfaces.FACE_DISABLED;
            border = Surfaces.BORDER_DISABLED;
        } else {
            face = hovered ? tone.faceHover : tone.face;
            border = selected ? Surfaces.BORDER_SELECTED : hovered ? tone.borderHover : tone.border;
        }
        graphics.fill(x + 1, faceTop, right - 1, faceBottom, border);
        graphics.fill(x + 2, faceTop + 1, right - 2, faceBottom - 1, face);

        renderFace(graphics, faceTop, faceBottom);
    }

    /** Draws what the face shows between its top and bottom edges: the icon and the label. */
    protected void renderFace(GuiGraphics graphics, int faceTop, int faceBottom) {
        int color = this.active ? Surfaces.TEXT : 0xFFA8A8A8;
        int faceHeight = faceBottom - faceTop;
        String label = this.getMessage().getString();
        if (label.isEmpty()) {
            if (icon != null) {
                PixelIcon.drawCentered(graphics, icon, getX(), faceTop, this.width, faceHeight, color);
            }
            return;
        }
        int iconWidth = icon != null ? PixelIcon.width(icon) + ICON_GAP : 0;
        if (pixelLabel) {
            int room = this.width - PIXEL_LABEL_MARGIN - iconWidth;
            // The largest whole number of screen pixels per font pixel whose capitals fill at most
            // 1 / ROOM_PER_CAPITALS of the face, at least one GUI pixel, as Quick Skin sizes its
            // labels; a smaller label takes its share of that, still in whole screen pixels
            double guiScale = PixelFont.guiScale();
            int smallest = (int) Math.round(guiScale);
            int fitting = (int) Math.floor((faceHeight - 2) * guiScale / (PixelFont.CAPITALS_HEIGHT * ROOM_PER_CAPITALS) + 1e-4);
            int pixels = Math.max(smallest, Math.min((int) MAX_PIXEL_SCALE * smallest, fitting));
            if (labelScale < 1.0f) {
                pixels = Math.max(1, Math.round(pixels * labelScale));
            }
            float scale = (float) (pixels / guiScale);
            int labelWidth = Math.min(room, PixelFont.width(getMessage(), PixelFont.crispScale(scale)));
            int left = getX() + (this.width - labelWidth - iconWidth) / 2;
            if (icon != null) {
                PixelIcon.draw(graphics, icon, left, faceTop + (faceHeight - icon.length) / 2, color);
            }
            PixelFont.drawCentered(graphics, getMessage(), left + iconWidth + labelWidth / 2,
                    faceTop + faceHeight / 2.0f, room, scale, color, true);
            return;
        }
        Font font = Minecraft.getInstance().font;
        int room = this.width - 2 * LABEL_MARGIN - iconWidth;
        String fitted = UiText.fit(font, label, room);
        int left = getX() + (this.width - font.width(fitted) - iconWidth) / 2;
        int textY = faceTop + (faceHeight - 8) / 2;
        if (icon != null) {
            PixelIcon.draw(graphics, icon, left, faceTop + (faceHeight - icon.length) / 2, color);
        }
        UiText.draw(graphics, font, fitted, left + iconWidth, textY, color);
    }

    @Override
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        // Only the left button. The constant, not 0: 26.3 numbers the left button 1
        return button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    }
}
