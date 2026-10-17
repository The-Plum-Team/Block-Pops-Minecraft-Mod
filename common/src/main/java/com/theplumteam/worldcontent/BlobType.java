package com.theplumteam.worldcontent;

import org.jetbrains.annotations.Nullable;

/**
 * The kinds of file a World collection is made of. Each has a fixed wire id, the name
 * the store writes and the largest size any file of its kind may have.
 */
public enum BlobType {
    PNG(0, "png", WorldContentLimits.PNG_BYTES),
    GEO(1, "geo", WorldContentLimits.GEO_BYTES),
    ANIMATION(2, "animation", WorldContentLimits.ANIMATION_BYTES);

    private final int id;
    private final String storeName;
    private final int maxBytes;

    BlobType(int id, String storeName, int maxBytes) {
        this.id = id;
        this.storeName = storeName;
        this.maxBytes = maxBytes;
    }

    /** The byte that names this type on the wire. */
    public int id() {
        return id;
    }

    /** The name this type has in the store's blob table. */
    public String storeName() {
        return storeName;
    }

    public int maxBytes() {
        return maxBytes;
    }

    @Nullable
    public static BlobType byId(int id) {
        for (BlobType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }

    @Nullable
    public static BlobType byStoreName(String name) {
        for (BlobType type : values()) {
            if (type.storeName.equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Validates a complete file of this type and describes it. A PNG must already be in
     * canonical form; nothing else is ever stored or loaded.
     */
    public BlobInfo validate(byte[] bytes) throws WorldContentException {
        return switch (this) {
            case PNG -> PngValidator.validateCanonical(bytes);
            case GEO -> GeoJsonCheck.validate(bytes);
            case ANIMATION -> AnimationJsonCheck.validate(bytes);
        };
    }
}
