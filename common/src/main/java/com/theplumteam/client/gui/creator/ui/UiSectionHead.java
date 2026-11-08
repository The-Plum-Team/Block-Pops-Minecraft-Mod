package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The head of a section of a form, which says what the section holds and what it is set to: a
 * one-unit line across the section's top, the section's name in the small pixel font with its
 * middle {@value #LABEL_MIDDLE} units under the line, and its value on the right in Minecraft's
 * font, cut with "..." to the room the name leaves. The section's content starts
 * {@value #HEIGHT} units under the line.
 *
 * <p>Made {@link #stacked}, it is a narrow form's row label instead: the name, and the value under
 * it in faint text, centred on the height of the row it labels, in a column of its own beside the
 * row; with no value the name alone is centred.
 *
 * <p>The value is asked for on every frame, so the head follows the form without being rebuilt.
 * A hint, such as what a setting does, is the name's tooltip.
 */
public class UiSectionHead extends UiNode.Box {
    /** From the head's line to where the section's content starts. */
    public static final int HEIGHT = 18;
    /** From the head's line to the middle of its name. */
    public static final int LABEL_MIDDLE = 10;
    // The room kept between the name and the value
    private static final int VALUE_GAP = 12;
    private static final float LABEL_SCALE = 0.67f;

    private final Component label;
    private final Supplier<Component> value;
    private IntSupplier valueColour = () -> Theme.FAINT;
    @Nullable
    private Component hint;
    private boolean stacked;

    /**
     * @param value what the section is set to, asked as it is drawn; empty for nothing
     */
    public UiSectionHead(Component label, Supplier<Component> value) {
        this.label = label;
        this.value = value;
        this.height = HEIGHT;
    }

    /**
     * The value's colour, asked as it is drawn, such as amber while the section needs something;
     * faint unless set.
     */
    public UiSectionHead valueColour(IntSupplier colour) {
        this.valueColour = colour;
        return this;
    }

    /** What the section is for, shown over its name; null for nothing. */
    public UiSectionHead hint(@Nullable Component hint) {
        this.hint = hint;
        return this;
    }

    /** Draws it as a narrow form's row label, the value under the name, rather than as a head. */
    public UiSectionHead stacked(boolean stacked) {
        this.stacked = stacked;
        return this;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible() || width <= 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String whole = this.value.get().getString();
        int colour = this.valueColour.getAsInt();
        boolean hovered = pointerOver(mouseX, mouseY);
        if (this.stacked) {
            int middle = y + height / 2;
            if (whole.isEmpty()) {
                SidebarScreen.smallLabel(graphics, this.label, x, middle, width, Theme.TEXT_SOFT);
            } else {
                SidebarScreen.smallLabel(graphics, this.label, x, middle - 4, width, Theme.TEXT_SOFT);
                String shown = Draw.cut(font, whole, width);
                Draw.string(graphics, font, shown, x, middle + 1, colour);
                if (hovered && this.hint == null && !shown.equals(whole)) {
                    UiTooltip.set(Component.literal(whole));
                }
            }
            if (hovered && this.hint != null) {
                UiTooltip.set(SidebarScreen.tooltipLines(this.hint));
            }
            return;
        }
        graphics.fill(x, y, x + width, y + 1, Theme.SEPARATOR);
        int middle = y + LABEL_MIDDLE;
        int labelWidth = PixelFont.width(this.label, PixelFont.crispScale(LABEL_SCALE));
        String shown = Draw.cut(font, whole, Math.max(0, width - labelWidth - VALUE_GAP));
        if (!shown.isEmpty()) {
            Draw.string(graphics, font, shown, x + width - font.width(shown), middle - 4, colour);
        }
        SidebarScreen.smallLabel(graphics, this.label, x, middle, width, Theme.TEXT_SOFT);
        boolean overLabel = mouseX >= x && mouseX < x + labelWidth + 4 && mouseY >= y + 3 && mouseY < y + HEIGHT;
        boolean overValue = mouseX >= x + width - font.width(shown) && mouseY >= y + 3 && mouseY < y + HEIGHT
                && mouseX < x + width;
        if (overLabel && this.hint != null) {
            UiTooltip.set(SidebarScreen.tooltipLines(this.hint));
        } else if (overValue && !shown.equals(whole)) {
            UiTooltip.set(SidebarScreen.tooltipLines(Component.literal(whole)));
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // A label: what is under it keeps its clicks
        return false;
    }

    // Under the pointer, for its own tooltip, though it takes no clicks
    private boolean pointerOver(int mouseX, int mouseY) {
        return visible() && bounds().contains(mouseX, mouseY);
    }
}
