package com.theplumteam.remote.player;

import java.util.Locale;

/**
 * Why a file or a collection record was refused, as a stable code beside the message of a
 * {@link com.theplumteam.worldcontent.WorldContentException}. The message is for whoever sent
 * the content and may change; the code is what another program can act on, so a value is never
 * renamed or given another meaning, only added.
 *
 * <p>Each value names one rule. Several refusals of one rule share its value: every way a PNG's
 * palette can be wrong is {@link #PNG_PALETTE}.
 */
public enum ContentError {
    /** A refusal outside the content core, such as the server's store or an operator's edit. */
    UNSPECIFIED,

    // Any file
    FILE_TYPE,
    FILE_SIZE,
    FILE_FACTS,
    FILE_MISSING,
    FILE_NOT_PNG,
    SHA256,

    // JSON, whatever it holds
    JSON_SYNTAX,
    JSON_UNKNOWN_KEY,
    JSON_MISSING_KEY,
    JSON_TYPE,
    JSON_STRING_LENGTH,
    JSON_RANGE,
    JSON_VECTOR,

    // Text an operator or a player typed
    TEXT_EMPTY,
    TEXT_CHARACTERS,

    // PNG
    PNG_BYTES,
    PNG_BYTES_ROLE,
    PNG_SIGNATURE,
    PNG_TRUNCATED,
    PNG_CRC,
    PNG_HEADER,
    PNG_SIZE,
    PNG_SIZE_ROLE,
    PNG_METHOD,
    PNG_INTERLACED,
    PNG_DEPTH,
    PNG_PALETTE,
    PNG_TRANSPARENCY,
    PNG_STRUCTURE,
    PNG_ANIMATED,
    PNG_CRITICAL_CHUNK,
    PNG_NOT_CANONICAL,
    PNG_DATA,

    // Models
    GEO_BYTES,
    GEO_GEOMETRIES,
    GEO_BONES,
    GEO_BONE_TREE,
    GEO_MESH,
    GEO_LOCATORS,
    GEO_CUBES,
    GEO_NAME,
    GEO_UV,
    GEO_BOUNDS,

    // Animations
    ANIMATION_BYTES,
    ANIMATION_COUNT,
    ANIMATION_NO_STAND,
    ANIMATION_NAME,
    ANIMATION_UNSUPPORTED,
    ANIMATION_LOOP,
    ANIMATION_LENGTH,
    ANIMATION_BONES,
    ANIMATION_KEYFRAMES,
    ANIMATION_EXPRESSION,
    ANIMATION_KEYFRAME,
    ANIMATION_TIME,
    ANIMATION_EASING,

    // A collection record
    RECORD_BYTES,
    RECORD_ID,
    RECORD_BACKGROUND,
    RECORD_BOX,
    RECORD_FLAG,
    RECORD_AUTHOR_URL,
    RECORD_LOGO_LAYOUT,
    RECORD_RARITY,
    RECORD_RARITY_WEIGHTS,
    RECORD_FIGURES,
    RECORD_FIGURE_ID,
    RECORD_FIGURE_KIND,
    RECORD_SCALE,
    RECORD_TEXTURE_RATIO,
    RECORD_ANIMATION,
    RECORD_VARIANTS,
    RECORD_BOX_FACE,

    // What a set of collections may hold together
    BUDGET_COLLECTIONS,
    BUDGET_BYTES,
    BUDGET_PIXELS,
    BUDGET_CUBES,
    BUDGET_KEYFRAMES;

    private final String id = name().toLowerCase(Locale.ROOT);

    /** The code as documents and lang keys spell it, such as {@code png_size_role}. */
    public String id() {
        return id;
    }
}
