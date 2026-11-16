package com.theplumteam.remote.player;

/**
 * What this build of the mod is compiled with for player collections.
 *
 * <p>The keys are the public halves of the keys whose signatures the game believes: each a key
 * id, what it may sign ({@code online} or {@code root}) and its X.509 SubjectPublicKeyInfo in
 * Base64. A public key is no secret. The table is empty until those keys exist, and with no key
 * nothing signed is believed, so player collections stay off.
 */
final class PlayerBuildFlags {
    /** Where games read what players publish: everything of theirs lies below {@code p/} there. */
    static final String READ_ORIGIN = "https://blockpops-cdn.quickskin.net";

    static final String[][] KEYS = {};

    private PlayerBuildFlags() {
    }
}
