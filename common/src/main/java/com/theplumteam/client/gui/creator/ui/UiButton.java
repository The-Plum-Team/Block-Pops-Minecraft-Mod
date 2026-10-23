package com.theplumteam.client.gui.creator.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A button in the look of the creator mockup: a bevelled block in one of four colours (gold for
 * the main action of a screen, green for a positive one, red for one that takes something away,
 * grey otherwise), or plain text for a link or a destructive action. A disabled button stays
 * visible and says why it cannot be used in its tooltip. It may show an item before its label.
 */
public class UiButton extends UiNode.Box {
    /** How a button looks. */
    public enum Style {
        PRIMARY(0xFFA87A06, 0xFFFFD84A, 0xFF5E4300),
        GREEN(0xFF3B8A26, 0xFF79D45A, 0xFF1D5410),
        SECONDARY(0xFF6E6E6E, 0xFFAAAAAA, 0xFF3C3C3C),
        DANGER(0xFFA32B2B, 0xFFE06A6A, 0xFF5C1414),
        DANGER_TEXT(0, 0, 0),
        LINK(0, 0, 0);

        final int fill;
        final int light;
        final int shade;

        Style(int fill, int light, int shade) {
            this.fill = fill;
            this.light = light;
            this.shade = shade;
        }

        boolean isText() {
            return this == DANGER_TEXT || this == LINK;
        }
    }

    public static final int HEIGHT = 20;
    public static final int SMALL_HEIGHT = 15;
    private static final int OFF_FILL = 0xFF2F2F2F;
    private static final int OFF_LIGHT = 0xFF4A4A4A;
    private static final int OFF_SHADE = 0xFF1C1C1C;
    private static final int OFF_TEXT = 0xFF9A9A9A;
    private static final int BORDER = 0xFF000000;
    private static final int HOVER_BORDER = 0xFFFFFFFF;
    private static final int DANGER = 0xFFFF7A7A;
    private static final int DANGER_HOVER = 0xFFFFB3B3;
    private static final int ICON_GAP = 4;
    private static final int PADDING = 8;

    private Component label;
    private Style style;
    private final Runnable onPress;
    @Nullable
    private Component disabledReason;
    private boolean enabled = true;
    @Nullable
    private Component tooltip;
    private ItemStack icon = ItemStack.EMPTY;

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

    public void setLabel(Component label) {
        this.label = label;
    }

    public Component label() {
        return label;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon;
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
        int fill = enabled ? style.fill : OFF_FILL;
        int light = enabled ? style.light : OFF_LIGHT;
        int shade = enabled ? style.shade : OFF_SHADE;
        Draw.panel(graphics, x, y, width, height, fill, hovered && enabled ? HOVER_BORDER : BORDER);
        // The bevel of the mockup: lit from the top left
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, light);
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, light);
        graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, shade);
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, shade);

        boolean withIcon = !icon.isEmpty();
        int iconSize = Math.min(16, height - 4);
        int room = width - 2 * PADDING - (withIcon ? iconSize + ICON_GAP : 0);
        String text = Draw.cut(font, label.getString(), Math.max(0, room));
        int contentWidth = font.width(text) + (withIcon ? iconSize + ICON_GAP : 0);
        int left = x + (width - contentWidth) / 2;
        if (withIcon) {
            Draw.item(graphics, icon, left, y + (height - iconSize) / 2, iconSize);
            left += iconSize + ICON_GAP;
        }
        graphics.drawString(font, text, left, y + (height - 8) / 2, enabled ? Theme.TEXT : OFF_TEXT, enabled);
    }

    private void drawText(GuiGraphics graphics, Font font, boolean hovered) {
        int colour;
        if (!enabled) {
            colour = OFF_TEXT;
        } else if (style == Style.LINK) {
            colour = hovered ? Theme.GOLD_LIGHT : Theme.GOLD;
        } else {
            colour = hovered ? DANGER_HOVER : DANGER;
        }
        String text = Draw.cut(font, label.getString(), width);
        int textY = y + (height - 8) / 2;
        graphics.drawString(font, text, x, textY, colour, false);
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
