package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.WorldContentIds;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Makes the ids the creator gives a new collection or figure from the name the operator
 * typed, so nobody has to invent a slug: "Dragon Ball Z" becomes {@code dragon_ball_z} and
 * "Pokémon Rojo" becomes {@code pokemon_rojo}.
 *
 * <p>A slug is cut to {@link #MAX_BASE} characters, which leaves room for a suffix from
 * {@code _2} to {@code _99} within the {@value #MAX_LENGTH} characters an id may have; a
 * longer suffix, which only a collection with more than 99 figures of one name needs,
 * shortens the slug before it. The client cannot see the ids purged collections used, so a
 * create the server refuses with ID_TAKEN tries again with {@link #next}.
 */
public final class Slugs {
    /** The longest a collection slug or a figure id may be. */
    public static final int MAX_LENGTH = 24;
    /** The longest slug made from a name, so that a {@code _99} suffix still fits in {@value #MAX_LENGTH} characters. */
    public static final int MAX_BASE = 21;
    /** The highest suffix {@link #unique} and {@link #next} add: more than a collection has figures. */
    public static final int MAX_SUFFIX = 999;
    public static final String COLLECTION_FALLBACK = "collection";
    public static final String FIGURE_FALLBACK = "figure";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SUFFIX = Pattern.compile("(.+)_([0-9]{1,3})");

    private Slugs() {
    }

    /** The slug of a collection name, or {@value #COLLECTION_FALLBACK} when nothing of it is usable. */
    public static String collection(String name) {
        return slugify(name, COLLECTION_FALLBACK);
    }

    /** The id of a figure name, or {@value #FIGURE_FALLBACK} when nothing of it is usable. */
    public static String figure(String name) {
        return slugify(name, FIGURE_FALLBACK);
    }

    /**
     * Turns a name into a slug: accents are dropped, letters are lowercased, every run of
     * other characters becomes one underscore, and the result is trimmed of underscores and
     * cut to {@link #MAX_BASE} characters.
     */
    public static String slugify(String name, String fallback) {
        String text = name == null ? "" : name;
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String slug = trim(NOT_SLUG.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll("_"));
        if (slug.length() > MAX_BASE) {
            slug = trim(slug.substring(0, MAX_BASE));
        }
        return WorldContentIds.isSlug(slug) ? slug : fallback;
    }

    /**
     * The slug itself when it is free, otherwise the first of {@code base_2} to
     * {@code base_999} that is. When all of them are taken, the last one is returned and the
     * server's refusal says so.
     */
    public static String unique(String base, Predicate<String> taken) {
        String root = fit(base);
        if (!taken.test(root)) {
            return root;
        }
        String candidate = root;
        for (int suffix = 2; suffix <= MAX_SUFFIX; suffix++) {
            candidate = withSuffix(root, suffix);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return candidate;
    }

    /**
     * The slug to try after the server refused this one as taken: {@code name} becomes
     * {@code name_2}, and {@code name_2} becomes {@code name_3}.
     */
    public static String next(String slug) {
        var matcher = SUFFIX.matcher(slug);
        if (matcher.matches()) {
            int suffix = Integer.parseInt(matcher.group(2));
            if (suffix >= 2 && suffix < MAX_SUFFIX) {
                return withSuffix(matcher.group(1), suffix + 1);
            }
        }
        return withSuffix(slug, 2);
    }

    /** Whether a collection with the slug is among the ones this client was sent. */
    public static boolean isCollectionTaken(String slug) {
        return WorldContentIds.isSlug(slug) && ClientWorldCollections.record(WorldContentIds.collectionId(slug)) != null;
    }

    // The root is shortened so the whole id stays a slug, as a slug typed in the advanced field may be 24 long
    private static String withSuffix(String root, int suffix) {
        String tail = "_" + suffix;
        int room = MAX_LENGTH - tail.length();
        String head = root.length() > room ? trim(root.substring(0, room)) : root;
        return (head.isEmpty() ? "x" : head) + tail;
    }

    // A slug typed in the advanced field may already be 24 characters long
    private static String fit(String slug) {
        return slug.length() > MAX_LENGTH ? trim(slug.substring(0, MAX_LENGTH)) : slug;
    }

    private static String trim(String slug) {
        int start = 0;
        int end = slug.length();
        while (start < end && slug.charAt(start) == '_') {
            start++;
        }
        while (end > start && slug.charAt(end - 1) == '_') {
            end--;
        }
        return slug.substring(start, end);
    }
}
