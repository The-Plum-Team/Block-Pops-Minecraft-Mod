package com.theplumteam.worldcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.remote.StrictJson;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Typed, bounded reads of World collection JSON; every failure names the field. The
 * server reads its own store file and imported packs with the same helpers.
 */
public final class JsonFields {
    private JsonFields() {
    }

    /** Parses one strict JSON object of at most maxBytes. */
    public static JsonObject parse(byte[] utf8, int maxBytes) throws WorldContentException {
        try {
            return StrictJson.parseObject(utf8, maxBytes);
        } catch (RemoteException e) {
            throw new WorldContentException(e.getMessage());
        }
    }

    /** Requires every required key, and refuses any key that is neither required nor optional. */
    public static void keys(JsonObject object, String what, Set<String> required, Set<String> optional)
            throws WorldContentException {
        for (String key : object.keySet()) {
            if (!required.contains(key) && !optional.contains(key)) {
                throw new WorldContentException(what + " has unknown key " + WorldContentException.excerpt(key));
            }
        }
        for (String key : required) {
            if (!object.has(key)) {
                throw new WorldContentException(what + " is missing " + key);
            }
        }
    }

    public static JsonObject object(JsonElement element, String what) throws WorldContentException {
        if (element == null || !element.isJsonObject()) {
            throw new WorldContentException(what + " must be an object");
        }
        return element.getAsJsonObject();
    }

    public static JsonArray array(JsonElement element, String what) throws WorldContentException {
        if (element == null || !element.isJsonArray()) {
            throw new WorldContentException(what + " must be an array");
        }
        return element.getAsJsonArray();
    }

    public static String string(JsonElement element, String what, int maxLength) throws WorldContentException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new WorldContentException(what + " must be a string");
        }
        String value = element.getAsString();
        if (value.length() > maxLength) {
            throw new WorldContentException(what + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    public static boolean bool(JsonElement element, String what) throws WorldContentException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new WorldContentException(what + " must be true or false");
        }
        return element.getAsBoolean();
    }

    public static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    /** A JSON number exactly as written; StrictJson has already refused NaN and Infinity. */
    public static BigDecimal decimal(JsonElement element, String what) throws WorldContentException {
        if (!isNumber(element)) {
            throw new WorldContentException(what + " must be a number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException e) {
            throw new WorldContentException(what + " is not a plain number");
        }
    }

    /** A finite number within [min, max]. */
    public static double number(JsonElement element, String what, double min, double max) throws WorldContentException {
        double value = decimal(element, what).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new WorldContentException(what + " must be between " + plain(min) + " and " + plain(max));
        }
        return value;
    }

    /** A whole number within [min, max]. */
    public static long integer(JsonElement element, String what, long min, long max) throws WorldContentException {
        BigDecimal value = decimal(element, what);
        if (value.signum() != 0 && value.stripTrailingZeros().scale() > 0) {
            throw new WorldContentException(what + " must be a whole number");
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new WorldContentException(what + " must be between " + min + " and " + max);
        }
        return value.longValueExact();
    }

    /** An array of exactly length numbers, each within [min, max]. */
    public static double[] numbers(JsonElement element, String what, int length, double min, double max)
            throws WorldContentException {
        JsonArray array = array(element, what);
        if (array.size() != length) {
            throw new WorldContentException(what + " must have " + length + " numbers");
        }
        double[] values = new double[length];
        for (int i = 0; i < length; i++) {
            values[i] = number(array.get(i), what, min, max);
        }
        return values;
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
