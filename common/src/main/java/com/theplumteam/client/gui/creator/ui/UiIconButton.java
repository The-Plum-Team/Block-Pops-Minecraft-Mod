package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A small square button that switches something on and off, such as a preview's spinning or its
 * night light: a raised face in the menus' border, {@value #SIZE} units a side, with a pixel icon
 * centred on it. While its state is on it is lit, its border in the selection's blue and its icon
 * in the light blue, so the state shows without a label; its tooltip names it. A click plays the
 * click sound and runs its action.
 */
public class UiIconButton extends UiNode.Box {
    /** The button's width and height. */
    public static final int SIZE = 14;

    private final Supplier<String[]> icon;
    private final BooleanSupplier on;
    private final Supplier<Component> tooltip;
    private final Runnable action;

    /**
     * @param icon    the pixel icon, asked as it is drawn, so it may follow the state (a sun or a moon)
     * @param on      whether the state it switches is on, which lights it
     * @param tooltip what it does, asked as it is drawn
     */
    public UiIconButton(Supplier<String[]> icon, BooleanSupplier on, Supplier<Component> tooltip, Runnable action) {
        this.icon = icon;
        this.on = on;
        this.tooltip = tooltip;
        this.action = action;
        this.width = SIZE;
        this.height = SIZE;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        boolean hovered = hovered(mouseX, mouseY);
        boolean lit = this.on.getAsBoolean();
        int border = lit ? Theme.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        Draw.raised(graphics, x, y, width, height, border, hovered ? Surfaces.FACE_HOVER : Surfaces.FACE, 1);
        PixelIcon.drawCentered(graphics, this.icon.get(), x, y, width, height - 1,
                lit ? Theme.SELECTED_LIGHT : Theme.TEXT_SOFT);
        if (hovered) {
            UiTooltip.set(this.tooltip.get());
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        UiSounds.play(ModSounds.CLICK);
        this.action.run();
        return true;
    }
}
