package com.theplumteam.plush;

import java.util.List;

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
}
