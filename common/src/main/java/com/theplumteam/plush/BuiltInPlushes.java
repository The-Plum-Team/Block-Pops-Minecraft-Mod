package com.theplumteam.plush;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The plushes that exist as blocks of their own. Each id registers a block, its item and a
 * block entity type named plush_&lt;id&gt;, and names the model and texture they draw.
 */
public class BuiltInPlushes {
    public static final List<String> PLUSH_IDS = List.of(
        // Adventure Time
        "finn",
        "fionna",
        "jake",
        "cake",
        "bubblegum_princess",
        "marceline",
        "ice_king",
        "simon",
        "ice_king_no_beard",
        // Castle Crashers
        "red_knight",
        "blue_knight",
        "green_knight",
        "orange_knight",
        "pink_knight",
        "grey_knight",
        // Chainsaw Man
        "denji",
        "power",
        "blood_fiend",
        "makima",
        "aki",
        "kishibe",
        "mad_dog",
        "kobeni",
        "himeno",
        "reze",
        "bomb_girl",
        "angel",
        "beam",
        "yoru",
        "chainsaw_man",
        // Hunter x Hunter
        "gon",
        "gon_san",
        "killua",
        "kurapika",
        "kurapika_kurta",
        "leorio",
        "hisoka",
        "hisoka_4",
        // Jujutsu Kaisen
        "itadori",
        "sukuna",
        "gojo",
        "gojo_unmasked",
        "megumi",
        "nobara",
        "maki",
        "toge",
        "panda",
        "yuta",
        "todo",
        "geto",
        "kenjaku",
        "kirara",
        // Pikmin
        "red_pikmin",
        "yellow_pikmin",
        "blue_pikmin",
        "white_pikmin"
    );

    // Plushes that are variants of one another, the base plush first. A gacha machine hands out
    // the base; right-clicking a placed plush turns it into the next one of its group.
    public static final List<List<String>> VARIANT_GROUPS = List.of(
        List.of("finn", "fionna"),
        List.of("jake", "cake"),
        List.of("ice_king", "simon"),
        List.of("power", "blood_fiend"),
        List.of("kishibe", "mad_dog"),
        List.of("reze", "bomb_girl"),
        List.of("gon", "gon_san"),
        List.of("kurapika", "kurapika_kurta"),
        List.of("hisoka", "hisoka_4"),
        List.of("itadori", "sukuna"),
        List.of("gojo", "gojo_unmasked"),
        List.of("geto", "kenjaku")
    );

    // Secret plushes: no machine hands them out and no right-click reaches them. A supporter
    // turns a plush into its secret, and back, by using shears on it.
    public static final Map<String, String> SECRET_TRANSFORMS = Map.of(
        "ice_king", "ice_king_no_beard",
        "ice_king_no_beard", "ice_king"
    );

    /** The plush shears turn this one into, or null when it has no secret. */
    @Nullable
    public static String secretTransform(String plushId) {
        return SECRET_TRANSFORMS.get(plushId);
    }

    /** The plush a right-click turns this one into, or null when it has no variants. */
    @Nullable
    public static String nextVariant(String plushId) {
        for (List<String> group : VARIANT_GROUPS) {
            int index = group.indexOf(plushId);
            if (index >= 0) {
                return group.get((index + 1) % group.size());
            }
        }
        return null;
    }
}
