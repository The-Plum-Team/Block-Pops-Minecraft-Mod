package com.theplumteam.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/**
 * Parses remote JSON documents with none of Gson's leniency.
 *
 * The bytes must be valid UTF-8 holding exactly one object: no duplicate key at any depth,
 * no NaN or Infinity, no comments or unquoted strings, and nothing after the object.
 *
 * A document players publish is held to one rule more ({@link #parsePlayerObject}): no number in
 * it is longer than {@link #PLAYER_NUMBER_LENGTH} characters or has an exponent beyond
 * {@link #PLAYER_NUMBER_EXPONENT} either way. Reading a number costs more than its length, so an
 * unbounded one would let a small file hold up everyone who checks it.
 */
public final class StrictJson {
    /** The most characters a number has in a document players publish. */
    public static final int PLAYER_NUMBER_LENGTH = 24;
    /** The largest exponent, either way, such a number has. */
    public static final int PLAYER_NUMBER_EXPONENT = 6;
    private static final int MAX_DEPTH = 64;

    private StrictJson() {
    }

    public static JsonObject parseObject(byte[] utf8, long maxBytes) throws RemoteException {
        return parse(utf8, maxBytes, false);
    }

    /** As {@link #parseObject}, for a document players publish: its numbers are bounded too. */
    public static JsonObject parsePlayerObject(byte[] utf8, long maxBytes) throws RemoteException {
        return parse(utf8, maxBytes, true);
    }

    /** Whether a number, as written, is one a document players publish may hold. */
    public static boolean isPlayerNumber(String token) {
        if (token.isEmpty() || token.length() > PLAYER_NUMBER_LENGTH) {
            return false;
        }
        int e = Math.max(token.indexOf('e'), token.indexOf('E'));
        if (e < 0) {
            return true;
        }
        int at = e + 1;
        if (at < token.length() && (token.charAt(at) == '+' || token.charAt(at) == '-')) {
            at++;
        }
        int exponent = 0;
        for (; at < token.length(); at++) {
            exponent = exponent * 10 + (token.charAt(at) - '0');
            if (exponent > PLAYER_NUMBER_EXPONENT) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject parse(byte[] utf8, long maxBytes, boolean playerNumbers) throws RemoteException {
        if (utf8.length > maxBytes) {
            throw new RemoteException("JSON document is larger than " + maxBytes + " bytes");
        }
        String text = CoreText.decodeUtf8(utf8);
        if (text == null) {
            throw new RemoteException("JSON document is not valid UTF-8");
        }
        // JsonReader would skip a byte order mark silently; JSON text never starts with one
        if (text.startsWith("﻿")) {
            throw new RemoteException("JSON document starts with a byte order mark");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new RemoteException("JSON document is not an object");
            }
            JsonObject object = read(reader, 0, playerNumbers).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new RemoteException("JSON document has data after its object");
            }
            return object;
        } catch (RemoteException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Gson reports malformed input as IOException or as its own runtime exceptions
            throw new RemoteException("Malformed JSON document");
        }
    }

    /** Requires the object to have exactly these keys. */
    static void requireKeys(JsonObject object, String what, String... keys) throws RemoteException {
        if (object.size() != keys.length) {
            throw new RemoteException(what + " must have exactly the keys " + String.join(", ", keys));
        }
        for (String key : keys) {
            if (!object.has(key)) {
                throw new RemoteException(what + " is missing " + key);
            }
        }
    }

    static JsonObject object(JsonElement element, String what) throws RemoteException {
        if (!element.isJsonObject()) {
            throw new RemoteException(what + " must be an object");
        }
        return element.getAsJsonObject();
    }

    static JsonArray array(JsonObject object, String key) throws RemoteException {
        JsonElement element = object.get(key);
        if (!element.isJsonArray()) {
            throw new RemoteException(key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    static String string(JsonObject object, String key) throws RemoteException {
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new RemoteException(key + " must be a string");
        }
        return element.getAsString();
    }

    private static JsonElement read(JsonReader reader, int depth, boolean playerNumbers) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new RemoteException("JSON document is nested too deeply");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) {
                        throw new RemoteException("Duplicate JSON key " + RemoteException.excerpt(key));
                    }
                    object.add(key, read(reader, depth + 1, playerNumbers));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1, playerNumbers));
                }
                reader.endArray();
                return array;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER: {
                // Strict mode has already refused NaN, Infinity and malformed numbers
                String token = reader.nextString();
                if (playerNumbers && !isPlayerNumber(token)) {
                    throw new RemoteException("JSON number is longer than " + PLAYER_NUMBER_LENGTH
                            + " characters or has an exponent beyond " + PLAYER_NUMBER_EXPONENT);
                }
                // A published document's numbers are bounded, so building one cannot fail and is
                // left until a check needs it; any other number is built now, so that one the
                // arbitrary-precision reader refuses still fails the parse
                return new JsonPrimitive(new JsonNumber(token, !playerNumbers));
            }
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new RemoteException("Unexpected JSON token");
        }
    }
}
