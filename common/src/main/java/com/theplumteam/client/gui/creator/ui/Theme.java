package com.theplumteam.client.gui.creator.ui;

/**
 * The colours and spacing of the creator screens, taken from the approved mockup at half its
 * pixel size, which is the size of Minecraft's GUI units at the usual scale. Every colour
 * spells out its alpha byte, because from 1.21.6 a colour with alpha 0 draws nothing.
 */
public final class Theme {
    private Theme() {
    }

    // Panel, dimming and outlines, the same as SettingsScreen and the World collection screens
    public static final int PANEL = 0xFF000000;
    public static final int PANEL_OUTLINE = 0x60FFFFFF;
    public static final int DIM = 0x70000000;
    public static final int MODAL_DIM = 0xA0000000;
    public static final int CARD = 0xFF121212;
    public static final int CARD_OUTLINE = 0xFF3A3A3A;
    public static final int DIALOG = 0xFF0C0C0C;
    public static final int DIALOG_OUTLINE = 0xFF9A9A9A;
    public static final int MENU = 0xFF181818;
    public static final int MENU_OUTLINE = 0xFF8A8A8A;
    public static final int MENU_HOVER = 0xFF2C2C2C;
    public static final int SEPARATOR = 0xFF3A3A3A;
    public static final int TOAST = 0xFF1B1B1B;
    public static final int TOAST_OUTLINE = 0xFF6A6A6A;
    public static final int TRACK = 0xFF333333;

    // Text
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_SOFT = 0xFFDDDDDD;
    public static final int MUTED = 0xFFAAAAAA;
    public static final int FAINT = 0xFF8F8F8F;

    // Accents
    public static final int GOLD = 0xFFFFD84A;
    public static final int GOLD_LIGHT = 0xFFFFF1A8;
    public static final int GOLD_DARK = 0xFF211B05;
    public static final int GREEN = 0xFF55FF55;
    public static final int AMBER = 0xFFFFAA00;
    public static final int ORANGE = 0xFFFFB37A;
    public static final int RED = 0xFFFF5555;
    public static final int PENDING = 0xFFFFFF80;

    // Status badges: background, then text and border
    public static final int BADGE_PUBLISHED_BG = 0xFF174D17;
    public static final int BADGE_PUBLISHED_FG = 0xFFB9FFB0;
    public static final int BADGE_DRAFT_BG = 0xFF4A3D00;
    public static final int BADGE_DRAFT_FG = 0xFFFFE98A;
    public static final int BADGE_ARCHIVED_BG = 0xFF2A2A2A;
    public static final int BADGE_ARCHIVED_FG = 0xFFCFCFCF;
    public static final int BADGE_MISSING_BG = 0xFF5A1414;
    public static final int BADGE_MISSING_FG = 0xFFFFB3B3;
    public static final int BADGE_SOON_BG = 0xFF262626;
    public static final int BADGE_SOON_FG = 0xFFD0D0D0;

    // Controls: segmented options, flat tabs and check boxes
    public static final int CONTROL = 0xFF141414;
    public static final int CONTROL_OUTLINE = 0xFF444444;
    public static final int CONTROL_TEXT = 0xFFCCCCCC;
    public static final int FIELD_OUTLINE = 0xFFA0A0A0;

    // Spacing
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;

    /** The height of a line of text with the gap below it. */
    public static final int LINE_HEIGHT = 11;
}
