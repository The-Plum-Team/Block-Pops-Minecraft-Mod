package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.theplumteam.remote.RemoteException;
import com.theplumteam.worldcontent.Sha256;

import java.math.BigDecimal;
import java.util.Arrays;

/** Reads the fields of a player document, each with its bound. */
final class Payloads {
    /** The latest time a document may carry: the last second of the year 9999. */
    static final long MAX_TIME = 253_402_300_799L;

    private Payloads() {
    }

    /** Requires exactly these keys, each once: no other key and none missing. */
    static void exactKeys(JsonObject json, String what, String... keys) throws RemoteException {
        if (json.size() != keys.length) {
            throw new RemoteException(what + " must have exactly the keys " + String.join(", ", keys));
        }
        for (String key : keys) {
            if (!json.has(key)) {
                throw new RemoteException(what + " is missing " + key);
            }
        }
    }

    static JsonObject object(JsonElement element, String what) throws RemoteException {
        if (element == null || !element.isJsonObject()) {
            throw new RemoteException(what + " must be an object");
        }
        return element.getAsJsonObject();
    }

    static JsonArray array(JsonObject json, String key, int max) throws RemoteException {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new RemoteException(key + " must be an array");
        }
        if (element.getAsJsonArray().size() > max) {
            throw new RemoteException(key + " has more than " + max + " entries");
        }
        return element.getAsJsonArray();
    }

    static String string(JsonObject json, String key, int maxLength) throws RemoteException {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new RemoteException(key + " must be a string");
        }
        String value = element.getAsString();
        if (value.length() > maxLength) {
            throw new RemoteException(key + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    /** A whole number between min and max, written without a fraction. */
    static long integer(JsonObject json, String key, long min, long max) throws RemoteException {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new RemoteException(key + " must be a number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        BigDecimal value;
        try {
            value = primitive.getAsBigDecimal();
        } catch (NumberFormatException e) {
            throw new RemoteException(key + " is not a plain number");
        }
        if (value.scale() != 0 || value.precision() > 19) {
            throw new RemoteException(key + " must be a whole number");
        }
        long whole;
        try {
            whole = value.longValueExact();
        } catch (ArithmeticException e) {
            throw new RemoteException(key + " must be a whole number");
        }
        if (whole < min || whole > max) {
            throw new RemoteException(key + " must be between " + min + " and " + max);
        }
        return whole;
    }

    static String sha(JsonObject json, String key) throws RemoteException {
        String sha = string(json, key, 64);
        if (!Sha256.isValidHex(sha)) {
            throw new RemoteException(key + " is not a SHA-256");
        }
        return sha;
    }

    /** One of the listed words. */
    static String word(JsonObject json, String key, String... words) throws RemoteException {
        String value = string(json, key, 16);
        for (String word : words) {
            if (word.equals(value)) {
                return word;
            }
        }
        throw new RemoteException(key + " must be one of " + String.join(", ", words));
    }

    static String playerId(JsonObject json, String key) throws RemoteException {
        String id = string(json, key, 32);
        if (!PlayerIds.isPlayerId(id)) {
            throw new RemoteException(key + " is not a player collection id");
        }
        return id;
    }

    /**
     * Requires the bytes read to be exactly the ones the document writes back, so that a
     * document has one spelling: no other key order, spacing, escape or way to write a number.
     */
    static <T> T canonical(T document, byte[] written, byte[] read, String what) throws RemoteException {
        if (!Arrays.equals(written, read)) {
            throw new RemoteException(what + " is not written the way it writes itself");
        }
        return document;
    }

    /** The document's kind and schema, which must be the ones expected. */
    static void header(JsonObject json, String kind) throws RemoteException {
        if (!kind.equals(string(json, "kind", 64))) {
            throw new RemoteException("Document is not a " + kind);
        }
        integer(json, "schema", 1, 1);
    }
}
