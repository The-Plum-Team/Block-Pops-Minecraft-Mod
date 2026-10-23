package com.theplumteam.client.worldcontent;

import com.theplumteam.client.gui.creator.ui.FileChecks;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the bulk add would make of a set of files: one row per figure, before anything is added
 * to the draft.
 *
 * <p>Files are grouped by their name without its ending. With pairing on, a model, a PNG and
 * optional poses of the same name make one 3D model figure, and a PNG alone makes a skin
 * figure; with pairing off, every file is a row of its own, so only skins can be made. Files
 * that make no figure, and files the picker refused, are rows that say why and are never added.
 * Adding a file with the name of one already here replaces it, as when a fixed file is dropped
 * again; two different files with one name in the same drop are an error instead.
 *
 * <p>Each row starts named after its files, with or without the numbers in them, and at the
 * default size, and can be renamed, resized and left out; a row the operator changed keeps its
 * change when the options change. {@link #refresh} works out each row's id, unique among the
 * draft's figures and the rows before it, and its state: ready, a warning when the id its name
 * makes is taken and another is used, or an error that skips it, such as a file that breaks a
 * rule, an empty name, or no room left under the figure cap.
 *
 * Client thread only.
 */
public final class BulkPlan {
    private static final String KEY = "blockpops.world_creator.";

    /** Where a row's name comes from. */
    public enum NameMode {
        /** The file's name: {@code krilin_base.png} is "Krilin Base". */
        FILE_NAME,
        /** The file's name without its numbers: {@code goku_02.png} is "Goku". */
        WITHOUT_NUMBERS
    }

    /** Whether a row is added. */
    public enum State {
        READY, WARNING, ERROR, LEFT_OUT
    }

    /** One figure the files make, or a file that makes none. */
    public static final class Row {
        private final String key;
        private final int serial;
        private final String stem;
        @Nullable
        private FilePicker.LocalFile png;
        @Nullable
        private FilePicker.LocalFile geo;
        @Nullable
        private FilePicker.LocalFile animation;
        private boolean twice;
        @Nullable
        private String refusedName;
        @Nullable
        private Component refusal;

        private String name = "";
        private boolean nameEdited;
        private float scale = 1.0f;
        private boolean scaleEdited;
        private boolean included = true;

        // Worked out by refresh()
        @Nullable
        private Kind kind;
        @Nullable
        private Component fileProblem;
        private String id = "";
        private State state = State.ERROR;
        private Component status = Component.empty();

        private Row(String key, int serial, String stem) {
            this.key = key;
            this.serial = serial;
            this.stem = stem;
        }

        /** Stays the same for as long as the row does, as its preview's id does. */
        public int serial() {
            return serial;
        }

        public String name() {
            return name;
        }

        public float scale() {
            return scale;
        }

        public boolean included() {
            return included;
        }

        /** The kind of figure the files make, or null when they make none. */
        @Nullable
        public Kind kind() {
            return kind;
        }

        /** Whether its files make a figure, so its name, size and box can change. */
        public boolean usable() {
            return kind != null;
        }

        /** The id the figure gets when added; empty for a row whose files make none. */
        public String id() {
            return id;
        }

        public State state() {
            return state;
        }

        /** Its state in words, with its mark: ✔, ⚠, ✖ or ○. */
        public Component status() {
            return status;
        }

        /** Whether it is added with the others. */
        public boolean adds() {
            return state == State.READY || state == State.WARNING;
        }

        /** Its files' names, joined as the table lists them. */
        public String files() {
            if (refusedName != null) {
                return refusedName;
            }
            List<String> names = new ArrayList<>();
            for (FilePicker.LocalFile file : files(geo, png, animation)) {
                names.add(file.name());
            }
            return String.join(" + ", names);
        }

        /** Its files: the model, the texture or skin, and the poses, each when there is one. */
        public List<FilePicker.LocalFile> localFiles() {
            return files(geo, png, animation);
        }

        private static List<FilePicker.LocalFile> files(@Nullable FilePicker.LocalFile... files) {
            List<FilePicker.LocalFile> list = new ArrayList<>();
            for (FilePicker.LocalFile file : files) {
                if (file != null) {
                    list.add(file);
                }
            }
            return list;
        }

        @Nullable
        public FilePicker.LocalFile texture() {
            return png;
        }

        @Nullable
        public FilePicker.LocalFile model() {
            return kind == Kind.MODEL ? geo : null;
        }

        @Nullable
        public FilePicker.LocalFile animation() {
            return kind == Kind.MODEL ? animation : null;
        }

        /** The figure as a record, under another id, for a preview; null when the files make none. */
        @Nullable
        public WorldCollectionRecord.Figure toFigure(String figureId) {
            if (kind == null || png == null) {
                return null;
            }
            FilePicker.LocalFile model = model();
            FilePicker.LocalFile poses = animation();
            // A name the server would refuse cannot keep a preview from showing
            String shown = !name.isBlank() && WorldCollectionRecord.isPlainText(name) ? name : figureId;
            return new WorldCollectionRecord.Figure(figureId, shown, kind, png.sha(),
                    model != null ? model.sha() : null, poses != null ? poses.sha() : null, scale, true);
        }
    }

    private NameMode nameMode = NameMode.FILE_NAME;
    private float defaultScale = 1.0f;
    private boolean pair = true;
    // Every accepted file by its lowercase name, the newest last, and every refusal the same way
    private final Map<String, FilePicker.LocalFile> files = new LinkedHashMap<>();
    private final Map<String, FilePicker.PickResult> refused = new LinkedHashMap<>();
    // Names that came twice in one drop with different contents
    private final Set<String> twice = new HashSet<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private int nextSerial;

    public float defaultScale() {
        return defaultScale;
    }

    public boolean pairs() {
        return pair;
    }

    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** The rows that would be added, in order. */
    public List<Row> toAdd() {
        List<Row> adding = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.adds()) {
                adding.add(row);
            }
        }
        return adding;
    }

    /** How many rows are in a state. */
    public int count(State state) {
        int count = 0;
        for (Row row : rows.values()) {
            if (row.state == state) {
                count++;
            }
        }
        return count;
    }

    // ---- Changing the files

    /**
     * Adds the files of a drop or a pick. Refusals become rows that say why; a file named
     * like one already here replaces it. Answers how many files were left out because the
     * list already holds {@link FilePicker#MAX_FILES}, as many as one drop reads.
     */
    public int add(List<FilePicker.PickResult> results) {
        Map<String, String> shaInThisDrop = new LinkedHashMap<>();
        int leftOut = 0;
        for (FilePicker.PickResult result : results) {
            FilePicker.LocalFile file = result.file();
            boolean refusal = result.kind() == FilePicker.PickResult.Kind.REFUSED;
            if (!refusal && (result.kind() != FilePicker.PickResult.Kind.ACCEPTED || file == null)) {
                continue;
            }
            String lower = (refusal ? result.name() : file.name()).toLowerCase(Locale.ROOT);
            // Every file stays in memory until the card closes, so drop after drop cannot grow it without end
            if (!files.containsKey(lower) && !refused.containsKey(lower)
                    && files.size() + refused.size() >= FilePicker.MAX_FILES) {
                leftOut++;
                continue;
            }
            if (refusal) {
                files.remove(lower);
                twice.remove(lower);
                refused.put(lower, result);
                continue;
            }
            String before = shaInThisDrop.put(lower, file.sha());
            if (before != null && !before.equals(file.sha())) {
                twice.add(lower);
            } else if (before == null) {
                twice.remove(lower);
            }
            refused.remove(lower);
            // Removed first, so the newest file of a name goes last
            files.remove(lower);
            files.put(lower, file);
        }
        regroup();
        return leftOut;
    }

    /** Takes a row and its files away. */
    public void remove(Row row) {
        if (row.refusedName != null) {
            refused.remove(row.refusedName.toLowerCase(Locale.ROOT));
        }
        for (FilePicker.LocalFile file : row.localFiles()) {
            String lower = file.name().toLowerCase(Locale.ROOT);
            files.remove(lower);
            twice.remove(lower);
        }
        regroup();
    }

    public void setPair(boolean next) {
        if (next != pair) {
            pair = next;
            regroup();
        }
    }

    public void setNameMode(NameMode next) {
        nameMode = next;
        for (Row row : rows.values()) {
            if (!row.nameEdited) {
                row.name = nameFor(row.stem);
            }
        }
    }

    public void setDefaultScale(float next) {
        if (WorldCollectionRecord.scaleIndex(next) < 0) {
            throw new IllegalArgumentException("Not a figure size: " + next);
        }
        defaultScale = next;
        for (Row row : rows.values()) {
            if (!row.scaleEdited) {
                row.scale = next;
            }
        }
    }

    // ---- Changing a row

    /** Renames a row; the options no longer rename it. */
    public void rename(Row row, String name) {
        row.name = name.length() > WorldContentLimits.MAX_NAME_LENGTH
                ? name.substring(0, WorldContentLimits.MAX_NAME_LENGTH) : name;
        row.nameEdited = true;
    }

    /** Resizes a row; the default size no longer resizes it. */
    public void resize(Row row, float scale) {
        if (WorldCollectionRecord.scaleIndex(scale) < 0) {
            throw new IllegalArgumentException("Not a figure size: " + scale);
        }
        row.scale = scale;
        row.scaleEdited = true;
    }

    /** Leaves a row out, or puts it back; a row whose files make no figure is never added. */
    public void include(Row row, boolean included) {
        row.included = included;
    }

    // ---- Grouping

    // Makes the rows again from the files, keeping what was changed on a row that stays
    private void regroup() {
        Map<String, Row> before = new LinkedHashMap<>(rows);
        rows.clear();
        for (FilePicker.LocalFile file : files.values()) {
            String stem = stem(file.name());
            String key = pair ? "stem:" + stem.toLowerCase(Locale.ROOT) : "file:" + file.name().toLowerCase(Locale.ROOT);
            Row row = rows.get(key);
            if (row == null) {
                row = keep(before, key, stem);
                rows.put(key, row);
            }
            switch (file.type()) {
                case PNG -> row.png = file;
                case GEO -> row.geo = file;
                case ANIMATION -> row.animation = file;
            }
            row.twice |= twice.contains(file.name().toLowerCase(Locale.ROOT));
        }
        for (FilePicker.PickResult result : refused.values()) {
            String key = "refused:" + result.name().toLowerCase(Locale.ROOT);
            Row row = keep(before, key, stem(result.name()));
            row.refusedName = result.name();
            row.refusal = Component.literal(result.message());
            rows.put(key, row);
        }
        for (Row row : rows.values()) {
            row.fileProblem = fileProblem(row);
            row.kind = row.fileProblem == null ? (row.geo != null ? Kind.MODEL : Kind.SKIN) : null;
        }
    }

    // The row a key had, with its files cleared, or a new one
    private Row keep(Map<String, Row> before, String key, String stem) {
        Row old = before.get(key);
        Row row = new Row(key, old != null ? old.serial : nextSerial++, stem);
        if (old != null) {
            row.name = old.name;
            row.nameEdited = old.nameEdited;
            row.scale = old.scale;
            row.scaleEdited = old.scaleEdited;
            row.included = old.included;
        } else {
            row.name = nameFor(stem);
            row.scale = defaultScale;
        }
        return row;
    }

    /** Why a row's files make no figure, or null when they make one. */
    @Nullable
    private Component fileProblem(Row row) {
        if (row.refusal != null) {
            return row.refusal;
        }
        if (row.twice) {
            return tr("bulk.problem.twice");
        }
        if (row.geo != null) {
            if (!pair) {
                return tr("bulk.problem.unpaired_model");
            }
            if (row.png == null) {
                return tr("bulk.problem.no_texture");
            }
            Component failure = firstFailure(FileChecks.geo(row.geo.info()));
            if (failure == null) {
                failure = firstFailure(FileChecks.modelTexture(row.png.info(), row.geo.info()));
            }
            if (failure == null && row.animation != null) {
                failure = firstFailure(FileChecks.animation(row.animation.info()));
            }
            return failure;
        }
        if (row.animation != null) {
            return tr(pair ? "bulk.problem.no_model" : "bulk.problem.unpaired_animation");
        }
        if (row.png == null) {
            return tr("bulk.problem.no_texture");
        }
        return firstFailure(FileChecks.skin(row.png.info()));
    }

    @Nullable
    private static Component firstFailure(List<FileChecks.Check> checks) {
        for (FileChecks.Check check : checks) {
            if (!check.ok()) {
                return check.text();
            }
        }
        return null;
    }

    // ---- Working out the ids and states

    /**
     * Works out every row's id and state against the draft: ids unique among the draft's
     * figures and the rows added before, in order, and no more rows added than the figure cap
     * leaves room for.
     */
    public void refresh(CreatorDraft draft) {
        Set<String> taken = new HashSet<>();
        Map<String, String> namesById = new LinkedHashMap<>();
        for (CreatorDraft.FigureDraft figure : draft.figures()) {
            namesById.put(figure.id(), figure.name());
        }
        int room = Math.max(0, WorldContentLimits.MAX_FIGURES - draft.figures().size());
        for (Row row : rows.values()) {
            row.id = "";
            if (row.kind == null) {
                row.state = State.ERROR;
                row.status = tr("bulk.status.skipped", row.fileProblem);
                continue;
            }
            // The id the name makes, then the one it gets when that is taken
            String wanted = Slugs.unique(Slugs.figure(row.name), id -> false);
            row.id = Slugs.unique(wanted, id -> draft.isFigureIdTaken(id) || taken.contains(id));
            if (row.name.isBlank()) {
                row.state = State.ERROR;
                row.status = tr("bulk.status.skipped", tr("bulk.problem.no_name"));
                continue;
            }
            if (!WorldCollectionRecord.isPlainText(row.name)) {
                row.state = State.ERROR;
                row.status = tr("bulk.status.skipped", tr("name_rule"));
                continue;
            }
            if (!row.included) {
                row.state = State.LEFT_OUT;
                row.status = tr("bulk.status.left_out");
                continue;
            }
            if (room == 0) {
                row.state = State.ERROR;
                row.status = tr("bulk.status.skipped", tr("bulk.problem.no_room", WorldContentLimits.MAX_FIGURES));
                continue;
            }
            room--;
            taken.add(row.id);
            if (!row.id.equals(wanted)) {
                String holder = namesById.get(wanted);
                if (holder == null) {
                    holder = draft.base().figure(wanted).map(WorldCollectionRecord.Figure::name).orElse(row.name);
                }
                row.state = State.WARNING;
                row.status = tr("bulk.status.renamed", holder, row.id);
            } else {
                row.state = State.READY;
                row.status = readyStatus(row);
            }
            namesById.putIfAbsent(row.id, row.name);
        }
    }

    private static Component readyStatus(Row row) {
        if (row.kind != Kind.MODEL) {
            return tr("bulk.status.ready");
        }
        FilePicker.LocalFile poses = row.animation;
        if (poses == null) {
            return tr("bulk.status.shared_poses");
        }
        BlobInfo info = poses.info();
        return tr(info.hasPose(BlobInfo.POSE_SIT) ? "bulk.status.poses" : "bulk.status.stand_only");
    }

    // ---- Names

    private String nameFor(String stem) {
        if (nameMode == NameMode.WITHOUT_NUMBERS) {
            String plain = stem.replaceAll("[0-9]+", " ").strip();
            if (!plain.replaceAll("[\\s_.\\-]+", "").isEmpty()) {
                return nameOf(plain);
            }
        }
        return nameOf(stem);
    }

    /** A figure name from a file name: "krilin_base" becomes "Krilin Base". */
    private static String nameOf(String stem) {
        StringBuilder name = new StringBuilder();
        for (String word : stem.split("[\\s_.\\-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
            name.append(word.substring(Character.charCount(word.codePointAt(0))));
        }
        String text = name.toString();
        if (text.length() > WorldContentLimits.MAX_NAME_LENGTH) {
            text = text.substring(0, WorldContentLimits.MAX_NAME_LENGTH).strip();
        }
        return text.isBlank() || !WorldCollectionRecord.isPlainText(text) ? tr("figures.new_name").getString() : text;
    }

    /** A file's name without the ending of its type. */
    private static String stem(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ending : List.of(".geo.json", ".animation.json", ".json", ".png")) {
            if (lower.endsWith(ending) && lower.length() > ending.length()) {
                return fileName.substring(0, fileName.length() - ending.length());
            }
        }
        return fileName;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }
}
