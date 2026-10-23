package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.Surfaces;

/**
 * The colours and spacing of the creator screens, in the dark-mode Bedrock look the mod's menus
 * share with Quick Skin: the surfaces come from {@link Surfaces}, selection is its blue, and a
 * selected tab or menu row is the slate of the settings dialog's sidebar. Every colour spells out
 * its alpha byte, because from 1.21.6 a colour with alpha 0 draws nothing.
 */
public final class Theme {
    private Theme() {
    }

    // Panel, dimming and outlines: a dialog's charcoal face on the dimmed starry background
    public static final int PANEL = Surfaces.DIALOG_FACE;
    public static final int PANEL_OUTLINE = Surfaces.BORDER;
    public static final int DIM = 0x70000000;
    public static final int MODAL_DIM = 0x80000000;
    public static final int CARD = Surfaces.FACE;
    public static final int CARD_OUTLINE = Surfaces.BORDER;
    public static final int DIALOG = Surfaces.DIALOG_FACE;
    public static final int DIALOG_OUTLINE = Surfaces.BORDER;
    public static final int MENU = Surfaces.DIALOG_FACE;
    public static final int MENU_OUTLINE = Surfaces.BORDER;
    public static final int MENU_HOVER = 0xFF4E6078;
    public static final int SEPARATOR = 0xFF46474A;
    public static final int TOAST = Surfaces.DIALOG_FACE;
    public static final int TOAST_OUTLINE = Surfaces.BORDER;
    public static final int TRACK = Surfaces.WELL;

    // Text
    public static final int TEXT = Surfaces.TEXT;
    public static final int TEXT_SOFT = 0xFFDDDDDD;
    public static final int MUTED = Surfaces.TEXT_DIM;
    public static final int FAINT = Surfaces.TEXT_MUTED;

    // Selection and focus: Quick Skin's blue border, and the slate of a selected tab with its bar
    public static final int SELECTED = Surfaces.SELECTED;
    public static final int SELECTED_LIGHT = 0xFF9DC0FF;
    public static final int HIGHLIGHT = 0xFF4E6078;
    public static final int HIGHLIGHT_BAR = 0xFF6F8FB0;
    /** The face of a plain row or tab under the mouse. */
    public static final int HOVER = 0xFF444549;
    /** A link's text, and under the mouse. */
    public static final int LINK = 0xFF8FB4FF;
    public static final int LINK_HOVER = 0xFFC6DAFF;

    // Accents. Gold is the guaranteed token's colour, and marks what is favoured or set aside
    public static final int GOLD = 0xFFFFD060;
    public static final int GOLD_LIGHT = 0xFFFFE9A0;
    public static final int GOLD_DARK = 0xFF4A3608;
    public static final int GREEN = 0xFF6FCF6F;
    public static final int AMBER = 0xFFFFB040;
    public static final int ORANGE = 0xFFFFB37A;
    public static final int RED = 0xFFFF6B6B;
    public static final int PENDING = 0xFFFFFF80;

    // Status badges: background, then text and border, in the dialog accents' colours
    public static final int BADGE_PUBLISHED_BG = DialogFrame.Accent.CREATE.strip;
    public static final int BADGE_PUBLISHED_FG = 0xFF9AE09A;
    public static final int BADGE_DRAFT_BG = GOLD_DARK;
    public static final int BADGE_DRAFT_FG = GOLD;
    public static final int BADGE_ARCHIVED_BG = Surfaces.FACE_DISABLED;
    public static final int BADGE_ARCHIVED_FG = Surfaces.TEXT_DIM;
    public static final int BADGE_MISSING_BG = DialogFrame.Accent.DANGER.strip;
    public static final int BADGE_MISSING_FG = 0xFFFF9090;
    public static final int BADGE_SOON_BG = Surfaces.FACE_DISABLED;
    public static final int BADGE_SOON_FG = Surfaces.TEXT_DIM;

    // Controls: the sunk well of fields, tracks and check boxes, and its borders
    public static final int CONTROL = Surfaces.WELL;
    public static final int CONTROL_OUTLINE = Surfaces.BORDER_DISABLED;
    public static final int CONTROL_TEXT = Surfaces.TEXT_DIM;
    public static final int FIELD_OUTLINE = Surfaces.BORDER_HOVER;

    // Spacing
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;

    /** The height of a line of text with the gap below it. */
    public static final int LINE_HEIGHT = 11;

    /** The pixel icons the creator adds to the menus' own ({@code PixelIcon}): rows of '#' and '.'. */
    public static final String[] ICON_HELP = {
            "..#####..", ".##...##.", ".......##", "......##.", "....##...",
            "....##...", ".........", "....##...", "....##..."};
    public static final String[] ICON_ARCHIVE = {
            "#########", "#.......#", "#########", ".#.....#.", ".#.###.#.",
            ".#.....#.", ".#.....#.", ".#.....#.", ".#######."};
    public static final String[] ICON_SAVE = {
            "########.", "#.#####.#", "#.#####.#", "#.......#", "#.......#",
            "#.#####.#", "#.#...#.#", "#.#...#.#", "#########"};
    public static final String[] ICON_UNDO = {
            "..#......", ".##......", "#######..", ".##....#.", "..#.....#",
            "........#", "........#", ".......#.", "..#####.."};
    public static final String[] ICON_FILE = {
            "#####....", "#...##...", "#...#.#..", "#...####.", "#......#.",
            "#......#.", "#......#.", "#......#.", "########."};
}
