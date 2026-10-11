package com.theplumteam.plush;

import java.util.List;

/**
 * The plushes that exist as blocks of their own. Each id registers a block, its item and a
 * block entity type named plush_&lt;id&gt;, and names the model and texture they draw.
 */
public class BuiltInPlushes {
    public static final List<String> PLUSH_IDS = List.of(
        "denji",
        "chainsaw_man",
        "power",
        "power_casual",
        "aki",
        "makima",
        "himeno",
        "kobeni",
        "kishibe",
        "kishibe_young",
        "angel",
        "beam",
        "reze",
        "reze_bomb",
        "yoru"
    );
}
