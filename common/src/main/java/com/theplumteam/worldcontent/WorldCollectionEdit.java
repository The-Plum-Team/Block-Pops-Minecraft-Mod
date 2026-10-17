package com.theplumteam.worldcontent;

import org.jetbrains.annotations.Nullable;

/**
 * One change an operator asks for in the editor. Every field is as received: the server
 * checks each of them, and the record they produce, before anything changes.
 *
 * Purging a collection is not an edit; only the command does it.
 */
public sealed interface WorldCollectionEdit {
    /** The edits, with the byte that names each one on the wire. */
    enum Action {
        CREATE(0),
        SET_COLLECTION(1),
        SET_ARCHIVED(2),
        PUT_FIGURE(3),
        SET_FIGURE_ENABLED(4);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        @Nullable
        public static Action byId(int id) {
            for (Action action : values()) {
                if (action.id == id) {
                    return action;
                }
            }
            return null;
        }
    }

    int BOX_NONE = 0;
    int BOX_TEXTURE = 1;
    int BOX_COLOR = 2;
    int KIND_SKIN = 0;
    int KIND_MODEL = 1;

    Action action();

    /** Creates an empty collection with the slug, which is fixed from then on. */
    record Create(String slug, String name) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.CREATE;
        }
    }

    /**
     * Replaces a collection's names, colour, box and logo.
     *
     * @param boxMode {@link #BOX_NONE}, {@link #BOX_TEXTURE} or {@link #BOX_COLOR}
     * @param box     the sha of the box texture, the name of the box colour, or empty
     * @param logo    the sha of the logo, or empty for none
     */
    record SetCollection(String id, String name, String author, int red, int green, int blue, int boxMode,
                         String box, String logo) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.SET_COLLECTION;
        }
    }

    record SetArchived(String id, boolean archived) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.SET_ARCHIVED;
        }
    }

    /**
     * Adds a figure, or replaces the one with that id; a replaced figure keeps whether it
     * is retired.
     *
     * @param kind       {@link #KIND_SKIN} or {@link #KIND_MODEL}
     * @param model      the sha of the model, or empty for a skin
     * @param animation  the sha of the poses, or empty for the shared poses
     * @param scaleIndex an index into {@link WorldCollectionRecord#SCALES}
     */
    record PutFigure(String id, String figure, String name, int kind, String texture, String model,
                     String animation, int scaleIndex) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.PUT_FIGURE;
        }
    }

    /** Retires a figure, or brings a retired one back. */
    record SetFigureEnabled(String id, String figure, boolean enabled) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.SET_FIGURE_ENABLED;
        }
    }
}
