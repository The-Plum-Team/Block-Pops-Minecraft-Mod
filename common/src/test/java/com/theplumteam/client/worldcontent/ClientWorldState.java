package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * Puts the client in the state a sync of the World collections leaves it in. A real sync
 * ({@link ClientWorldCollections#onSyncPart}) also registers the collections and loads their
 * files, which needs a running game, so the tests set the records, the file table and the
 * revision the creator reads, and nothing else.
 */
final class ClientWorldState {
    private ClientWorldState() {
    }

    /** A sync of these records, with the file table their stored figures need. */
    static void sync(WorldCollectionRecord... records) {
        sync(Fixtures.blobsOf(records), Set.of(), records);
    }

    /**
     * A sync of these records and this file table.
     *
     * @param missing the files the server says it lost
     */
    static void sync(Map<String, BlobInfo> blobs, Set<String> missing, WorldCollectionRecord... records) {
        Map<String, WorldCollectionRecord> held = get("RECORDS");
        held.clear();
        for (WorldCollectionRecord record : records) {
            held.put(record.id(), record);
        }
        set("blobs", Map.copyOf(blobs));
        set("missingOnServer", Set.copyOf(missing));
        set("synced", true);
        set("revision", ClientWorldCollections.revision() + 1);
    }

    /** A client that has not been sent any World collection, as after leaving a server. */
    static void clear() {
        Map<String, WorldCollectionRecord> held = get("RECORDS");
        held.clear();
        set("blobs", Map.of());
        set("missingOnServer", Set.of());
        set("synced", false);
        set("revision", ClientWorldCollections.revision() + 1);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(String name) {
        try {
            return (T) field(name).get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(String name, Object value) {
        try {
            field(name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = ClientWorldCollections.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("ClientWorldCollections has no field " + name, e);
        }
    }
}
