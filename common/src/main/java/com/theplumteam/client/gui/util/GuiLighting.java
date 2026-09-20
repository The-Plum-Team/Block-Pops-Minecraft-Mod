package com.theplumteam.client.gui.util;

import com.mojang.blaze3d.platform.Lighting;
//? if >=1.21.6 {
/*import net.minecraft.client.Minecraft;
*///? }

/**
 * Selects the lighting a GUI draws its models under.
 *
 * 1.21.6 turned `Lighting` from a holder of static setups into an instance the game
 * renderer owns, selected by a named entry.
 */
public final class GuiLighting {
    private GuiLighting() {
    }

    /** Flat lighting, as an item icon is drawn under. */
    public static void flatItems() {
        //? if >=1.21.6 {
        /*Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
        *///? } else {
        Lighting.setupForFlatItems();
        //? }
    }

    /** The lighting a three-dimensional item is drawn under. */
    public static void items3D() {
        //? if >=1.21.6 {
        /*Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
        *///? } else {
        Lighting.setupFor3DItems();
        //? }
    }
}
