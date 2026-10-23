package com.theplumteam.client.gui.style;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The menus' small pixel icons, drawn one GUI pixel per '#' with the text's dark drop shadow, so
 * they stay crisp at every scale. An icon is rows of {@code '#'} and {@code '.'}.
 */
public final class PixelIcon {
    public static final String[] GEAR = {
            "....#....", ".#.###.#.", "..#####..", ".##...##.", "###...###",
            ".##...##.", "..#####..", ".#.###.#.", "....#...."};
    public static final String[] BIN = {
            "..#####..", "#########", ".#######.", ".#.#.#.#.", ".#.#.#.#.",
            ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#######."};
    public static final String[] PENCIL = {
            "......##.", ".....####", "....####.", "...####..", "..####...",
            ".####....", "#.##.....", "##.......", "###......"};
    public static final String[] PLUS = {
            "...###...", "...###...", "...###...", "#########", "#########",
            "#########", "...###...", "...###...", "...###..."};
    public static final String[] CLOSE = {
            "##.....##", "###...###", ".###.###.", "..#####..", "...###...",
            "..#####..", ".###.###.", "###...###", "##.....##"};
    public static final String[] STAR = {
            "....#....", "...###...", "#########", ".#######.", "..#####..",
            "..##.##..", ".##...##.", "##.....##", "........."};
    public static final String[] PLAYER = {
            "...###...", "..#####..", "..#####..", "...###...", ".........",
            ".#######.", "#########", "#########", "#########"};
    public static final String[] SERVER = {
            "#########", "#.......#", "#.##..#.#", "#########", ".........",
            "#########", "#.......#", "#.##..#.#", "#########"};
    public static final String[] PALETTE = {
            "..#####..", ".#.....#.", "#.##.##.#", "#.......#", "#.##....#",
            "#......##", ".#....#.#", "..####..#", "........."};
    public static final String[] LIST = {
            "##.######", ".........", "##.######", ".........", "##.######",
            ".........", "##.######", ".........", "........."};
    public static final String[] WRENCH = {
            "......##.", ".....#..#", ".....#.##", "....###..", "...###...",
            "..###....", ".###.....", "###......", "##......."};
    public static final String[] KEY = {
            ".###.....", "#...#....", "#...#####", "#...#.#.#", ".###..#.#",
            ".........", ".........", ".........", "........."};
    public static final String[] BOX = {
            ".#######.", "#.......#", "#########", "#.......#", "#..###..#",
            "#..#.#..#", "#..###..#", "#.......#", "#########"};
    public static final String[] CHECK = {
            "........#", ".......##", "......##.", "#....##..", "##..##...",
            ".####....", "..##.....", ".........", "........."};
    public static final String[] ARROW_LEFT = {
            "...#.....", "..##.....", ".#######.", "########.", ".#######.",
            "..##.....", "...#.....", ".........", "........."};
    public static final String[] UPLOAD = {
            "....#....", "...###...", "..#####..", ".#######.", "...###...",
            "...###...", "...###...", ".........", "#########"};

    private PixelIcon() {
    }

    /** Draws an icon with its top-left corner at {@code (x, y)}, with the dark drop shadow. */
    public static void draw(GuiGraphics graphics, String[] icon, int x, int y, int color) {
        int shadow = (color & 0xFF000000) | (UiText.SHADOW & 0x00FFFFFF);
        drawPass(graphics, icon, x + 1, y + 1, shadow);
        drawPass(graphics, icon, x, y, color);
    }

    /** Draws an icon centred on a rectangle. */
    public static void drawCentered(GuiGraphics graphics, String[] icon, int x, int y, int width, int height, int color) {
        draw(graphics, icon, x + (width - width(icon)) / 2, y + (height - icon.length) / 2, color);
    }

    public static int width(String[] icon) {
        return icon.length == 0 ? 0 : icon[0].length();
    }

    private static void drawPass(GuiGraphics graphics, String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int column = 0;
            while (column < line.length()) {
                if (line.charAt(column) != '#') {
                    column++;
                    continue;
                }
                // One fill per run of pixels on the row
                int start = column;
                while (column < line.length() && line.charAt(column) == '#') {
                    column++;
                }
                graphics.fill(x + start, y + row, x + column, y + row + 1, color);
            }
        }
    }
}
