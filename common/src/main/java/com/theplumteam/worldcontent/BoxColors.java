package com.theplumteam.worldcontent;

import java.util.List;

/**
 * The names of the plain box colours, each of which has a bundled box texture: the serialized
 * names of {@code com.theplumteam.block.PopBlockColor}, in its order. The content core keeps
 * its own list because that enum needs Minecraft; a test holds the two equal.
 */
public final class BoxColors {
    public static final List<String> NAMES = List.of("original", "black", "blue", "brown", "cyan", "gray", "green",
            "light_blue", "light_gray", "lime", "magenta", "orange", "pink", "purple", "red", "yellow");

    private BoxColors() {
    }
}
