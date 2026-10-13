package com.theplumteam.remote;

import com.google.gson.JsonObject;

/** What a collection code points at: `codes/<code>.json` holding `{"id": ..., "name": ...}`. */
public record CodeEntry(String id, String name) {
    private static final int MAX_NAME_LENGTH = 64;

    public CodeEntry {
        if (!RemotePaths.isValidCollectionId(id)) {
            throw new IllegalArgumentException("Invalid collection id");
        }
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid collection name");
        }
    }

    public static CodeEntry parse(byte[] utf8) throws RemoteException {
        JsonObject root = StrictJson.parseObject(utf8, RemoteLimits.CODE_BYTES);
        StrictJson.requireKeys(root, "Code", "id", "name");
        String id = StrictJson.string(root, "id");
        String name = StrictJson.string(root, "name");
        if (!RemotePaths.isValidCollectionId(id)) {
            throw new RemoteException("Code points at an invalid collection id " + RemoteException.excerpt(id));
        }
        if (!isValidName(name)) {
            throw new RemoteException("Code has an invalid collection name");
        }
        return new CodeEntry(id, name);
    }

    /** 1 to 64 characters, not blank, with no control, formatting or separator characters. */
    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        return name.codePoints().noneMatch(c -> {
            int type = Character.getType(c);
            return Character.isISOControl(c) || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
        });
    }
}
