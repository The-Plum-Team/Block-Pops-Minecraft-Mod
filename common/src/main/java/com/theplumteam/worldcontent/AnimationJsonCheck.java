package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an uploaded `.animation.json` against an allowlisted schema before any GeckoLib
 * version sees it: plain numbers only (no Molang), bounded values, time keys in a plain
 * decimal form that ascend strictly, and no sounds, particles or timeline events.
 *
 * Figures loop `Pose_Stand` and, when it exists, `Pose_Sit` by those exact names, so
 * `Pose_Stand` is required and which of the two exist is recorded.
 *
 * Easings are limited to the names every supported GeckoLib registers, and the argument
 * each one reads is held to a range that GeckoLib evaluates without throwing (a step
 * easing throws on the render thread for fewer than two steps).
 */
public final class AnimationJsonCheck {
    private static final Pattern TIME_KEY = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?");
    /** Easings whose argument GeckoLib ignores. */
    private static final Set<String> PLAIN_EASINGS = Set.of("linear", "none", "catmullrom",
            "easeinsine", "easeoutsine", "easeinoutsine", "easeinquad", "easeoutquad", "easeinoutquad",
            "easeincubic", "easeoutcubic", "easeinoutcubic", "easeinquart", "easeoutquart", "easeinoutquart",
            "easeinquint", "easeoutquint", "easeinoutquint", "easeinexpo", "easeoutexpo", "easeinoutexpo",
            "easeincirc", "easeoutcirc", "easeinoutcirc");
    /** Easings that read their first argument, and the range it may take. */
    private static final Map<String, double[]> ARGUMENT_EASINGS = Map.ofEntries(
            Map.entry("step", new double[] {2, 100}),
            Map.entry("easeinback", new double[] {-10, 10}),
            Map.entry("easeoutback", new double[] {-10, 10}),
            Map.entry("easeinoutback", new double[] {-10, 10}),
            Map.entry("easeinelastic", new double[] {-20, 20}),
            Map.entry("easeoutelastic", new double[] {-20, 20}),
            Map.entry("easeinoutelastic", new double[] {-20, 20}),
            Map.entry("easeinbounce", new double[] {0, 1}),
            Map.entry("easeoutbounce", new double[] {0, 1}),
            Map.entry("easeinoutbounce", new double[] {0, 1}));
    /** The range of easing arguments that no registered easing reads. */
    private static final double MAX_UNUSED_EASING_ARG = 100;
    private static final Set<String> LERP_MODES = Set.of("linear", "catmullrom", "step");
    private static final Set<String> KEYFRAME_KEYS = Set.of("vector", "pre", "post", "easing", "easingArgs", "lerp_mode");
    private static final List<String> REFUSED_KEYS = List.of("sound_effects", "particle_effects", "timeline",
            "anim_time_update", "blend_weight", "start_delay", "loop_delay", "override_previous_animation");
    private static final BigDecimal MAX_LENGTH = BigDecimal.valueOf(300);
    private static final int MAX_EASING_ARGS = 4;

    /** A bone channel and the range its values may take. */
    private enum Channel {
        ROTATION("rotation", -3600, 3600),
        POSITION("position", -64, 64),
        SCALE("scale", 0, 4);

        private final String key;
        private final double min;
        private final double max;

        Channel(String key, double min, double max) {
            this.key = key;
            this.min = min;
            this.max = max;
        }

        static Channel of(String key) {
            for (Channel channel : values()) {
                if (channel.key.equals(key)) {
                    return channel;
                }
            }
            return null;
        }
    }

    /** What the blob table records about a valid animation file. */
    public record Animations(int keyframes, int poses) {
    }

    /** Counts keyframes across the file and the latest time key of one animation. */
    private static final class Tally {
        int keyframes;
        BigDecimal lastKey = BigDecimal.ZERO;

        void add(int count) throws WorldContentException {
            keyframes += count;
            if (keyframes > WorldContentLimits.ANIMATION_MAX_KEYFRAMES) {
                throw new WorldContentException("Animation file has more than "
                        + WorldContentLimits.ANIMATION_MAX_KEYFRAMES + " keyframes");
            }
        }
    }

    private AnimationJsonCheck() {
    }

    /** Parses and checks an animation file. */
    public static BlobInfo validate(byte[] bytes) throws WorldContentException {
        if (bytes.length > WorldContentLimits.ANIMATION_BYTES) {
            throw new WorldContentException("Animation is larger than " + WorldContentLimits.ANIMATION_BYTES / 1024 + " KiB");
        }
        Animations animations = check(JsonFields.parse(bytes, WorldContentLimits.ANIMATION_BYTES));
        return BlobInfo.animation(bytes.length, animations.keyframes(), animations.poses());
    }

    /** Checks a parsed animation file, throwing the first problem found. */
    public static Animations check(JsonObject json) throws WorldContentException {
        JsonFields.keys(json, "animation file", Set.of("format_version", "animations"), Set.of());
        JsonFields.string(json.get("format_version"), "format_version", 16);
        JsonObject animations = JsonFields.object(json.get("animations"), "animations");
        if (animations.size() < 1 || animations.size() > WorldContentLimits.ANIMATION_MAX_ANIMATIONS) {
            throw new WorldContentException("Animation file must hold 1 to "
                    + WorldContentLimits.ANIMATION_MAX_ANIMATIONS + " animations");
        }
        if (!animations.has(BlobInfo.POSE_STAND_NAME)) {
            throw new WorldContentException("Animation file has no " + BlobInfo.POSE_STAND_NAME + " animation");
        }
        Tally tally = new Tally();
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            String name = entry.getKey();
            if (!GeoJsonCheck.NAME.matcher(name).matches()) {
                throw new WorldContentException("Animation name " + WorldContentException.excerpt(name)
                        + " may use only letters, digits, _ . and -");
            }
            checkAnimation(name, JsonFields.object(entry.getValue(), "animation " + name), tally);
        }
        int poses = BlobInfo.POSE_STAND | (animations.has(BlobInfo.POSE_SIT_NAME) ? BlobInfo.POSE_SIT : 0);
        return new Animations(tally.keyframes, poses);
    }

    private static void checkAnimation(String name, JsonObject animation, Tally tally) throws WorldContentException {
        for (String key : REFUSED_KEYS) {
            if (animation.has(key)) {
                throw new WorldContentException("Animation " + name + " uses " + key + ", which is not supported");
            }
        }
        JsonFields.keys(animation, "animation " + name, Set.of("bones"), Set.of("loop", "animation_length"));
        boolean loops = false;
        if (animation.has("loop")) {
            JsonElement loop = animation.get("loop");
            if (loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean()) {
                loops = loop.getAsBoolean();
            } else if (loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isString()
                    && "hold_on_last_frame".equals(loop.getAsString())) {
                loops = true;
            } else {
                throw new WorldContentException("Animation " + name + " loop must be true, false or \"hold_on_last_frame\"");
            }
        }
        BigDecimal length = null;
        if (animation.has("animation_length")) {
            length = JsonFields.decimal(animation.get("animation_length"), "animation_length");
            if (length.signum() <= 0 || length.compareTo(MAX_LENGTH) > 0) {
                throw new WorldContentException("Animation " + name + " length must be above 0 and at most 300 s");
            }
        }

        JsonObject bones = JsonFields.object(animation.get("bones"), "bones");
        if (bones.size() > WorldContentLimits.ANIMATION_MAX_BONES) {
            throw new WorldContentException("Animation " + name + " moves more than "
                    + WorldContentLimits.ANIMATION_MAX_BONES + " bones");
        }
        tally.lastKey = BigDecimal.ZERO;
        for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
            if (!GeoJsonCheck.NAME.matcher(bone.getKey()).matches()) {
                throw new WorldContentException("Animated bone " + WorldContentException.excerpt(bone.getKey())
                        + " may use only letters, digits, _ . and -");
            }
            JsonObject channels = JsonFields.object(bone.getValue(), "bone " + bone.getKey());
            for (Map.Entry<String, JsonElement> entry : channels.entrySet()) {
                Channel channel = Channel.of(entry.getKey());
                if (channel == null) {
                    throw new WorldContentException("Animated bone " + bone.getKey() + " has unknown channel "
                            + WorldContentException.excerpt(entry.getKey()));
                }
                checkChannel(channel, entry.getValue(), tally);
            }
        }
        BigDecimal effective = length != null ? length : tally.lastKey;
        if (loops && effective.signum() <= 0) {
            throw new WorldContentException("Animation " + name + " loops: set the animation length (for example 0.05 s)");
        }
        if (length != null && tally.lastKey.compareTo(length) > 0) {
            throw new WorldContentException("Animation " + name + " has a keyframe after its length");
        }
    }

    private static void checkChannel(Channel channel, JsonElement value, Tally tally) throws WorldContentException {
        if (JsonFields.isNumber(value)) {
            JsonFields.number(value, channel.key, channel.min, channel.max);
            tally.add(1);
            return;
        }
        if (value.isJsonArray()) {
            JsonFields.numbers(value, channel.key, 3, channel.min, channel.max);
            tally.add(1);
            return;
        }
        if (!value.isJsonObject()) {
            throw new WorldContentException(channel.key + " must hold numbers; expressions are not supported");
        }
        JsonObject object = value.getAsJsonObject();
        if (object.keySet().stream().anyMatch(KEYFRAME_KEYS::contains)) {
            // GeckoLib reads an object as one keyframe only when it holds a vector, and some
            // versions read any other key beside it as a time
            if (!object.has("vector") || object.has("pre") || object.has("post")) {
                throw new WorldContentException(channel.key + " keyframe outside a time key needs a vector and no pre or post;"
                        + " put it under a time such as \"0\"");
            }
            checkKeyframe(channel, object);
            tally.add(1);
            return;
        }
        if (object.size() < 1 || object.size() > WorldContentLimits.ANIMATION_MAX_TIME_KEYS) {
            throw new WorldContentException(channel.key + " must have 1 to "
                    + WorldContentLimits.ANIMATION_MAX_TIME_KEYS + " keyframes");
        }
        BigDecimal previous = null;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (!TIME_KEY.matcher(key).matches()) {
                throw new WorldContentException("Keyframe time " + WorldContentException.excerpt(key)
                        + " must be a plain decimal such as 0.5");
            }
            BigDecimal time = new BigDecimal(key);
            if (previous != null && time.compareTo(previous) <= 0) {
                throw new WorldContentException("Keyframe times must ascend: " + key + " follows " + previous.toPlainString());
            }
            previous = time;
            JsonElement keyframe = entry.getValue();
            if (keyframe.isJsonArray()) {
                JsonFields.numbers(keyframe, channel.key, 3, channel.min, channel.max);
            } else if (keyframe.isJsonObject()) {
                checkKeyframe(channel, keyframe.getAsJsonObject());
            } else {
                throw new WorldContentException(channel.key + " keyframe must hold 3 numbers");
            }
        }
        if (previous.compareTo(tally.lastKey) > 0) {
            tally.lastKey = previous;
        }
        tally.add(object.size());
    }

    private static void checkKeyframe(Channel channel, JsonObject keyframe) throws WorldContentException {
        JsonFields.keys(keyframe, "keyframe", Set.of(), KEYFRAME_KEYS);
        if (!keyframe.has("vector") && !keyframe.has("post")) {
            throw new WorldContentException("Keyframe needs a vector or post value");
        }
        for (String key : new String[] {"vector", "pre", "post"}) {
            if (keyframe.has(key)) {
                JsonFields.numbers(keyframe.get(key), channel.key, 3, channel.min, channel.max);
            }
        }
        String easing = "linear";
        if (keyframe.has("easing")) {
            easing = JsonFields.string(keyframe.get("easing"), "easing", 32).toLowerCase(Locale.ROOT);
            if (!PLAIN_EASINGS.contains(easing) && !ARGUMENT_EASINGS.containsKey(easing)) {
                throw new WorldContentException("Easing " + WorldContentException.excerpt(keyframe.get("easing").getAsString())
                        + " is not one GeckoLib knows");
            }
        }
        if (keyframe.has("easingArgs")) {
            JsonArray args = JsonFields.array(keyframe.get("easingArgs"), "easingArgs");
            if (args.size() > MAX_EASING_ARGS) {
                throw new WorldContentException("easingArgs has more than " + MAX_EASING_ARGS + " numbers");
            }
            double[] range = ARGUMENT_EASINGS.get(easing);
            for (int i = 0; i < args.size(); i++) {
                // Only the first argument is read; the rest are kept but bounded
                if (i == 0 && easing.equals("step")) {
                    // GeckoLib's step easing throws for fewer than two steps
                    JsonFields.integer(args.get(i), "Step easing's number of steps", (long) range[0], (long) range[1]);
                } else if (i == 0 && range != null) {
                    JsonFields.number(args.get(i), "easingArgs", range[0], range[1]);
                } else {
                    JsonFields.number(args.get(i), "easingArgs", -MAX_UNUSED_EASING_ARG, MAX_UNUSED_EASING_ARG);
                }
            }
        }
        if (keyframe.has("lerp_mode")) {
            String mode = JsonFields.string(keyframe.get("lerp_mode"), "lerp_mode", 16);
            if (!LERP_MODES.contains(mode)) {
                throw new WorldContentException("lerp_mode must be linear, catmullrom or step");
            }
        }
    }
}
