package com.theplumteam.worldcontent;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One change an operator asks for in the editor. Every field is as received: the server
 * checks each of them, and the record they produce, before anything changes.
 *
 * <p>A {@link Batch} carries several edits that the server applies in order and saves
 * together, or not at all. {@link Duplicate}, {@link Purge} and {@link ImportPack} each
 * stand alone and are never part of a batch.
 */
public sealed interface WorldCollectionEdit {
    /** The edits, with the byte that names each one on the wire. */
    enum Action {
        CREATE(0),
        SET_COLLECTION(1),
        SET_ARCHIVED(2),
        PUT_FIGURE(3),
        SET_FIGURE_ENABLED(4),
        CREATE_DRAFT(5),
        PUBLISH(6),
        REORDER_FIGURES(7),
        REMOVE_FIGURE(8),
        BATCH(9),
        SET_COLLECTION_EXTRAS(10),
        SET_FIGURE_EXTRAS(11),
        DUPLICATE(12),
        PURGE(13),
        IMPORT_PACK(14);

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
    /** A figure's box face as {@link SetFigureExtras} sends it: the default for its kind. */
    int FACE_DEFAULT = 0;
    /** The box shows the figure's face; for a model figure only together with a region. */
    int FACE_SHOW = 1;
    int FACE_HIDE = 2;
    /** The box shows a model figure's face from the given region of its texture. */
    int FACE_SHOW_UV = 3;
    /** The most edits one {@link Batch} may carry. */
    int MAX_BATCH = 48;

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

    /** Creates an empty draft with the slug: stored and shown to operators, but in no machine yet. */
    record CreateDraft(String slug, String name) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.CREATE_DRAFT;
        }
    }

    /** Publishes a draft, which puts it in the machines. A collection is never a draft again. */
    record Publish(String id) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.PUBLISH;
        }
    }

    /**
     * Puts a collection's figures in a new order.
     *
     * @param order every figure id of the collection exactly once, retired ones included
     */
    record ReorderFigures(String id, List<String> order) implements WorldCollectionEdit {
        public ReorderFigures {
            order = List.copyOf(order);
        }

        @Override
        public Action action() {
            return Action.REORDER_FIGURES;
        }
    }

    /**
     * Deletes a figure of a draft. A published collection's figures may be in players'
     * boxes, so they are only ever retired.
     */
    record RemoveFigure(String id, String figure) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.REMOVE_FIGURE;
        }
    }

    /**
     * Several edits applied in order and saved together: when any of them is refused,
     * none of them changes anything. Holds 1 to {@link #MAX_BATCH} edits, none of which
     * {@link #standsAlone}.
     */
    record Batch(List<WorldCollectionEdit> edits) implements WorldCollectionEdit {
        public Batch {
            edits = List.copyOf(edits);
        }

        @Override
        public Action action() {
            return Action.BATCH;
        }
    }

    /**
     * Replaces a collection's author link, logo placement, plush flag and rarity weights.
     *
     * @param authorUrl     the link, or empty (or null) for none
     * @param logoLayout    the logo placement, or null for the automatic fit; needs a logo
     * @param rarityWeights the weights of common, rare, epic and legendary figures, or null
     *                      for {@link WorldCollectionRecord.RarityWeights#DEFAULT}
     */
    record SetCollectionExtras(String id, String authorUrl, @Nullable WorldCollectionRecord.LogoLayout logoLayout,
                               boolean plush, @Nullable WorldCollectionRecord.RarityWeights rarityWeights)
            implements WorldCollectionEdit {
        public SetCollectionExtras {
            authorUrl = authorUrl != null ? authorUrl : "";
        }

        @Override
        public Action action() {
            return Action.SET_COLLECTION_EXTRAS;
        }
    }

    /**
     * Replaces a figure's rarity, variants and box face, keeping its name, files, size and
     * whether it is retired. Sent in the same batch as a {@link PutFigure} that changes the
     * figure's texture or model, the record is checked only with both applied, so variants
     * or a face region that no longer fit can be replaced in the same step.
     *
     * @param variants at most {@link WorldContentLimits#MAX_VARIANTS_PER_FIGURE}
     * @param faceMode {@link #FACE_DEFAULT}, {@link #FACE_SHOW}, {@link #FACE_HIDE} or
     *                 {@link #FACE_SHOW_UV}
     * @param faceUv   the face region for {@link #FACE_SHOW_UV}, else null
     */
    record SetFigureExtras(String id, String figure, WorldCollectionRecord.Rarity rarity,
                           List<WorldCollectionRecord.Variant> variants, int faceMode,
                           @Nullable WorldCollectionRecord.Uv faceUv) implements WorldCollectionEdit {
        public SetFigureExtras {
            rarity = rarity != null ? rarity : WorldCollectionRecord.Rarity.COMMON;
            variants = List.copyOf(variants);
        }

        @Override
        public Action action() {
            return Action.SET_FIGURE_EXTRAS;
        }
    }

    /**
     * Copies a collection, with every figure and file it names, into a new draft with the
     * slug and name. Nothing is uploaded: the copy names the same stored files.
     */
    record Duplicate(String sourceId, String slug, String name) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.DUPLICATE;
        }
    }

    /**
     * Deletes an archived collection or a draft for good. The id is given twice, as the
     * command asks for it, and is never used again in the world.
     */
    record Purge(String id, String idAgain) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.PURGE;
        }
    }

    /**
     * Imports the pack the server's datapacks ship at namespace:path as the collection with
     * the slug, as the importpack command does.
     *
     * @param asDraft whether a collection the import creates is a draft; an existing one
     *                keeps whether it is
     */
    record ImportPack(String slug, String namespace, String path, boolean asDraft) implements WorldCollectionEdit {
        @Override
        public Action action() {
            return Action.IMPORT_PACK;
        }
    }

    /** Whether the edit must be sent on its own rather than inside a {@link Batch}. */
    static boolean standsAlone(WorldCollectionEdit edit) {
        return edit instanceof Batch || edit instanceof Duplicate || edit instanceof Purge || edit instanceof ImportPack;
    }
}
