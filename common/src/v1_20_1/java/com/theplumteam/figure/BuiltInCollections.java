package com.theplumteam.figure;

import java.util.List;

/**
 * Defines the built-in collection IDs that will have box blocks registered for them.
 * The actual collection content (figures, textures, etc.) is loaded from JSON files.
 */
public class BuiltInCollections {
    // Add new collection IDs here to automatically create box blocks for them
    public static final List<String> COLLECTION_IDS = List.of(
        "jojos",
        "jujutsukaisen",
        "adventuretime",
        "supermario",
        "starwars",
        "fnaf",
        "onepiece",
        "deltarune",
        "alienstage",
        "strangerthings",
        "dispatch",
        "ultrakill"
        // Add more collections as needed
    );

    /**
     * Gets the display name for a collection ID (used for block names)
     */
    public static String getDisplayName(String collectionId) {
        return switch (collectionId) {
            case "jojos" -> "JoJos";
            case "jujutsukaisen" -> "Jujutsu Kaisen";
            case "adventuretime" -> "Adventure Time";
            case "supermario" -> "Super Mario";
            case "starwars" -> "Star Wars";
            case "fnaf" -> "FNAF";
            case "onepiece" -> "One Piece";
            case "deltarune" -> "Deltarune";
            case "alienstage" -> "Alien Stage";
            case "strangerthings" -> "Stranger Things";
            case "dispatch" -> "Dispatch";
            case "ultrakill" -> "ULTRAKILL";
            default -> collectionId;
        };
    }
}
