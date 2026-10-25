package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A tab of a dialog's sidebar, as Quick Skin's settings draw theirs: a row with an optional pixel
 * icon and the tab's name, left-aligned. The selected tab has a slate highlight with a brighter bar
 * along its left edge, matching the dialog's header; an unselected tab is plain, lightening under
 * the mouse.
 */
public class TabButton extends Button {
    private boolean selected;
    @Nullable
    private String[] icon;

    private static final int SELECTED_BG = 0xFF4E6078;
    private static final int HOVER_BG = 0xFF444549;
    private static final int SELECTED_BAR = 0xFF6F8FB0;
    private static final int SELECTED_TEXT = 0xFFFFFFFF;
    private static final int HOVER_TEXT = 0xFFCCCCCC;
    private static final int UNSELECTED_TEXT = 0xFFB0B0B0;
    private static final int BAR_WIDTH = 2;

    public TabButton(int x, int y, int width, int height, Component label, boolean selected, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.selected = selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return this.selected;
    }

    /** The pixel icon before the name, as rows of {@code '#'} and {@code '.'}. */
    public TabButton setIcon(@Nullable String[] icon) {
        this.icon = icon;
        return this;
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        UiSounds.play(soundManager, ModSounds.TAB);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = this.getX();
        int y = this.getY();
        boolean hovered = !this.selected && this.active && this.isHovered();
        if (this.selected) {
            graphics.fill(x, y, x + this.width, y + this.height, SELECTED_BG);
            graphics.fill(x, y, x + BAR_WIDTH, y + this.height, SELECTED_BAR);
        } else if (hovered) {
            graphics.fill(x, y, x + this.width, y + this.height, HOVER_BG);
        }
        int color = this.selected ? SELECTED_TEXT : hovered ? HOVER_TEXT : UNSELECTED_TEXT;

        int textX = x + 8;
        if (icon != null && icon.length > 0) {
            PixelIcon.draw(graphics, icon, textX, y + (this.height - icon.length) / 2, color);
            textX += PixelIcon.width(icon) + 5;
        }
        Font font = Minecraft.getInstance().font;
        UiText.drawFitted(graphics, font, getMessage().getString(), textX, y + (this.height - 8) / 2,
                x + this.width - 4 - textX, color);
    }

    @Override
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        // Only allow left-click. The constant, not 0: 26.3 numbers the left button 1
        return button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    }

    /**
     * A tab of the machine menu's top bar, choosing the kind of collection it lists: a raised
     * button in the menus' style with an item icon before its label in the pixel font. The
     * selected tab keeps the blue border of a selection and a lighter face, so the two read as a
     * segmented switch.
     */
    public static class Folder extends TabButton {
        public static final int HEIGHT = 28;
        private static final int ICON_SIZE = 16;
        private static final float UNSELECTED_ICON_SCALE = 0.85f;
        private static final int PADDING = 10;
        private static final int ICON_GAP = 6;
        private static final int LIP = 3;

        private static final int SELECTED_TEXT = 0xFFFFFFFF;
        private static final int UNSELECTED_TEXT = 0xFFB0B0B0;
        private static final int HOVERED_TEXT = 0xFFDDDDDD;

        private final ItemStack icon;

        public Folder(int x, int y, Component label, ItemStack icon, boolean selected, OnPress onPress) {
            this(x, y, label, icon, selected, onPress, Integer.MAX_VALUE);
        }

        /** A tab no wider than {@code maxWidth}: a longer label is drawn smaller to fit. */
        public Folder(int x, int y, Component label, ItemStack icon, boolean selected, OnPress onPress, int maxWidth) {
            super(x, y, Math.min(widthOf(label), maxWidth), HEIGHT, label, selected, onPress);
            this.icon = icon;
        }

        public static int widthOf(Component label) {
            return PADDING + ICON_SIZE + ICON_GAP + PixelFont.width(label, 1.0f) + PADDING;
        }

        public int left() {
            return getX();
        }

        public int right() {
            return getX() + width;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getX();
            int y = getY();
            boolean selected = isSelected();
            boolean hovered = !selected && isHovered();
            int faceBottom = Surfaces.raised(graphics, x, y, x + width, y + height,
                    selected ? Surfaces.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER,
                    selected || hovered ? Surfaces.FACE_HOVER : Surfaces.FACE, Surfaces.LIP, LIP);
            int middle = (y + 1 + faceBottom) / 2;

            int iconX = x + PADDING;
            // An unselected tab's icon is drawn a little smaller, centred where the full one sits
            float iconScale = selected ? 1.0f : UNSELECTED_ICON_SCALE;
            GuiPose.push(graphics);
            GuiPose.translate(graphics, iconX + ICON_SIZE / 2.0f, middle);
            GuiPose.scale(graphics, iconScale, iconScale);
            GuiPose.translate(graphics, -ICON_SIZE / 2.0f, -ICON_SIZE / 2.0f);
            graphics.renderItem(icon, 0, 0);
            GuiPose.pop(graphics);

            int textColor = selected ? SELECTED_TEXT : hovered ? HOVERED_TEXT : UNSELECTED_TEXT;
            Component text = PixelFont.text(getMessage());
            int room = width - PADDING - ICON_SIZE - ICON_GAP - PADDING;
            float scale = Math.min(1.0f, room / (float) Math.max(1, PixelFont.width(getMessage(), 1.0f)));
            PixelFont.draw(graphics, text, iconX + ICON_SIZE + ICON_GAP,
                    Math.round(middle + 0.5f - PixelFont.CAPITALS_MIDDLE * scale), scale, textColor, true);
        }
    }
}
