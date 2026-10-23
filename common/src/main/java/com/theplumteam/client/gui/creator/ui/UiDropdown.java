package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A control that shows "Label: value ▾" on a small raised face in the menus' button style, and
 * opens a menu of the choices under itself, such as
 * the overview's sort order or the default size of a bulk add. The menu ticks the chosen value
 * and opens with it highlighted; a long list scrolls. With the keyboard, Enter or Space opens it.
 * It can be disabled with a reason in its tooltip. It does not belong in a dialog: the screen
 * hands a dialog every click while it is open, so a menu could not be chosen from there.
 */
public class UiDropdown extends UiNode.Box {
    public static final int HEIGHT = 16;
    private static final int PADDING = 6;
    private static final String ARROW = "▾";
    private static final int OFF_TEXT = 0xFF6A6A6A;

    private final Component label;
    private final List<Component> options;
    private final IntConsumer onSelect;
    private int selected;
    @Nullable
    private Component disabledReason;

    /**
     * @param label    what the value is, or empty to show the value alone
     * @param onSelect told the index of a newly chosen value
     */
    public UiDropdown(Component label, List<Component> options, int selected, IntConsumer onSelect) {
        this.label = label;
        this.options = new ArrayList<>(options);
        this.selected = selected;
        this.onSelect = onSelect;
        this.height = HEIGHT;
    }

    public int selected() {
        return selected;
    }

    /** Chooses a value without telling the screen. */
    public void select(int index) {
        if (index >= 0 && index < options.size()) {
            selected = index;
        }
    }

    /** Disables the control and says why; null enables it again. */
    public void disable(@Nullable Component reason) {
        this.disabledReason = reason;
    }

    private Component text(int index) {
        Component value = index >= 0 && index < options.size() ? options.get(index) : Component.empty();
        return label.getString().isEmpty() ? value : CreatorScreen.tr("dropdown.value", label, value);
    }

    /** The width that fits the longest choice, so the control does not change size with it. */
    public int naturalWidth() {
        Font font = Minecraft.getInstance().font;
        int widest = 0;
        for (int i = 0; i < options.size(); i++) {
            widest = Math.max(widest, font.width(text(i)));
        }
        return widest + 3 * PADDING + font.width(ARROW);
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean hovered = hovered(mouseX, mouseY);
        boolean enabled = disabledReason == null;
        boolean lit = hovered && enabled;
        int border = !enabled ? Surfaces.BORDER_DISABLED : lit ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        int face = !enabled ? Surfaces.FACE_DISABLED : lit ? Surfaces.FACE_HOVER : Surfaces.FACE;
        int faceBottom = Draw.raised(graphics, x, y, width, height, border, face, 1);
        int textY = y + (faceBottom - y - 8) / 2 + 1;
        int arrowWidth = font.width(ARROW);
        int colour = !enabled ? OFF_TEXT : lit ? Theme.TEXT : Theme.CONTROL_TEXT;
        Draw.string(graphics, font, ARROW, x + width - PADDING - arrowWidth, textY, colour);
        Draw.text(graphics, font, text(selected).getString(), x + PADDING, textY,
                Math.max(0, width - 3 * PADDING - arrowWidth), colour);
        if (hovered && disabledReason != null) {
            UiTooltip.set(disabledReason);
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        if (!click.left() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        open();
        return true;
    }

    @Override
    public boolean key(UiInput.Key key) {
        // Keys reach every node when none has the keyboard; only a dropdown that has it opens
        if ((key.enter() || key.is(InputConstants.KEY_SPACE)) && CreatorScreen.hasKeyboard(this)) {
            open();
            return true;
        }
        return false;
    }

    /** Opens the menu of choices under the control, unless it is disabled. */
    public void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (disabledReason != null || !(minecraft.screen instanceof CreatorScreen screen) || screen.isModalOpen()) {
            return;
        }
        UiMenu menu = new UiMenu();
        for (int i = 0; i < options.size(); i++) {
            int index = i;
            Runnable choose = () -> {
                if (index != selected) {
                    selected = index;
                    onSelect.accept(index);
                }
            };
            if (i == selected) {
                menu.item(options.get(i), Component.literal("✔"), choose);
            } else {
                menu.item(options.get(i), choose);
            }
        }
        menu.highlight(selected);
        // Inside a scrolling area the control is not where its bounds say on the screen
        screen.openMenu(menu, UiScrollPane.onScreen(bounds()));
    }
}
