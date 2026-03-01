package com.theplumteam.client.gui.util;

import com.theplumteam.client.gui.widget.TabButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Factory for creating specialized button widgets
 */
public class ButtonFactory {

    /**
     * Creates a tab button for tabbed interfaces.
     * Uses custom styling for consistent tabbed interface design.
     */
    public static Button createTab(int x, int y, int width, int height, Component label, boolean selected, Button.OnPress onPress) {
        return new TabButton(x, y, width, height, label, selected, onPress);
    }
}
