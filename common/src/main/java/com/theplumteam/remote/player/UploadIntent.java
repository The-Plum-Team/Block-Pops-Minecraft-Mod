package com.theplumteam.remote.player;

import com.theplumteam.worldcontent.Sha256;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * What a player is about to ask the upload service to do, as one line of text. The player's
 * Minecraft session is bound to exactly this line, so a session proves "this account wants this
 * action on this content" and nothing more. The game and the service both build and read it
 * here, so they cannot disagree about what a line means.
 *
 * <pre>
 * publish|new|0|&lt;package sha256&gt;
 * publish|&lt;id&gt;|&lt;base rev&gt;|&lt;package sha256&gt;
 * list
 * rollback|&lt;id&gt;|&lt;head rev&gt;|&lt;to rev&gt;
 * withdraw|&lt;id&gt;|&lt;head rev&gt;|&lt;rev&gt;
 * delete|&lt;id&gt;|&lt;head rev&gt;
 * </pre>
 *
 * @param id       the collection it is about; null for a new one and for list
 * @param baseRev  the head revision the player last saw: 0 for a new collection
 * @param rev      the revision to go back to or to withdraw; 0 otherwise
 * @param sha256   the SHA-256 of the package to publish; null otherwise
 */
public record UploadIntent(Action action, @Nullable String id, int baseRev, int rev, @Nullable String sha256) {
    /** The longest an intent's text is. */
    public static final int MAX_LENGTH = 128;
    private static final String NEW = "new";

    /** What is asked. */
    public enum Action {
        PUBLISH("publish"), LIST("list"), ROLLBACK("rollback"), WITHDRAW("withdraw"), DELETE("delete");

        private final String word;

        Action(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }
    }

    public UploadIntent {
        boolean ok = action != null && switch (action) {
            case LIST -> id == null && baseRev == 0 && rev == 0 && sha256 == null;
            case PUBLISH -> Sha256.isValidHex(sha256) && rev == 0
                    && (id == null ? baseRev == 0 : PlayerIds.isPlayerId(id) && isRev(baseRev));
            case ROLLBACK, WITHDRAW -> PlayerIds.isPlayerId(id) && isRev(baseRev) && isRev(rev) && rev != baseRev
                    && sha256 == null;
            case DELETE -> PlayerIds.isPlayerId(id) && isRev(baseRev) && rev == 0 && sha256 == null;
        };
        if (!ok) {
            throw new IllegalArgumentException("Invalid intent");
        }
    }

    public static UploadIntent publishNew(String packageSha256) {
        return new UploadIntent(Action.PUBLISH, null, 0, 0, packageSha256);
    }

    public static UploadIntent publishChanges(String id, int baseRev, String packageSha256) {
        return new UploadIntent(Action.PUBLISH, id, baseRev, 0, packageSha256);
    }

    public static UploadIntent list() {
        return new UploadIntent(Action.LIST, null, 0, 0, null);
    }

    public static UploadIntent rollback(String id, int headRev, int toRev) {
        return new UploadIntent(Action.ROLLBACK, id, headRev, toRev, null);
    }

    public static UploadIntent withdraw(String id, int headRev, int rev) {
        return new UploadIntent(Action.WITHDRAW, id, headRev, rev, null);
    }

    public static UploadIntent delete(String id, int headRev) {
        return new UploadIntent(Action.DELETE, id, headRev, 0, null);
    }

    /** The intent as its one line of text. */
    public String text() {
        return switch (action) {
            case LIST -> action.word();
            case PUBLISH -> action.word() + "|" + (id == null ? NEW : id) + "|" + baseRev + "|" + sha256;
            case ROLLBACK, WITHDRAW -> action.word() + "|" + id + "|" + baseRev + "|" + rev;
            case DELETE -> action.word() + "|" + id + "|" + baseRev;
        };
    }

    /** The lowercase hex SHA-256 of the intent's text, which is what a session is bound to. */
    public String digest(Sha256 sha) {
        return sha.hex(text().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The text whose lowercase hex SHA-1 a player's game joins as its Minecraft "server", so that
     * the join proves this account asked for this intent under this single-use nonce.
     *
     * @param nonce        the 32 lowercase hex digits the service answered a challenge with
     * @param intentDigest the intent's {@link #digest}
     */
    public static String joinText(String nonce, String intentDigest) {
        if (nonce == null || nonce.length() != 32 || !Sha256.isValidHex(nonce + nonce) || !Sha256.isValidHex(intentDigest)) {
            throw new IllegalArgumentException("Invalid nonce or digest");
        }
        return "blockpops-upload-v1\n" + nonce + "\n" + intentDigest;
    }

    /** Reads an intent's text, or null when it is not the one spelling of any intent. */
    @Nullable
    public static UploadIntent parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return null;
        }
        String[] parts = text.split("\\|", -1);
        try {
            UploadIntent intent = switch (parts[0]) {
                case "list" -> parts.length == 1 ? list() : null;
                case "publish" -> parts.length != 4 ? null : NEW.equals(parts[1])
                        ? ("0".equals(parts[2]) ? publishNew(parts[3]) : null)
                        : publishChanges(parts[1], rev(parts[2]), parts[3]);
                case "rollback" -> parts.length == 4 ? rollback(parts[1], rev(parts[2]), rev(parts[3])) : null;
                case "withdraw" -> parts.length == 4 ? withdraw(parts[1], rev(parts[2]), rev(parts[3])) : null;
                case "delete" -> parts.length == 3 ? delete(parts[1], rev(parts[2])) : null;
                default -> null;
            };
            // One spelling only: what was read writes back the same text
            return intent != null && intent.text().equals(text) ? intent : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isRev(int rev) {
        return rev >= 1 && rev <= PlayerHead.MAX_REV;
    }

    // A revision written as plain digits without a sign or a leading zero
    private static int rev(String text) {
        if (text.isEmpty() || text.length() > 7 || text.charAt(0) == '0') {
            throw new IllegalArgumentException("Invalid revision");
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid revision");
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
