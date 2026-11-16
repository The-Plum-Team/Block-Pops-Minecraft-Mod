package com.theplumteam.worldcontent;

import com.google.gson.JsonElement;
import com.theplumteam.remote.player.ContentError;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Which links may be a World collection's author link. It is the game's rule, not the content
 * core's: it reads a link as the JDK does, and only a World record carries one, so the record
 * takes it as its {@link WorldCollectionRecord.LinkRule} wherever the game parses one.
 */
public final class AuthorLinks {
    /** The rule every World record is parsed with. */
    public static final WorldCollectionRecord.LinkRule RULE = AuthorLinks::isValid;

    private AuthorLinks() {
    }

    /** Reads an author link and refuses anything {@link #isValid} does not accept. */
    public static String read(JsonElement element) throws WorldContentException {
        String url = JsonFields.string(element, "author_url", WorldContentLimits.MAX_AUTHOR_URL_LENGTH);
        if (!isValid(url)) {
            throw new WorldContentException(ContentError.RECORD_AUTHOR_URL, WorldCollectionRecord.AUTHOR_URL_RULE);
        }
        return url;
    }

    /**
     * Whether a link may be an author link: at most {@link WorldContentLimits#MAX_AUTHOR_URL_LENGTH}
     * printable ASCII characters without spaces, an http or https scheme, a host and no user
     * info. ASCII alone keeps look-alike letters out of a link players are asked to open.
     */
    public static boolean isValid(String url) {
        if (url == null || url.isEmpty() || url.length() > WorldContentLimits.MAX_AUTHOR_URL_LENGTH) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c > '~') {
                return false;
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        return (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null;
    }
}
