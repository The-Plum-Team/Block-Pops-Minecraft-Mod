package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.BoxFace;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.LogoLayout;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldContentException;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The creator's working copy of one World collection: everything an operator changed and
 * has not saved yet. It starts as a copy of the collection's record, or of the record a
 * create would make for a collection that does not exist yet, and every screen of the
 * creator reads and changes this one object.
 *
 * <p>Files are named by sha; a file picked on this computer also carries its checked bytes
 * until it is uploaded ({@link FileRef}). {@link #changes()} lists what differs from the
 * record the draft was made from, which {@link CreatorSave} turns into edits. When a sync
 * brings a newer record, {@link #rebase} takes it for every field the operator did not
 * touch and keeps the rest, so a save that just landed counts as saved.
 *
 * <p>The draft remembers what each accepted save sent ({@link #markSent}), by change key, until
 * a sync that came after the server's answer shows it. A field whose value went out and then
 * changed back before that sync keeps the value it changed back to, rather than taking the sent
 * one from the sync, so the next save sends it; and a new figure that was sent and then deleted
 * here is not brought back by the sync that first holds it. A new figure the server took keeps its
 * id from then on, before any sync.
 *
 * <p>A listener ({@link #listen}) hears the key of every change the operator makes, which is
 * what {@link AutoSaver} times its saves by.
 *
 * <p>Every change first records a snapshot for {@link #undo()}, at most {@value #MAX_UNDO}
 * of them. Changes to the same field one after another, such as typing a name, share one
 * snapshot until another field changes or {@link #checkpoint()} is called. Undo replaces
 * the figure objects, so a screen looks a figure up again by id after it.
 *
 * Client thread only.
 */
public final class CreatorDraft {
    /** The most snapshots {@link #undo()} can go back through. */
    public static final int MAX_UNDO = 50;

    public static final String NEW = "new";
    public static final String NAME = "name";
    public static final String AUTHOR = "author";
    public static final String COLOUR = "colour";
    public static final String BOX = "box";
    public static final String LOGO = "logo";
    public static final String AUTHOR_URL = "author_url";
    public static final String LOGO_LAYOUT = "logo_layout";
    public static final String PLUSH = "plush";
    public static final String RARITY_WEIGHTS = "rarity_weights";
    public static final String HOLOGRAPHIC = "holographic";
    public static final String ARCHIVED = "archived";
    public static final String ORDER = "order";
    /** The change key {@link #listen} hears for an undo, which may change any field. */
    public static final String UNDO = "undo";
    /** The prefix of every change to one figure: {@code figure/<id>/<field>}. */
    public static final String FIGURE = "figure/";

    /** What a collection's box looks like. */
    public enum BoxMode {
        /** The mod's own box. */
        NONE,
        /** A plain box of one of the box colours. */
        COLOR,
        /** The collection's own 64×64 texture. */
        TEXTURE
    }

    /**
     * A file the draft names: by its sha, and with its checked bytes while it was picked on
     * this computer. Two refs to the same sha are equal, whether or not they carry bytes.
     *
     * @param local the picked file, or null for a file the server already has
     */
    public record FileRef(String sha, @Nullable FilePicker.LocalFile local) {
        public FileRef {
            Objects.requireNonNull(sha, "sha");
            if (local != null && !local.sha().equals(sha)) {
                throw new IllegalArgumentException("A picked file must keep its own sha");
            }
        }

        /** A file the server stores. */
        public static FileRef stored(String sha) {
            return new FileRef(sha, null);
        }

        /** A file picked on this computer. */
        public static FileRef of(FilePicker.LocalFile file) {
            return new FileRef(file.sha(), file);
        }

        @Nullable
        static FileRef ofNullable(@Nullable String sha) {
            return sha != null ? stored(sha) : null;
        }

        @Nullable
        static String shaOf(@Nullable FileRef ref) {
            return ref != null ? ref.sha : null;
        }

        /** What the picker or the server's file table knows about the file, or null. */
        @Nullable
        public BlobInfo info() {
            return local != null ? local.info() : ClientWorldCollections.blob(sha);
        }

        /** Whether its bytes have to be sent: picked here, and not stored on the server, or lost there. */
        public boolean needsUpload() {
            return local != null
                    && (ClientWorldCollections.blob(sha) == null || ClientWorldCollections.isMissingOnServer(sha));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FileRef ref && ref.sha.equals(sha);
        }

        @Override
        public int hashCode() {
            return sha.hashCode();
        }
    }

    /** A figure's other texture, as the draft holds it. */
    public record VariantDraft(String name, FileRef texture) {
        public VariantDraft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
        }

        WorldCollectionRecord.Variant toVariant() {
            return new WorldCollectionRecord.Variant(name, texture.sha());
        }
    }

    /**
     * What the draft predicts about the collection once saved.
     *
     * @param recordBytes     the size of its record, at most {@link WorldContentLimits#RECORD_BYTES}
     * @param collectionBytes the bytes of the distinct files it names, at most
     *                        {@link WorldContentLimits#MAX_COLLECTION_BYTES}
     * @param uploadBytes     the bytes still to be uploaded
     * @param budgetProblem   why the server would refuse it over a cap or budget, or null
     */
    public record Forecast(int recordBytes, long collectionBytes, long uploadBytes, @Nullable String budgetProblem) {
        public boolean fits() {
            return recordBytes <= WorldContentLimits.RECORD_BYTES && budgetProblem == null;
        }
    }

    /**
     * One figure of the draft. Its setters record an undo snapshot and change nothing when
     * given the value it already has.
     */
    public static final class FigureDraft {
        private final CreatorDraft owner;
        private String id;
        private String name;
        private Kind kind;
        @Nullable
        private FileRef texture;
        @Nullable
        private FileRef model;
        @Nullable
        private FileRef animation;
        private float scale;
        private boolean enabled;
        private Rarity rarity;
        private List<VariantDraft> variants;
        @Nullable
        private BoxFace boxFace;
        private boolean isNew;

        private FigureDraft(CreatorDraft owner, String id, String name, Kind kind, @Nullable FileRef texture,
                            @Nullable FileRef model, @Nullable FileRef animation, float scale, boolean enabled,
                            Rarity rarity, List<VariantDraft> variants, @Nullable BoxFace boxFace, boolean isNew) {
            this.owner = owner;
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.texture = texture;
            this.model = model;
            this.animation = animation;
            this.scale = scale;
            this.enabled = enabled;
            this.rarity = rarity;
            this.variants = List.copyOf(variants);
            this.boxFace = boxFace;
            this.isNew = isNew;
        }

        private static FigureDraft of(CreatorDraft owner, WorldCollectionRecord.Figure figure) {
            List<VariantDraft> variants = new ArrayList<>();
            for (WorldCollectionRecord.Variant variant : figure.variants()) {
                variants.add(new VariantDraft(variant.name(), FileRef.stored(variant.texture())));
            }
            return new FigureDraft(owner, figure.id(), figure.name(), figure.kind(), FileRef.stored(figure.texture()),
                    FileRef.ofNullable(figure.model()), FileRef.ofNullable(figure.animation()), figure.scale(),
                    figure.enabled(), figure.rarity(), variants, figure.boxFace(), false);
        }

        private FigureDraft copy() {
            return new FigureDraft(owner, id, name, kind, texture, model, animation, scale, enabled, rarity, variants,
                    boxFace, isNew);
        }

        private String key(String field) {
            return FIGURE + id + "/" + field;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        @Nullable
        public FileRef texture() {
            return texture;
        }

        /** The model; always null for a skin. */
        @Nullable
        public FileRef model() {
            return model;
        }

        /** The poses, or null for the shared ones; always null for a skin. */
        @Nullable
        public FileRef animation() {
            return animation;
        }

        public float scale() {
            return scale;
        }

        /** False once retired from the draw. */
        public boolean enabled() {
            return enabled;
        }

        public Rarity rarity() {
            return rarity;
        }

        public List<VariantDraft> variants() {
            return variants;
        }

        /** The box face in the record's form: null for the default of the figure's kind. */
        @Nullable
        public BoxFace boxFace() {
            return boxFace;
        }

        /** Whether the box shows this figure's face. */
        public boolean showsFace() {
            return boxFace != null ? boxFace.show() : kind == Kind.SKIN;
        }

        /** Whether the server has never stored this figure, so its id may still change. */
        public boolean isNew() {
            return isNew;
        }

        /** Its position in the draft. */
        public int index() {
            return owner.figures.indexOf(this);
        }

        /** Whether it has every file its kind needs, which a save and a preview require. */
        public boolean isComplete() {
            return texture != null && (kind == Kind.SKIN || model != null);
        }

        /** Whether anything of this figure differs from the saved collection. */
        public boolean isDirty() {
            return owner.figureChanged(this);
        }

        /**
         * Gives a new figure another id. Refused once it is saved, and for an id that is not
         * a slug or that another figure has.
         */
        public boolean setId(String next) {
            if (next.equals(id)) {
                return true;
            }
            if (!isNew || !WorldContentIds.isSlug(next) || owner.isFigureIdTaken(next)) {
                return false;
            }
            owner.checkpoint(key("id"));
            id = next;
            return true;
        }

        /** Sets the name, at most {@link WorldContentLimits#MAX_NAME_LENGTH} characters. */
        public void setName(String next) {
            checkLength(next, WorldContentLimits.MAX_NAME_LENGTH);
            if (!next.equals(name)) {
                owner.checkpoint(key("name"));
                name = next;
            }
        }

        /**
         * Renames the figure as the figure inspector's name field does. A figure the server has
         * never stored also takes the id the new name makes, unique in the collection, so its id
         * is fixed only once it is saved; a saved figure keeps its id. Typing on in the field
         * stays one undo step, though the id changes with it.
         */
        public void rename(String next) {
            if (!isNew) {
                setName(next);
                return;
            }
            checkLength(next, WorldContentLimits.MAX_NAME_LENGTH);
            if (next.equals(name)) {
                return;
            }
            // Keyed by position, which typing leaves alone, since the id changes with each letter
            owner.checkpoint(FIGURE + "#" + index() + "/name");
            name = next;
            String own = id;
            Predicate<String> taken = other -> !other.equals(own) && owner.isFigureIdTaken(other);
            String wanted = Slugs.unique(Slugs.figure(next), taken);
            // Every suffix taken leaves the id it has, so two figures never share one
            if (!taken.test(wanted)) {
                id = wanted;
            }
            owner.touched(key("name"));
        }

        /**
         * Gives a new figure the id its name makes again, as {@link #rename} would have, after a
         * save in flight held its id still while the name changed. A figure the server has is left
         * alone. It is no undo step.
         */
        public void followName() {
            if (!isNew) {
                return;
            }
            String own = id;
            Predicate<String> taken = other -> !other.equals(own) && owner.isFigureIdTaken(other);
            String wanted = Slugs.unique(Slugs.figure(name), taken);
            if (!taken.test(wanted)) {
                id = wanted;
            }
        }

        /**
         * Makes it a skin or a model figure. As on the server, the variants and the box face
         * go with the old kind, and a skin keeps no model or poses.
         */
        public void setKind(Kind next) {
            if (next == kind) {
                return;
            }
            owner.checkpoint(key("kind"));
            kind = next;
            variants = List.of();
            boxFace = null;
            if (next == Kind.SKIN) {
                model = null;
                animation = null;
            }
        }

        public void setTexture(FileRef next) {
            Objects.requireNonNull(next, "texture");
            if (!next.equals(texture) || next.local() != null && texture.local() == null) {
                owner.checkpoint(key("texture"));
                texture = next;
            }
        }

        /** Sets a model figure's model. */
        public void setModel(@Nullable FileRef next) {
            if (kind == Kind.SKIN && next != null) {
                throw new IllegalStateException("A skin figure has no model");
            }
            if (!Objects.equals(next, model) || next != null && next.local() != null && model.local() == null) {
                owner.checkpoint(key("model"));
                model = next;
            }
        }

        /** Sets a model figure's poses, or null for the shared ones. */
        public void setAnimation(@Nullable FileRef next) {
            if (kind == Kind.SKIN && next != null) {
                throw new IllegalStateException("A skin figure uses the shared poses");
            }
            if (!Objects.equals(next, animation) || next != null && next.local() != null && animation.local() == null) {
                owner.checkpoint(key("animation"));
                animation = next;
            }
        }

        /** Sets the size, which must be one of {@link WorldCollectionRecord#SCALES}. */
        public void setScale(float next) {
            if (WorldCollectionRecord.scaleIndex(next) < 0) {
                throw new IllegalArgumentException("Not a figure size: " + next);
            }
            if (next != scale) {
                owner.checkpoint(key("scale"));
                scale = next;
            }
        }

        /** Retires it from the draw, or brings it back. */
        public void setEnabled(boolean next) {
            if (next != enabled) {
                owner.checkpoint(key("enabled"));
                enabled = next;
            }
        }

        public void setRarity(Rarity next) {
            Objects.requireNonNull(next, "rarity");
            if (next != rarity) {
                owner.checkpoint(key("rarity"));
                rarity = next;
            }
        }

        /** Replaces the variants, at most {@link WorldContentLimits#MAX_VARIANTS_PER_FIGURE}. */
        public void setVariants(List<VariantDraft> next) {
            if (next.size() > WorldContentLimits.MAX_VARIANTS_PER_FIGURE) {
                throw new IllegalArgumentException("A figure has at most "
                        + WorldContentLimits.MAX_VARIANTS_PER_FIGURE + " variants");
            }
            for (VariantDraft variant : next) {
                checkLength(variant.name(), WorldContentLimits.MAX_NAME_LENGTH);
            }
            if (!sameVariants(next, variants)) {
                owner.checkpoint(key("variants"));
                variants = List.copyOf(next);
            }
        }

        /**
         * Shows or hides the face on the box. A model figure shows it only from a region of
         * its texture, so it needs one to show it.
         *
         * @param uv the face region of a model figure's texture; ignored for a skin
         */
        public void setFace(boolean show, @Nullable WorldCollectionRecord.Uv uv) {
            BoxFace next;
            if (kind == Kind.SKIN) {
                // Stored as the server stores it: a skin shows its face unless told not to
                next = show ? null : new BoxFace(false, null);
            } else if (show) {
                if (uv == null) {
                    throw new IllegalArgumentException("A model figure's face needs its region in the texture");
                }
                // The edit sends each value as a short; the server checks the region against the texture
                if (uv.u() < 0 || uv.v() < 0 || uv.w() <= 0 || uv.h() <= 0 || uv.u() > Short.MAX_VALUE
                        || uv.v() > Short.MAX_VALUE || uv.w() > Short.MAX_VALUE || uv.h() > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("Not a face region: " + uv);
                }
                next = new BoxFace(true, uv);
            } else {
                next = null;
            }
            if (!Objects.equals(next, boxFace)) {
                owner.checkpoint(key("box_face"));
                boxFace = next;
            }
        }

        /** The figure as a record names it; only for a complete figure. */
        WorldCollectionRecord.Figure toFigure() {
            if (!isComplete()) {
                throw new IllegalStateException("Figure " + id + " misses a file");
            }
            List<WorldCollectionRecord.Variant> records = new ArrayList<>(variants.size());
            for (VariantDraft variant : variants) {
                records.add(variant.toVariant());
            }
            boolean skin = kind == Kind.SKIN;
            return new WorldCollectionRecord.Figure(id, name, kind, texture.sha(), skin ? null : FileRef.shaOf(model),
                    skin ? null : FileRef.shaOf(animation), scale, enabled, rarity, records, boxFace);
        }
    }

    /** Everything an undo restores. */
    private record Snapshot(String slug, String name, String author, int red, int green, int blue, BoxMode boxMode,
                            @Nullable String boxColor, @Nullable FileRef boxTexture, @Nullable FileRef logo,
                            String authorUrl, @Nullable LogoLayout logoLayout, boolean plush,
                            @Nullable RarityWeights rarityWeights, boolean holographic, boolean archived,
                            List<FigureDraft> figures) {
    }

    // The record the draft was made from, or the one a create would make while it is new
    private WorldCollectionRecord base;
    private boolean isNew;
    private String slug;
    private String name;
    private String author;
    private int red;
    private int green;
    private int blue;
    private BoxMode boxMode;
    @Nullable
    private String boxColor;
    @Nullable
    private FileRef boxTexture;
    @Nullable
    private FileRef logo;
    private String authorUrl;
    @Nullable
    private LogoLayout logoLayout;
    private boolean plush;
    @Nullable
    private RarityWeights rarityWeights;
    private boolean holographic;
    private boolean archived;
    private List<FigureDraft> figures = new ArrayList<>();
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    @Nullable
    private String lastKey;
    private int quiet;
    // What accepted saves sent, by change key, until a sync after the server's answer shows it
    private final Map<String, Sent> sent = new HashMap<>();
    @Nullable
    private Consumer<String> listener;

    /**
     * A value a save sent and the server accepted.
     *
     * @param revision {@link ClientWorldCollections#revision()} when the server's answer came,
     *                 so only a sync applied after it counts as showing the value
     */
    private record Sent(@Nullable Object value, int revision) {
    }

    private CreatorDraft(WorldCollectionRecord base, boolean isNew) {
        this.base = base;
        this.isNew = isNew;
        this.slug = base.slug();
        takeAll(base);
    }

    /** A draft of a collection the server sent. */
    public static CreatorDraft of(WorldCollectionRecord record) {
        return new CreatorDraft(record, false);
    }

    /**
     * A draft of a collection that does not exist yet, which a save creates as a draft
     * collection with the slug and name.
     */
    public static CreatorDraft create(String slug, String name) {
        if (!WorldContentIds.isSlug(slug)) {
            throw new IllegalArgumentException("Not a slug: " + slug);
        }
        checkLength(name, WorldContentLimits.MAX_NAME_LENGTH);
        return new CreatorDraft(WorldCollectionRecord.created(WorldContentIds.collectionId(slug), name, true), true);
    }

    private void takeAll(WorldCollectionRecord record) {
        name = record.name();
        author = record.author();
        red = record.red();
        green = record.green();
        blue = record.blue();
        takeBox(record);
        logo = FileRef.ofNullable(record.logo());
        authorUrl = record.authorUrl() != null ? record.authorUrl() : "";
        logoLayout = record.logoLayout();
        plush = record.plush();
        rarityWeights = record.rarityWeights();
        holographic = record.holographic();
        archived = record.archived();
        figures = new ArrayList<>();
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            figures.add(FigureDraft.of(this, figure));
        }
    }

    private void takeBox(WorldCollectionRecord record) {
        boxTexture = FileRef.ofNullable(record.boxTexture());
        boxColor = record.boxColor();
        boxMode = boxTexture != null ? BoxMode.TEXTURE : boxColor != null ? BoxMode.COLOR : BoxMode.NONE;
    }

    // ---- Reading

    /** The record the draft compares against: the saved one, or the one a create would make. */
    public WorldCollectionRecord base() {
        return base;
    }

    /** Whether the collection does not exist on the server yet. */
    public boolean isNew() {
        return isNew;
    }

    public String slug() {
        return slug;
    }

    /** The collection's id; while it is new, the one it will be created with. */
    public String collectionId() {
        return WorldContentIds.COLLECTION_PREFIX + slug;
    }

    /** Whether the collection is a draft, out of every machine; a new one always is. */
    public boolean isDraftCollection() {
        return base.draft();
    }

    public String name() {
        return name;
    }

    public String author() {
        return author;
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }

    /** The background colour as 0xRRGGBB. */
    public int rgb() {
        return red << 16 | green << 8 | blue;
    }

    public BoxMode boxMode() {
        return boxMode;
    }

    /** The box colour's name, kept while another mode is chosen so switching back finds it. */
    @Nullable
    public String boxColor() {
        return boxColor;
    }

    /** The box texture, kept while another mode is chosen so switching back finds it. */
    @Nullable
    public FileRef boxTexture() {
        return boxTexture;
    }

    @Nullable
    public FileRef logo() {
        return logo;
    }

    /** The author link, or empty for none. */
    public String authorUrl() {
        return authorUrl;
    }

    /** The logo placement in use, {@link LogoLayout#DEFAULT} when there is none of its own. */
    public LogoLayout logoLayout() {
        return logoLayout != null ? logoLayout : LogoLayout.DEFAULT;
    }

    public boolean plush() {
        return plush;
    }

    /** The rarity weights in use, {@link RarityWeights#DEFAULT} when there are none of its own. */
    public RarityWeights rarityWeights() {
        return rarityWeights != null ? rarityWeights : RarityWeights.DEFAULT;
    }

    /** Whether a draw from the collection may come out holographic, as it may by default. */
    public boolean holographic() {
        return holographic;
    }

    public boolean archived() {
        return archived;
    }

    /** The figures in their order, as they are now: undo and rebase replace the list. */
    public List<FigureDraft> figures() {
        return List.copyOf(figures);
    }

    @Nullable
    public FigureDraft figure(String figureId) {
        for (FigureDraft figure : figures) {
            if (figure.id.equals(figureId)) {
                return figure;
            }
        }
        return null;
    }

    // ---- Changing the collection

    /** Sets the slug of a collection that is still new; refused once it exists or for a non-slug. */
    public boolean setSlug(String next) {
        if (next.equals(slug)) {
            return true;
        }
        if (!isNew || !WorldContentIds.isSlug(next)) {
            return false;
        }
        checkpoint("slug");
        slug = next;
        base = WorldCollectionRecord.created(WorldContentIds.collectionId(next), base.name(), true);
        return true;
    }

    /**
     * Moves a new collection to another slug after the server refused the one it had as taken.
     * The operator did not ask for it, so it is no undo step, and undo keeps the new slug.
     */
    boolean retrySlug(String next) {
        if (!isNew || !WorldContentIds.isSlug(next)) {
            return false;
        }
        slug = next;
        base = WorldCollectionRecord.created(WorldContentIds.collectionId(next), base.name(), true);
        List<Snapshot> kept = new ArrayList<>(undo);
        undo.clear();
        for (Snapshot snapshot : kept) {
            undo.addLast(new Snapshot(next, snapshot.name(), snapshot.author(), snapshot.red(), snapshot.green(),
                    snapshot.blue(), snapshot.boxMode(), snapshot.boxColor(), snapshot.boxTexture(), snapshot.logo(),
                    snapshot.authorUrl(), snapshot.logoLayout(), snapshot.plush(), snapshot.rarityWeights(),
                    snapshot.holographic(), snapshot.archived(), snapshot.figures()));
        }
        return true;
    }

    /**
     * Marks a new collection as created once the server saved the batch that creates it,
     * under the slug that batch carried, so a second save sent before the sync arrives does
     * not create it again under another slug. Until {@link #rebase} the draft still compares
     * against the empty record a create makes, so that save repeats the edits already
     * saved, which the server takes as changing nothing.
     */
    void markCreated(String createdId) {
        if (!isNew || !createdId.startsWith(WorldContentIds.COLLECTION_PREFIX)) {
            return;
        }
        String createdSlug = createdId.substring(WorldContentIds.COLLECTION_PREFIX.length());
        if (!WorldContentIds.isSlug(createdSlug)) {
            return;
        }
        if (!createdSlug.equals(slug)) {
            // The operator may have changed the slug while the create was on its way
            slug = createdSlug;
            base = WorldCollectionRecord.created(createdId, base.name(), true);
        }
        isNew = false;
    }

    /** Sets the name, at most {@link WorldContentLimits#MAX_NAME_LENGTH} characters. */
    public void setName(String next) {
        checkLength(next, WorldContentLimits.MAX_NAME_LENGTH);
        if (!next.equals(name)) {
            checkpoint(NAME);
            name = next;
        }
    }

    /** Sets the author, at most {@link WorldContentLimits#MAX_AUTHOR_LENGTH} characters. */
    public void setAuthor(String next) {
        checkLength(next, WorldContentLimits.MAX_AUTHOR_LENGTH);
        if (!next.equals(author)) {
            checkpoint(AUTHOR);
            author = next;
        }
    }

    /** Sets the background colour from 0xRRGGBB. */
    public void setRgb(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        if (r != red || g != green || b != blue) {
            checkpoint(COLOUR);
            red = r;
            green = g;
            blue = b;
        }
    }

    /** Chooses the box. Choosing a colour or a texture before one is set shows the mod's box meanwhile. */
    public void setBoxMode(BoxMode next) {
        if (next != boxMode) {
            checkpoint(BOX);
            boxMode = next;
        }
    }

    /** Makes it a plain box of one of the box colours. */
    public void setBoxColor(String next) {
        if (!WorldCollectionRecord.isBoxColor(next)) {
            throw new IllegalArgumentException("Not a box colour: " + next);
        }
        if (boxMode != BoxMode.COLOR || !next.equals(boxColor)) {
            checkpoint(BOX);
            boxColor = next;
            boxMode = BoxMode.COLOR;
        }
    }

    /** Gives it its own box texture. */
    public void setBoxTexture(FileRef next) {
        Objects.requireNonNull(next, "box texture");
        if (boxMode != BoxMode.TEXTURE || !next.equals(boxTexture)
                || next.local() != null && boxTexture.local() == null) {
            checkpoint(BOX);
            boxTexture = next;
            boxMode = BoxMode.TEXTURE;
        }
    }

    /** Sets the logo, or removes it with null, which also forgets its placement. */
    public void setLogo(@Nullable FileRef next) {
        if (!Objects.equals(next, logo) || next != null && next.local() != null && logo.local() == null) {
            checkpoint(LOGO);
            logo = next;
            if (next == null) {
                logoLayout = null;
            }
        }
    }

    /** Sets the author link, or none with empty; checked by {@link WorldCollectionRecord#isValidAuthorUrl}. */
    public void setAuthorUrl(String next) {
        checkLength(next, WorldContentLimits.MAX_AUTHOR_URL_LENGTH);
        if (!next.equals(authorUrl)) {
            checkpoint(AUTHOR_URL);
            authorUrl = next;
        }
    }

    /** Places the logo, within the size and offsets {@link LogoLayout#isValid} allows. */
    public void setLogoLayout(LogoLayout next) {
        if (!LogoLayout.isValid(next.scalePct(), next.offsetX(), next.offsetY())) {
            throw new IllegalArgumentException("Not a logo placement: " + next);
        }
        LogoLayout normal = LogoLayout.DEFAULT.equals(next) ? null : next;
        if (!Objects.equals(normal, logoLayout)) {
            checkpoint(LOGO_LAYOUT);
            logoLayout = normal;
        }
    }

    public void setPlush(boolean next) {
        if (next != plush) {
            checkpoint(PLUSH);
            plush = next;
        }
    }

    /**
     * Lets the collection's draws come out holographic, one time in 25 as every collection's may,
     * or turns that off, so none of them ever does.
     */
    public void setHolographic(boolean next) {
        if (next != holographic) {
            checkpoint(HOLOGRAPHIC);
            holographic = next;
        }
    }

    /** Sets the rarity weights, each within the range {@link RarityWeights#isValid} allows. */
    public void setRarityWeights(RarityWeights next) {
        if (!RarityWeights.isValid(next.common(), next.rare(), next.epic(), next.legendary())) {
            throw new IllegalArgumentException("Not rarity weights: " + next);
        }
        RarityWeights normal = RarityWeights.DEFAULT.equals(next) ? null : next;
        if (!Objects.equals(normal, rarityWeights)) {
            checkpoint(RARITY_WEIGHTS);
            rarityWeights = normal;
        }
    }

    /** Takes a published collection out of the machines, or puts it back. A draft is never archived. */
    public void setArchived(boolean next) {
        if (next && isDraftCollection()) {
            throw new IllegalStateException("A draft is not archived");
        }
        if (next != archived) {
            checkpoint(ARCHIVED);
            archived = next;
        }
    }

    // ---- Changing the figures

    /** Whether a figure of the draft, or one the saved collection had, uses the id. */
    public boolean isFigureIdTaken(String figureId) {
        // A figure the server took is there even before the sync shows it, and keeps its id
        return figure(figureId) != null || base.figure(figureId).isPresent()
                || sent.containsKey(FIGURE + figureId + "/" + NEW);
    }

    /** Whether the draft holds as many figures as a collection may. */
    public boolean isFull() {
        return figures.size() >= WorldContentLimits.MAX_FIGURES;
    }

    /**
     * Adds a new skin figure with no files yet, last, with an id made from the name.
     *
     * @return the figure, or null when the collection is full
     */
    @Nullable
    public FigureDraft addFigure(String figureName) {
        return addFigure(figureName, Kind.SKIN, null, null, null, 1.0f);
    }

    /**
     * Adds a new figure last, with an id made from the name.
     *
     * @return the figure, or null when the collection is full
     */
    @Nullable
    public FigureDraft addFigure(String figureName, Kind kind, @Nullable FileRef texture, @Nullable FileRef model,
                                 @Nullable FileRef animation, float scale) {
        if (isFull()) {
            return null;
        }
        checkLength(figureName, WorldContentLimits.MAX_NAME_LENGTH);
        if (WorldCollectionRecord.scaleIndex(scale) < 0) {
            throw new IllegalArgumentException("Not a figure size: " + scale);
        }
        boolean skin = kind == Kind.SKIN;
        String figureId = Slugs.unique(Slugs.figure(figureName), this::isFigureIdTaken);
        checkpoint(FIGURE + figureId + "/" + NEW);
        FigureDraft figure = new FigureDraft(this, figureId, figureName, kind, texture, skin ? null : model,
                skin ? null : animation, scale, true, Rarity.COMMON, List.of(), null, true);
        figures.add(figure);
        return figure;
    }

    /**
     * Adds a copy of a figure right after it, with a new id and the same files and extras.
     *
     * @return the copy, or null when the collection is full or there is no such figure
     */
    @Nullable
    public FigureDraft duplicateFigure(String figureId) {
        FigureDraft source = figure(figureId);
        if (source == null || isFull()) {
            return null;
        }
        String copyId = Slugs.unique(Slugs.next(source.id), this::isFigureIdTaken);
        checkpoint(FIGURE + copyId + "/" + NEW);
        FigureDraft copy = new FigureDraft(this, copyId, source.name, source.kind, source.texture, source.model,
                source.animation, source.scale, source.enabled, source.rarity, source.variants, source.boxFace, true);
        figures.add(figures.indexOf(source) + 1, copy);
        return copy;
    }

    /**
     * Takes a figure out of the draft. A new one is just dropped and a draft collection's
     * figure is deleted on save; a published collection's figure may be in players' boxes,
     * so it is retired instead and stays.
     *
     * @return whether it left the draft
     */
    public boolean removeFigure(String figureId) {
        FigureDraft figure = figure(figureId);
        if (figure == null) {
            return false;
        }
        if (!figure.isNew && !isDraftCollection()) {
            figure.setEnabled(false);
            return false;
        }
        checkpoint(FIGURE + figureId + "/removed");
        figures.remove(figure);
        return true;
    }

    /** Moves a figure to another position, as a drag in the grid does. */
    public void moveFigure(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= figures.size() || to >= figures.size()) {
            return;
        }
        checkpoint(ORDER);
        figures.add(to, figures.remove(from));
    }

    // ---- Undo

    /**
     * Ends the current run of changes, so the next change gets an undo snapshot of its own
     * even when it changes the same field, as when a text field loses focus.
     */
    public void checkpoint() {
        lastKey = null;
    }

    /**
     * Makes several changes one undo step, such as retiring every selected figure: one
     * snapshot is taken before them and none inside.
     */
    public void change(String key, Runnable changes) {
        checkpoint(key);
        lastKey = null;
        quiet++;
        try {
            changes.run();
        } finally {
            quiet--;
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Goes back to how the draft was before the last change; false when there is nothing to undo. */
    public boolean undo() {
        Snapshot snapshot = undo.pollFirst();
        if (snapshot == null) {
            return false;
        }
        restore(snapshot);
        lastKey = null;
        touched(UNDO);
        return true;
    }

    /** Forgets every undo step, as when another editor attaches to this draft or it was published. */
    public void clearUndo() {
        undo.clear();
        lastKey = null;
    }

    /**
     * Sets what hears the key of every change the operator makes from now on: a field's key as
     * {@link #changes()} names it, {@link #UNDO} after an undo, or the key of a group of changes
     * {@link #change} makes. Null for none.
     */
    public void listen(@Nullable Consumer<String> next) {
        this.listener = next;
    }

    private void touched(String key) {
        Consumer<String> heard = this.listener;
        if (heard != null) {
            heard.accept(key);
        }
    }

    // Records a snapshot unless the change continues the previous one; every change passes here
    private void checkpoint(String key) {
        // A rename keys its step by position, and says its own key once its id followed
        if (key.indexOf('#') < 0) {
            touched(key);
        }
        if (quiet > 0 || key.equals(lastKey)) {
            return;
        }
        lastKey = key;
        undo.addFirst(snapshot());
        while (undo.size() > MAX_UNDO) {
            undo.pollLast();
        }
    }

    private Snapshot snapshot() {
        List<FigureDraft> copies = new ArrayList<>(figures.size());
        for (FigureDraft figure : figures) {
            copies.add(figure.copy());
        }
        return new Snapshot(slug, name, author, red, green, blue, boxMode, boxColor, boxTexture, logo, authorUrl,
                logoLayout, plush, rarityWeights, holographic, archived, copies);
    }

    private void restore(Snapshot snapshot) {
        if (isNew && !snapshot.slug().equals(slug)) {
            slug = snapshot.slug();
            base = WorldCollectionRecord.created(WorldContentIds.collectionId(slug), base.name(), true);
        }
        name = snapshot.name();
        author = snapshot.author();
        red = snapshot.red();
        green = snapshot.green();
        blue = snapshot.blue();
        boxMode = snapshot.boxMode();
        boxColor = snapshot.boxColor();
        boxTexture = snapshot.boxTexture();
        logo = snapshot.logo();
        authorUrl = snapshot.authorUrl();
        logoLayout = snapshot.logoLayout();
        plush = snapshot.plush();
        rarityWeights = snapshot.rarityWeights();
        holographic = snapshot.holographic();
        archived = snapshot.archived();
        figures = new ArrayList<>();
        for (FigureDraft figure : snapshot.figures()) {
            FigureDraft copy = figure.copy();
            // Whether a figure is new follows the server, which may have stored or dropped it since
            copy.isNew = base.figure(copy.id).isEmpty() && !sent.containsKey(FIGURE + copy.id + "/" + NEW);
            figures.add(copy);
        }
    }

    // ---- What changed

    /**
     * Every change against the saved collection, one entry per field: a collection field
     * such as {@link #NAME}, {@link #ORDER}, {@link #NEW} for a collection not created yet,
     * or {@code figure/<id>/<field>} for a figure, where a new figure is one entry
     * {@code figure/<id>/new} and a deleted one {@code figure/<id>/removed}.
     */
    public List<String> changes() {
        List<String> changes = new ArrayList<>();
        if (isNew) {
            changes.add(NEW);
        }
        if (!name.equals(base.name())) {
            changes.add(NAME);
        }
        if (!author.equals(base.author())) {
            changes.add(AUTHOR);
        }
        if (red != base.red() || green != base.green() || blue != base.blue()) {
            changes.add(COLOUR);
        }
        if (!Objects.equals(boxTextureSha(), base.boxTexture()) || !Objects.equals(boxColorName(), base.boxColor())) {
            changes.add(BOX);
        }
        if (!Objects.equals(FileRef.shaOf(logo), base.logo())) {
            changes.add(LOGO);
        }
        if (!Objects.equals(authorUrlOrNull(), base.authorUrl())) {
            changes.add(AUTHOR_URL);
        }
        if (!Objects.equals(effectiveLogoLayout(), base.logoLayout())) {
            changes.add(LOGO_LAYOUT);
        }
        if (plush != base.plush()) {
            changes.add(PLUSH);
        }
        if (!Objects.equals(rarityWeights, base.rarityWeights())) {
            changes.add(RARITY_WEIGHTS);
        }
        if (holographic != base.holographic()) {
            changes.add(HOLOGRAPHIC);
        }
        if (archived != base.archived()) {
            changes.add(ARCHIVED);
        }
        Set<String> ids = new HashSet<>();
        for (FigureDraft figure : figures) {
            ids.add(figure.id);
            WorldCollectionRecord.Figure saved = base.figure(figure.id).orElse(null);
            if (saved == null) {
                changes.add(FIGURE + figure.id + "/" + NEW);
            } else {
                for (String field : figureChanges(figure, saved)) {
                    changes.add(FIGURE + figure.id + "/" + field);
                }
            }
        }
        for (WorldCollectionRecord.Figure saved : base.figures()) {
            if (!ids.contains(saved.id())) {
                changes.add(FIGURE + saved.id() + "/removed");
            }
        }
        if (isOrderChanged()) {
            changes.add(ORDER);
        }
        return changes;
    }

    /** How many unsaved changes there are, as the editor's pill counts them. */
    public int dirtyCount() {
        return changes().size();
    }

    public boolean isDirty() {
        return dirtyCount() > 0;
    }

    /** Whether the named change is among {@link #changes()}. */
    public boolean changed(String key) {
        return changes().contains(key);
    }

    /** Whether the name, author, colour, box or logo changed, which one SetCollection saves. */
    public boolean identityChanged() {
        return !name.equals(base.name()) || !author.equals(base.author()) || red != base.red()
                || green != base.green() || blue != base.blue()
                || !Objects.equals(boxTextureSha(), base.boxTexture()) || !Objects.equals(boxColorName(), base.boxColor())
                || !Objects.equals(FileRef.shaOf(logo), base.logo());
    }

    /** Whether the author link, logo placement, plush flag or rarity weights changed. */
    public boolean extrasChanged() {
        return !Objects.equals(authorUrlOrNull(), base.authorUrl())
                || !Objects.equals(effectiveLogoLayout(), base.logoLayout()) || plush != base.plush()
                || !Objects.equals(rarityWeights, base.rarityWeights());
    }

    private boolean figureChanged(FigureDraft figure) {
        WorldCollectionRecord.Figure saved = base.figure(figure.id).orElse(null);
        return saved == null || !figureChanges(figure, saved).isEmpty();
    }

    /** The fields of a figure that differ from its saved form. */
    static List<String> figureChanges(FigureDraft figure, WorldCollectionRecord.Figure saved) {
        List<String> fields = new ArrayList<>();
        if (!figure.name.equals(saved.name())) {
            fields.add("name");
        }
        if (figure.kind != saved.kind()) {
            fields.add("kind");
        }
        if (!Objects.equals(FileRef.shaOf(figure.texture), saved.texture())) {
            fields.add("texture");
        }
        if (!Objects.equals(FileRef.shaOf(figure.model), saved.model())) {
            fields.add("model");
        }
        if (!Objects.equals(FileRef.shaOf(figure.animation), saved.animation())) {
            fields.add("animation");
        }
        if (figure.scale != saved.scale()) {
            fields.add("scale");
        }
        if (figure.enabled != saved.enabled()) {
            fields.add("enabled");
        }
        if (figure.rarity != saved.rarity()) {
            fields.add("rarity");
        }
        if (!sameVariants(figure.variants, draftsOf(saved.variants()))) {
            fields.add("variants");
        }
        if (!Objects.equals(figure.boxFace, saved.boxFace())) {
            fields.add("box_face");
        }
        return fields;
    }

    private static List<VariantDraft> draftsOf(List<WorldCollectionRecord.Variant> variants) {
        List<VariantDraft> drafts = new ArrayList<>(variants.size());
        for (WorldCollectionRecord.Variant variant : variants) {
            drafts.add(new VariantDraft(variant.name(), FileRef.stored(variant.texture())));
        }
        return drafts;
    }

    private static boolean sameVariants(List<VariantDraft> a, List<VariantDraft> b) {
        return a.equals(b);
    }

    /**
     * Whether the figures are in another order than the server would leave them in after a
     * save: the saved ones in their order, without the deleted ones, then the new ones.
     */
    public boolean isOrderChanged() {
        List<String> ids = orderedIds(figures);
        return !ids.equals(serverOrder(base, ids));
    }

    private static List<String> orderedIds(List<FigureDraft> list) {
        List<String> ids = new ArrayList<>(list.size());
        for (FigureDraft figure : list) {
            ids.add(figure.id);
        }
        return ids;
    }

    /**
     * The order a save leaves the figures in on the server before any reorder: the saved ones
     * that stay in their order, then the new ones as they are added.
     *
     * @param ids every figure id the collection holds after the save, in the draft's order
     */
    static List<String> serverOrder(WorldCollectionRecord base, List<String> ids) {
        Set<String> kept = new HashSet<>(ids);
        List<String> order = new ArrayList<>();
        for (WorldCollectionRecord.Figure saved : base.figures()) {
            if (kept.contains(saved.id())) {
                order.add(saved.id());
            }
        }
        for (String id : ids) {
            if (base.figure(id).isEmpty()) {
                order.add(id);
            }
        }
        return order;
    }

    @Nullable
    String boxTextureSha() {
        return boxMode == BoxMode.TEXTURE ? FileRef.shaOf(boxTexture) : null;
    }

    @Nullable
    String boxColorName() {
        return boxMode == BoxMode.COLOR ? boxColor : null;
    }

    @Nullable
    String authorUrlOrNull() {
        return authorUrl.isEmpty() ? null : authorUrl;
    }

    /** The logo placement as the record keeps it: none without a logo, and null for the default. */
    @Nullable
    LogoLayout effectiveLogoLayout() {
        return logo != null ? logoLayout : null;
    }

    // ---- The collection as it would be saved

    /**
     * The record the draft describes, for the previews and the forecasts. Figures that
     * miss a file are left out, as a save leaves them out. Nothing is checked: the record
     * may break any rule the server enforces.
     */
    public WorldCollectionRecord toRecord() {
        List<WorldCollectionRecord.Figure> complete = new ArrayList<>();
        for (FigureDraft figure : figures) {
            if (figure.isComplete()) {
                complete.add(figure.toFigure());
            }
        }
        return new WorldCollectionRecord(collectionId(), name, author, red, green, blue, boxTextureSha(),
                boxColorName(), FileRef.shaOf(logo), archived, base.revision(), complete, base.draft(),
                base.editedAt(), authorUrlOrNull(), effectiveLogoLayout(), plush, rarityWeights, holographic);
    }

    /** Every file picked on this computer that the draft names, by sha. */
    public Map<String, FilePicker.LocalFile> localFiles() {
        Map<String, FilePicker.LocalFile> files = new LinkedHashMap<>();
        addLocal(files, boxMode == BoxMode.TEXTURE ? boxTexture : null);
        addLocal(files, logo);
        for (FigureDraft figure : figures) {
            addLocal(files, figure.texture);
            if (figure.kind == Kind.MODEL) {
                addLocal(files, figure.model);
                addLocal(files, figure.animation);
            }
            for (VariantDraft variant : figure.variants) {
                addLocal(files, variant.texture());
            }
        }
        return files;
    }

    private static void addLocal(Map<String, FilePicker.LocalFile> files, @Nullable FileRef ref) {
        if (ref != null && ref.local() != null) {
            files.putIfAbsent(ref.sha(), ref.local());
        }
    }

    /** What the picker learned about each local file, as {@link CreatorPreview#show} takes it. */
    public Map<String, BlobInfo> localBlobs() {
        Map<String, BlobInfo> blobs = new HashMap<>();
        for (FilePicker.LocalFile file : localFiles().values()) {
            blobs.put(file.sha(), file.info());
        }
        return blobs;
    }

    /**
     * Shows the draft in a preview slot, loading its local files for the renderers first.
     *
     * @return false when the record cannot be shown, which keeps what the slot showed
     */
    public boolean preview(int slot) {
        for (FilePicker.LocalFile file : localFiles().values()) {
            ClientPreviewAssets.use(file);
        }
        return CreatorPreview.show(slot, toRecord(), localBlobs());
    }

    /**
     * Predicts the record's size and whether the server's caps and budgets allow it, with
     * this collection's record replaced by the draft's among every collection the client
     * was sent.
     */
    public Forecast forecast() {
        return forecast(toRecord(), localFiles());
    }

    /**
     * Predicts as {@link #forecast()} does with more new figures added last and the local
     * files they name, as the bulk add shows before it adds them.
     */
    public Forecast forecastWith(List<WorldCollectionRecord.Figure> more, Collection<FilePicker.LocalFile> moreFiles) {
        WorldCollectionRecord candidate = toRecord();
        if (!more.isEmpty()) {
            List<WorldCollectionRecord.Figure> all = new ArrayList<>(candidate.figures());
            all.addAll(more);
            candidate = candidate.withFigures(all);
        }
        Map<String, FilePicker.LocalFile> local = localFiles();
        for (FilePicker.LocalFile file : moreFiles) {
            local.putIfAbsent(file.sha(), file);
        }
        return forecast(candidate, local);
    }

    private static Forecast forecast(WorldCollectionRecord candidate, Map<String, FilePicker.LocalFile> local) {
        Map<String, BlobInfo> blobs = new HashMap<>(ClientWorldCollections.blobs());
        for (FilePicker.LocalFile file : local.values()) {
            blobs.putIfAbsent(file.sha(), file.info());
        }
        long collectionBytes = 0;
        for (String sha : candidate.referencedShas()) {
            BlobInfo info = blobs.get(sha);
            if (info != null) {
                collectionBytes += info.size();
            }
        }
        long uploadBytes = 0;
        for (FilePicker.LocalFile file : local.values()) {
            if (FileRef.of(file).needsUpload()) {
                uploadBytes += file.bytes().length;
            }
        }
        List<WorldCollectionRecord> all = new ArrayList<>();
        boolean replaced = false;
        for (WorldCollectionRecord record : ClientWorldCollections.records()) {
            if (record.id().equals(candidate.id())) {
                all.add(candidate);
                replaced = true;
            } else {
                all.add(record);
            }
        }
        if (!replaced) {
            all.add(candidate);
        }
        String problem = null;
        try {
            WorldCollectionRecord.checkBudgets(all, blobs);
        } catch (WorldContentException e) {
            problem = e.getMessage();
        }
        return new Forecast(WorldCollectionRecord.encodedSize(candidate), collectionBytes, uploadBytes, problem);
    }

    // ---- What saves sent

    /**
     * Remembers what a save sent once the server accepted it: the value of each change key the
     * save carried, and the new figures it put, whose ids are fixed from now on. A sync applied
     * after this call shows those values, and {@link #rebase} judges the fields by them.
     *
     * @param values      each change key the save carried, with the value it sent
     * @param putFigures  the ids of new figures the save put
     */
    public void markSent(Map<String, Object> values, Collection<String> putFigures) {
        int revision = ClientWorldCollections.revision();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            sent.put(entry.getKey(), new Sent(entry.getValue(), revision));
        }
        for (String figureId : putFigures) {
            sent.put(FIGURE + figureId + "/" + NEW, new Sent(Boolean.TRUE, revision));
            FigureDraft figure = figure(figureId);
            if (figure != null) {
                figure.isNew = false;
            }
        }
    }

    /** Whether the draft remembers a sent value no sync has shown yet. */
    public boolean hasUnconfirmedSends() {
        return !sent.isEmpty();
    }

    /** Forgets what saves sent, as when the collection was published and saves start again. */
    public void forgetSent() {
        sent.clear();
    }

    /**
     * The draft's value of a change key, as {@link #changes()} names it, in a form that equals
     * what {@link #valueIn} gives for a record holding the same: a figure's {@code new} key is
     * whether the draft has the figure, and its {@code removed} key whether it does not.
     */
    @Nullable
    Object value(String key) {
        if (key.startsWith(FIGURE)) {
            int slash = key.lastIndexOf('/');
            String figureId = key.substring(FIGURE.length(), slash);
            String field = key.substring(slash + 1);
            FigureDraft figure = figure(figureId);
            if (field.equals(NEW)) {
                return figure != null;
            }
            if (field.equals("removed")) {
                return figure == null;
            }
            return figure != null ? figureValue(figure, field) : null;
        }
        return switch (key) {
            case NAME -> name;
            case AUTHOR -> author;
            case COLOUR -> rgb();
            case BOX -> boxValue(boxTextureSha(), boxColorName());
            case LOGO -> FileRef.shaOf(logo);
            case AUTHOR_URL -> authorUrlOrNull();
            case LOGO_LAYOUT -> effectiveLogoLayout();
            case PLUSH -> plush;
            case RARITY_WEIGHTS -> rarityWeights;
            case HOLOGRAPHIC -> holographic;
            case ARCHIVED -> archived;
            case ORDER -> orderedIds(figures);
            default -> null;
        };
    }

    /** A record's value of a change key, in the form {@link #value} gives. */
    @Nullable
    static Object valueIn(WorldCollectionRecord record, String key) {
        if (key.startsWith(FIGURE)) {
            int slash = key.lastIndexOf('/');
            String figureId = key.substring(FIGURE.length(), slash);
            String field = key.substring(slash + 1);
            WorldCollectionRecord.Figure figure = record.figure(figureId).orElse(null);
            if (field.equals(NEW)) {
                return figure != null;
            }
            if (field.equals("removed")) {
                return figure == null;
            }
            return figure != null ? recordFigureValue(figure, field) : null;
        }
        return switch (key) {
            case NAME -> record.name();
            case AUTHOR -> record.author();
            case COLOUR -> record.red() << 16 | record.green() << 8 | record.blue();
            case BOX -> boxValue(record.boxTexture(), record.boxColor());
            case LOGO -> record.logo();
            case AUTHOR_URL -> record.authorUrl();
            case LOGO_LAYOUT -> record.logoLayout();
            case PLUSH -> record.plush();
            case RARITY_WEIGHTS -> record.rarityWeights();
            case HOLOGRAPHIC -> record.holographic();
            case ARCHIVED -> record.archived();
            case ORDER -> {
                List<String> ids = new ArrayList<>();
                for (WorldCollectionRecord.Figure figure : record.figures()) {
                    ids.add(figure.id());
                }
                yield ids;
            }
            default -> null;
        };
    }

    private static String boxValue(@Nullable String textureSha, @Nullable String colourName) {
        return textureSha != null ? "texture:" + textureSha : colourName != null ? "colour:" + colourName : "";
    }

    @Nullable
    private static Object figureValue(FigureDraft figure, String field) {
        return switch (field) {
            case "name" -> figure.name;
            case "kind" -> figure.kind;
            case "texture" -> FileRef.shaOf(figure.texture);
            case "model" -> FileRef.shaOf(figure.model);
            case "animation" -> FileRef.shaOf(figure.animation);
            case "scale" -> figure.scale;
            case "enabled" -> figure.enabled;
            case "rarity" -> figure.rarity;
            case "variants" -> {
                List<WorldCollectionRecord.Variant> records = new ArrayList<>();
                for (VariantDraft variant : figure.variants) {
                    records.add(variant.toVariant());
                }
                yield records;
            }
            case "box_face" -> figure.boxFace;
            default -> null;
        };
    }

    @Nullable
    private static Object recordFigureValue(WorldCollectionRecord.Figure figure, String field) {
        return switch (field) {
            case "name" -> figure.name();
            case "kind" -> figure.kind();
            case "texture" -> figure.texture();
            case "model" -> figure.model();
            case "animation" -> figure.animation();
            case "scale" -> figure.scale();
            case "enabled" -> figure.enabled();
            case "rarity" -> figure.rarity();
            case "variants" -> figure.variants();
            case "box_face" -> figure.boxFace();
            default -> null;
        };
    }

    // ---- Syncs

    /**
     * Takes a newer record of the collection the server sent, as {@link #rebase(WorldCollectionRecord, int)}
     * does for the sync the client applied last.
     */
    public void rebase(WorldCollectionRecord newer) {
        rebase(newer, ClientWorldCollections.revision());
    }

    /**
     * Takes a newer record of the collection the server sent: every field the operator did not
     * change takes the newer value, and every changed one keeps the draft's. A new collection
     * becomes an existing one once the server sent it, and a new figure the server now has is
     * new no longer. Saved figures the newer record dropped leave the draft unless they hold
     * changes, which then make them new again.
     *
     * <p>A field that a save sent counts as untouched here when the draft still holds the value
     * sent, so it follows the server from then on, and as changed when the operator changed it
     * again since, whatever the server holds; a sync applied before the server's answer to that
     * save does not hold it yet, so it leaves such a field as the draft has it. A new figure a save
     * put and the operator then deleted is not added back. Once a sync after the answer has been
     * taken, the draft forgets what that save sent.
     *
     * @param syncRevision the {@link ClientWorldCollections#revision()} of the sync that brought
     *                     the record
     */
    public void rebase(WorldCollectionRecord newer, int syncRevision) {
        if (!newer.id().equals(collectionId())) {
            throw new IllegalArgumentException("Another collection: " + newer.id());
        }
        WorldCollectionRecord older = base;
        // Every field's change is judged against the older record before any field moves on
        boolean nameKept = kept(NAME, older, syncRevision);
        boolean authorKept = kept(AUTHOR, older, syncRevision);
        boolean colourKept = kept(COLOUR, older, syncRevision);
        boolean boxKept = kept(BOX, older, syncRevision);
        boolean logoKept = kept(LOGO, older, syncRevision);
        boolean authorUrlKept = kept(AUTHOR_URL, older, syncRevision);
        boolean layoutKept = kept(LOGO_LAYOUT, older, syncRevision);
        boolean plushKept = kept(PLUSH, older, syncRevision);
        boolean weightsKept = kept(RARITY_WEIGHTS, older, syncRevision);
        boolean holographicKept = kept(HOLOGRAPHIC, older, syncRevision);
        boolean archivedKept = kept(ARCHIVED, older, syncRevision);
        if (!nameKept) {
            name = newer.name();
        }
        if (!authorKept) {
            author = newer.author();
        }
        if (!colourKept) {
            red = newer.red();
            green = newer.green();
            blue = newer.blue();
        }
        if (!boxKept) {
            // The unused texture or colour stays for switching back, unless the newer record names one
            if (newer.boxTexture() != null) {
                boxTexture = keepLocal(boxTexture, newer.boxTexture());
            }
            if (newer.boxColor() != null) {
                boxColor = newer.boxColor();
            }
            boxMode = newer.boxTexture() != null ? BoxMode.TEXTURE
                    : newer.boxColor() != null ? BoxMode.COLOR : BoxMode.NONE;
        }
        if (!logoKept) {
            logo = newer.logo() != null ? keepLocal(logo, newer.logo()) : null;
        }
        if (!authorUrlKept) {
            authorUrl = newer.authorUrl() != null ? newer.authorUrl() : "";
        }
        if (!layoutKept) {
            logoLayout = newer.logoLayout();
        }
        if (!plushKept) {
            plush = newer.plush();
        }
        if (!weightsKept) {
            rarityWeights = newer.rarityWeights();
        }
        if (!holographicKept) {
            holographic = newer.holographic();
        }
        if (!archivedKept) {
            archived = newer.archived();
        }
        boolean orderKept = orderKept(syncRevision);
        List<FigureDraft> next = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (FigureDraft figure : figures) {
            present.add(figure.id);
            WorldCollectionRecord.Figure before = older.figure(figure.id).orElse(null);
            WorldCollectionRecord.Figure after = newer.figure(figure.id).orElse(null);
            String newKey = FIGURE + figure.id + "/" + NEW;
            Sent put = sent.get(newKey);
            if (after == null) {
                if (put != null && put.revision() >= syncRevision) {
                    // The server took it after this sync was made: it stays, with its id
                    next.add(figure);
                    continue;
                }
                // Gone from the server: kept only when it holds changes, or was deleted here and
                // brought back, as a figure to add again
                if (before == null || !figureChanges(figure, before).isEmpty()
                        || sent.containsKey(FIGURE + figure.id + "/removed")) {
                    figure.isNew = true;
                    next.add(figure);
                }
                continue;
            }
            if (before != null) {
                rebaseFigure(figure, older, after, syncRevision);
            }
            figure.isNew = false;
            next.add(figure);
        }
        for (WorldCollectionRecord.Figure added : newer.figures()) {
            // Deleted here: one the older record had, or a new one this draft put and then deleted
            boolean removedHere = older.figure(added.id()).isPresent()
                    || sent.containsKey(FIGURE + added.id() + "/" + NEW);
            if (!present.contains(added.id()) && !removedHere) {
                next.add(FigureDraft.of(this, added));
            }
        }
        base = newer;
        isNew = false;
        slug = newer.slug();
        if (orderKept) {
            figures = next;
        } else {
            // Untouched order follows the server's, with figures it does not have yet last
            Map<String, FigureDraft> byId = new LinkedHashMap<>();
            for (FigureDraft figure : next) {
                byId.put(figure.id, figure);
            }
            List<FigureDraft> ordered = new ArrayList<>();
            for (WorldCollectionRecord.Figure saved : newer.figures()) {
                FigureDraft figure = byId.remove(saved.id());
                if (figure != null) {
                    ordered.add(figure);
                }
            }
            ordered.addAll(byId.values());
            figures = ordered;
        }
        // This sync came after the answers to these saves, so it shows what they sent
        sent.values().removeIf(entry -> entry.revision() < syncRevision);
    }

    /**
     * Whether a field keeps the draft's value through a rebase: it was changed here, judged by
     * what a save sent once a sync after the server's answer can show it, and by the older record
     * otherwise.
     */
    private boolean kept(String key, WorldCollectionRecord older, int syncRevision) {
        Object mine = value(key);
        Sent was = sent.get(key);
        if (was != null) {
            // Before the sync that shows the save, the draft holds what the server is about to have
            return was.revision() >= syncRevision || !Objects.equals(mine, was.value());
        }
        return !Objects.equals(mine, valueIn(older, key));
    }

    private boolean orderKept(int syncRevision) {
        Sent was = sent.get(ORDER);
        if (was != null) {
            return was.revision() >= syncRevision || !orderedIds(figures).equals(was.value());
        }
        return isOrderChanged();
    }

    private void rebaseFigure(FigureDraft figure, WorldCollectionRecord older, WorldCollectionRecord.Figure after,
                              int syncRevision) {
        String prefix = FIGURE + figure.id + "/";
        if (!kept(prefix + "name", older, syncRevision)) {
            figure.name = after.name();
        }
        if (!kept(prefix + "kind", older, syncRevision) && !kept(prefix + "texture", older, syncRevision)
                && !kept(prefix + "model", older, syncRevision) && !kept(prefix + "animation", older, syncRevision)) {
            // The kind and its files go together, as a put replaces them together
            figure.kind = after.kind();
            figure.texture = keepLocal(figure.texture, after.texture());
            figure.model = after.model() != null ? keepLocal(figure.model, after.model()) : null;
            figure.animation = after.animation() != null ? keepLocal(figure.animation, after.animation()) : null;
        }
        if (!kept(prefix + "scale", older, syncRevision)) {
            figure.scale = after.scale();
        }
        if (!kept(prefix + "enabled", older, syncRevision)) {
            figure.enabled = after.enabled();
        }
        if (!kept(prefix + "rarity", older, syncRevision)) {
            figure.rarity = after.rarity();
        }
        if (!kept(prefix + "variants", older, syncRevision)) {
            figure.variants = draftsOf(after.variants());
        }
        if (!kept(prefix + "box_face", older, syncRevision)) {
            figure.boxFace = after.boxFace();
        }
    }

    // ---- Putting changes back

    /**
     * Drops every change: the draft holds the record it compares against again, as a published
     * collection's changes are discarded. It is no undo step.
     */
    public void discardPending() {
        takeAll(base);
        lastKey = null;
    }

    /** Puts the name, author, colour, box and logo back as the server has them. It is no undo step. */
    public void revertIdentity() {
        name = base.name();
        author = base.author();
        red = base.red();
        green = base.green();
        blue = base.blue();
        takeBox(base);
        logo = FileRef.ofNullable(base.logo());
        if (logo == null) {
            logoLayout = null;
        }
    }

    /** Puts the author link, logo placement, plush flag and rarity weights back as the server has them. */
    public void revertExtras() {
        authorUrl = base.authorUrl() != null ? base.authorUrl() : "";
        logoLayout = base.logoLayout();
        plush = base.plush();
        rarityWeights = base.rarityWeights();
    }

    /**
     * Puts whether the collection's draws may come out holographic back as the server has it. It is
     * no undo step.
     */
    public void revertHolographic() {
        holographic = base.holographic();
    }

    /**
     * Puts the figures back in the order the server has them, figures it does not have yet
     * after them in the draft's order. It is no undo step.
     */
    public void revertOrder() {
        Map<String, Integer> order = new HashMap<>();
        List<WorldCollectionRecord.Figure> saved = base.figures();
        for (int i = 0; i < saved.size(); i++) {
            order.put(saved.get(i).id(), i);
        }
        // A stable sort, so the new figures keep their own order
        figures.sort(Comparator.comparingInt(figure -> order.getOrDefault(figure.id(), Integer.MAX_VALUE)));
    }

    /** Puts the collection back in or out of the machines as the server has it. It is no undo step. */
    public void revertArchived() {
        archived = base.archived();
    }

    /**
     * Puts one figure back as the server has it, in its place, or takes it out of the draft when
     * the server does not have it. It is no undo step.
     */
    public void revertFigure(String figureId) {
        FigureDraft figure = figure(figureId);
        WorldCollectionRecord.Figure saved = base.figure(figureId).orElse(null);
        if (figure == null) {
            if (saved != null) {
                figures.add(FigureDraft.of(this, saved));
            }
            return;
        }
        int index = figures.indexOf(figure);
        if (saved == null) {
            figures.remove(index);
        } else {
            figures.set(index, FigureDraft.of(this, saved));
        }
    }

    // The edits carry these texts with a fixed maximum, and the editor's fields stop there too
    private static void checkLength(String text, int max) {
        if (text.length() > max) {
            throw new IllegalArgumentException("Longer than " + max + " characters");
        }
    }

    // A local ref of the same sha keeps its bytes, in case the server loses the file again
    private static FileRef keepLocal(@Nullable FileRef current, String sha) {
        return current != null && current.sha().equals(sha) ? current : FileRef.stored(sha);
    }
}
