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
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Parses remote JSON documents with none of Gson's leniency.
 *
 * The bytes must be valid UTF-8 holding exactly one object: no duplicate key at any depth,
 * no NaN or Infinity, no comments or unquoted strings, and nothing after the object.
 */
public final class StrictJson {
    private static final int MAX_DEPTH = 64;

    private StrictJson() {
    }

    public static JsonObject parseObject(byte[] utf8, long maxBytes) throws RemoteException {
        if (utf8.length > maxBytes) {
            throw new RemoteException("JSON document is larger than " + maxBytes + " bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
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
            JsonObject object = read(reader, 0).getAsJsonObject();
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

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
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
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                // Strict mode has already refused NaN, Infinity and malformed numbers
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
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
