package com.theplumteam.client.gui.creator.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A box that is ticked or not, with its label beside it; clicking the box or the label toggles
 * it. Ticked boxes are gold, as the mockup's. It can be disabled with a reason in its tooltip.
 */
public class UiCheckbox extends UiNode.Box {
    public static final int BOX = 10;
    private static final int GAP = 5;
    private static final int OFF_TEXT = 0xFF707070;

    private final Component label;
    private final Consumer<Boolean> onChange;
    private boolean checked;
    @Nullable
    private Component disabledReason;
    private boolean enabled = true;

    public UiCheckbox(Component label, boolean checked, Consumer<Boolean> onChange) {
        this.label = label;
        this.checked = checked;
        this.onChange = onChange;
        this.height = BOX + 2;
    }

    /** The width of the box and its whole label. */
    public static int widthFor(Font font, Component label) {
        return BOX + GAP + font.width(label);
    }

    public boolean checked() {
        return checked;
    }

    /** Ticks or clears the box without telling the screen. */
    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    /** Disables the box and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.enabled = reason == null;
        this.disabledReason = reason;
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hovered = hovered(mouseX, mouseY);
        int boxY = y + (height - BOX) / 2;
        int outline = !enabled ? Theme.CONTROL_OUTLINE : hovered ? Theme.TEXT : Theme.FIELD_OUTLINE;
        Draw.panel(graphics, x, boxY, BOX, BOX, 0xFF000000, outline);
        if (checked) {
            graphics.fill(x + 2, boxY + 2, x + BOX - 2, boxY + BOX - 2, enabled ? Theme.GOLD : Theme.CONTROL_OUTLINE);
        }
        Draw.text(graphics, font, label.getString(), x + BOX + GAP, y + (height - 8) / 2 + 1,
                Math.max(0, width - BOX - GAP), enabled ? Theme.TEXT_SOFT : OFF_TEXT);
        if (hovered && disabledReason != null) {
            UiTooltip.set(disabledReason);
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        if (enabled) {
            checked = !checked;
            onChange.accept(checked);
        }
        return true;
    }
}
