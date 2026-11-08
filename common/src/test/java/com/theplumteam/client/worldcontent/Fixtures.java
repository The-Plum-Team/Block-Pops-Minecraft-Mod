package com.theplumteam.client.worldcontent;

import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Figure;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The files, records and plans the creator tests are made of. */
final class Fixtures {
    /** The collection every creator test edits. */
    static final String COLLECTION = "w-col";

    private Fixtures() {
    }

    /** A checked 64×64 skin picked on this computer, whose bytes and sha the tag makes. */
    static FilePicker.LocalFile skin(String tag) {
        return png(tag, 64, 64);
    }

    /** A checked 32×32 logo picked on this computer. */
    static FilePicker.LocalFile logo(String tag) {
        return png(tag, 32, 32);
    }

    static FilePicker.LocalFile png(String tag, int width, int height) {
        byte[] bytes = ("png " + tag).getBytes(StandardCharsets.UTF_8);
        return new FilePicker.LocalFile(tag + ".png", BlobType.PNG, bytes, Sha256.of(bytes),
                BlobInfo.png(bytes.length, width, height));
    }

    /** A skin figure as the server stores it; its file is the {@link #skin} of its id. */
    static Figure storedSkin(String id, String name) {
        return new Figure(id, name, Kind.SKIN, skin(id).sha(), null, null, 1.0f, true);
    }

    /** A draft collection as the server sends it, at revision 1, with these figures. */
    static WorldCollectionRecord draftRecord(Figure... figures) {
        return WorldCollectionRecord.created(COLLECTION, "Col", true).withFigures(List.of(figures)).withRevision(1);
    }

    /** A published collection as the server sends it, at revision 1, with these figures. */
    static WorldCollectionRecord publishedRecord(Figure... figures) {
        return draftRecord(figures).withDraft(false);
    }

    /** The record with another name, as a SetCollection leaves it. */
    static WorldCollectionRecord named(WorldCollectionRecord record, String name) {
        return record.withIdentity(name, record.author(), record.red(), record.green(), record.blue(),
                record.boxTexture(), record.boxColor(), record.logo());
    }

    /** The file table a sync of these records carries: each stored figure's {@link #skin}. */
    static Map<String, BlobInfo> blobsOf(WorldCollectionRecord... records) {
        Map<String, BlobInfo> blobs = new HashMap<>();
        for (WorldCollectionRecord record : records) {
            for (Figure figure : record.figures()) {
                blobs.put(figure.texture(), skin(figure.id()).info());
            }
        }
        return blobs;
    }

    /** The edits of a submission, a batch's one by one. */
    static List<WorldCollectionEdit> edits(CreatorSave.Submission submission) {
        return submission.edit() instanceof WorldCollectionEdit.Batch batch ? batch.edits()
                : List.of(submission.edit());
    }

    /** Every edit of a plan, in the order it sends them. */
    static List<WorldCollectionEdit> edits(CreatorSave.Plan plan) {
        List<WorldCollectionEdit> all = new ArrayList<>();
        for (CreatorSave.Submission submission : plan.submissions()) {
            all.addAll(edits(submission));
        }
        return all;
    }

    /** The unit keys of each submission of a plan. */
    static List<List<String>> unitKeys(CreatorSave.Plan plan) {
        List<List<String>> keys = new ArrayList<>();
        for (CreatorSave.Submission submission : plan.submissions()) {
            List<String> units = new ArrayList<>();
            for (CreatorSave.UnitInfo unit : submission.units()) {
                units.add(unit.key());
            }
            keys.add(units);
        }
        return keys;
    }

    /** The edits of a type in a list, in order. */
    static <T extends WorldCollectionEdit> List<T> ofType(List<WorldCollectionEdit> edits, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (WorldCollectionEdit edit : edits) {
            if (type.isInstance(edit)) {
                found.add(type.cast(edit));
            }
        }
        return found;
    }

    /** The unit of a plan with the key. */
    static CreatorSave.UnitInfo unit(CreatorSave.Plan plan, String key) {
        for (CreatorSave.Submission submission : plan.submissions()) {
            for (CreatorSave.UnitInfo unit : submission.units()) {
                if (unit.key().equals(key)) {
                    return unit;
                }
            }
        }
        throw new AssertionError("No unit " + key + " in " + unitKeys(plan));
    }
}
