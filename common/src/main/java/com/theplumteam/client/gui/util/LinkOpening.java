package com.theplumteam.client.gui.util;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;

import java.net.URI;

/**
 * Asks before sending a link to the platform's browser, and shows a folder in the
 * platform's file manager.
 *
 * 26.3 moved the opening itself off Util's platform enum and onto Blaze3D, and
 * its confirm screen takes the parsed URI where earlier versions took the text.
 */
public final class LinkOpening {
    private LinkOpening() {
    }

    /** The game's own confirmation, so an external link is never opened unasked. */
    public static ConfirmLinkScreen confirm(URI target, BooleanConsumer onChoice) {
        //? if >=26.3 {
        /*return new ConfirmLinkScreen(onChoice, target, false);
        *///? } else {
        return new ConfirmLinkScreen(onChoice, target.toString(), false);
        //? }
    }

    public static void open(URI target) {
        //? if >=26.3 {
        /*com.mojang.blaze3d.Blaze3D.openUri(target);
        *///? } else {
        net.minecraft.Util.getPlatform().openUri(target);
        //? }
    }

    /** Shows a folder of this computer in the platform's file manager. */
    public static void openFolder(java.nio.file.Path folder) {
        //? if >=26.3 {
        /*com.mojang.blaze3d.Blaze3D.openPath(folder);
        *///? } else {
        net.minecraft.Util.getPlatform().openFile(folder.toFile());
        //? }
    }
}
