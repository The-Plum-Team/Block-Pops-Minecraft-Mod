package com.theplumteam.remote.player;

import org.jetbrains.annotations.Nullable;

/**
 * Which text a player may publish as a name, and the reduced form names are compared in.
 *
 * <p>The characters are one frozen list, so every host agrees on a name whatever Unicode tables
 * it has: the ASCII letters and digits, the space and {@code ' - . , ! ? & ( ) :}, and the
 * letters of Latin-1 Supplement, Latin Extended-A and Latin Extended-B (U+00C0 to U+024F
 * without the two signs among them). Everything else is refused, which leaves out the section
 * sign Minecraft reads as formatting, the no-break space and the soft hyphen, every other
 * script, combining marks, and every control and format character. Spaces never lead, trail or
 * repeat.
 *
 * <p>{@link #skeleton} reduces a name to what it reads as, so that look-alikes compare equal:
 * case and accents go, digits that stand in for letters become them, and separators drop out.
 * It decides nothing by itself: lists of names are matched against it elsewhere.
 */
public final class PlayerText {
    /** The most characters a name has. */
    public static final int MAX_NAME_LENGTH = 48;
    private static final String PUNCTUATION = "'-.,!?&():";
    private static final int FOLD_FIRST = 0x00C0;
    private static final int FOLD_LAST = 0x024F;
    // What each letter of U+00C0 to U+024F reads as, in order; the two signs among them are empty
    private static final String[] FOLD = (""
            + "a,a,a,a,a,a,ae,c,e,e,e,e,i,i,i,i,d,n,o,o,o,o,o,,o,u,u,u,u,y,"
            + "th,ss,a,a,a,a,a,a,ae,c,e,e,e,e,i,i,"
            + "i,i,d,n,o,o,o,o,o,,o,u,u,u,u,y,th,y,a,a,a,a,a,a,c,c,c,c,c,c,c,c,d,d,d,d,e,e,e,e,e,e,e,e,e,e,g,g,"
            + "g,g,g,g,g,g,h,h,h,h,i,i,i,i,i,i,i,i,i,i,ij,ij,j,j,k,k,k,l,l,l,l,l,l,l,l,l,l,n,n,n,n,n,n,n,n,n,o,"
            + "o,o,o,o,o,oe,oe,r,r,r,r,r,r,s,s,s,s,s,s,s,s,t,t,t,t,t,t,u,u,u,u,u,u,u,u,u,u,u,u,w,w,y,y,y,z,z,z,"
            + "z,z,z,s,b,b,b,b,b,b,o,c,c,d,d,d,d,d,e,e,e,f,f,g,y,hv,i,i,k,k,l,l,m,n,n,o,o,o,oi,oi,p,p,r,s,s,s,"
            + "l,t,t,t,t,u,u,u,v,y,y,z,z,z,z,z,z,z,s,s,t,p,i,ii,t,i,dz,dz,dz,lj,lj,lj,nj,nj,nj,a,a,i,i,o,o,u,u,"
            + "u,u,u,u,u,u,u,u,e,a,a,a,a,ae,ae,g,g,g,g,k,k,o,o,o,o,z,z,j,dz,dz,dz,g,g,hv,p,n,n,a,a,ae,ae,o,o,a,"
            + "a,a,a,e,e,e,e,i,i,i,i,o,o,o,o,r,r,r,r,u,u,u,u,s,s,t,t,z,z,h,h,n,d,ou,ou,z,z,a,a,e,e,o,o,o,o,o,o,"
            + "o,o,y,y,l,n,t,j,db,qp,a,c,c,l,t,s,z,,,b,u,v,e,e,j,j,q,q,r,r,y,y").split(",", -1);

    private PlayerText() {
    }

    /** Whether the character may appear in a name. */
    public static boolean isAllowed(char c) {
        if (c < 0x80) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == ' '
                    || PUNCTUATION.indexOf(c) >= 0;
        }
        return c >= FOLD_FIRST && c <= FOLD_LAST && c != 0x00D7 && c != 0x00F7;
    }

    /**
     * What is wrong with a text of min to max characters, or null when it may be published:
     * {@link ContentError#TEXT_EMPTY}, {@link ContentError#TEXT_LENGTH},
     * {@link ContentError#TEXT_CHARACTERS} or {@link ContentError#TEXT_SPACES}.
     */
    @Nullable
    public static ContentError problem(String text, int min, int max) {
        if (text == null || text.length() < min) {
            return ContentError.TEXT_EMPTY;
        }
        if (text.length() > max) {
            return ContentError.TEXT_LENGTH;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isAllowed(c)) {
                return ContentError.TEXT_CHARACTERS;
            }
            if (c == ' ' && (i == 0 || i == text.length() - 1 || text.charAt(i - 1) == ' ')) {
                return ContentError.TEXT_SPACES;
            }
        }
        return null;
    }

    /** What is wrong with a collection's, figure's or variant's name, or null. */
    @Nullable
    public static ContentError nameProblem(String name) {
        return problem(name, 1, MAX_NAME_LENGTH);
    }

    /**
     * The text reduced to what it reads as: lowercase ASCII letters and digits only. Accents
     * and case go; {@code 0 1 3 4 5 7 @ $} become the letters they stand in for; {@code l}, the
     * dotless i and the click letter join {@code i}; {@code rn} reads as {@code m} and
     * {@code vv} as {@code w}; everything else, separators included, drops out.
     */
    public static String skeleton(String text) {
        StringBuilder letters = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c >= FOLD_FIRST && c <= FOLD_LAST) {
                String folded = FOLD[c - FOLD_FIRST];
                for (int j = 0; j < folded.length(); j++) {
                    letters.append(reading(folded.charAt(j)));
                }
            } else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '@' || c == '$') {
                letters.append(reading(c));
            }
        }
        StringBuilder out = new StringBuilder(letters.length());
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            char next = i + 1 < letters.length() ? letters.charAt(i + 1) : 0;
            if (c == 'r' && next == 'n') {
                out.append('m');
                i++;
            } else if (c == 'v' && next == 'v') {
                out.append('w');
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // The letter a lowercase ASCII letter, a digit or a sign reads as
    private static char reading(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', 'l' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> c;
        };
    }
}
