package com.theplumteam.remote.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * The one JSON writer of the content core: a parsed value as minified text, keys in the order
 * the object holds them, nulls written, and no escaping beyond what JSON requires.
 *
 * <p>It gives the bytes {@code new GsonBuilder().disableHtmlEscaping().serializeNulls().create()}
 * gave for the same value, so every World record keeps the bytes it has always had. It goes
 * through {@link JsonWriter} alone, because {@code Gson} reaches for reflection, which a host of
 * the core other than the JVM does not have.
 */
public final class JsonOut {
    private JsonOut() {
    }

    /** The value as JSON text. */
    public static String write(JsonElement element) {
        StringWriter out = new StringWriter();
        try {
            JsonWriter writer = new JsonWriter(out);
            writer.setHtmlSafe(false);
            writer.setSerializeNulls(true);
            write(writer, element);
            writer.flush();
        } catch (IOException e) {
            // A StringWriter does not fail
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    private static void write(JsonWriter writer, JsonElement element) throws IOException {
        if (element == null || element.isJsonNull()) {
            writer.nullValue();
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                writer.value(primitive.getAsNumber());
            } else if (primitive.isBoolean()) {
                writer.value(primitive.getAsBoolean());
            } else {
                writer.value(primitive.getAsString());
            }
        } else if (element.isJsonArray()) {
            writer.beginArray();
            for (JsonElement item : (JsonArray) element) {
                write(writer, item);
            }
            writer.endArray();
        } else {
            writer.beginObject();
            for (Map.Entry<String, JsonElement> entry : ((JsonObject) element).entrySet()) {
                writer.name(entry.getKey());
                write(writer, entry.getValue());
            }
            writer.endObject();
        }
    }
}
