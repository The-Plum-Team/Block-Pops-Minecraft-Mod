package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.BlobType;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The checks a creator screen shows beside a file, one short chip each, such as "64×64 ✔" or
 * "Texture is 64×32 but the model expects a square. Export it square. ✖". They are the rules
 * the server applies ({@code PngValidator}'s roles and the model's texture proportions), worked
 * out from a file's {@link BlobInfo} alone, so a screen can say what is wrong with a file before
 * anything is sent. A file read for one slot has already passed its role's checks; a file read in
 * bulk has only passed the checks of its type, and these say which slot it does not suit.
 */
public final class FileChecks {
    /** How a check came out: passed, worth knowing, or failed. */
    public enum Mark {
        OK("✔", 0xFF55FF55, 0xFFB9FFB0),
        NOTE("○", 0xFF6A6A6A, 0xFFCFCFCF),
        FAIL("✖", 0xFFFF5555, 0xFFFFB3B3);

        private final String symbol;
        private final int border;
        private final int text;

        Mark(String symbol, int border, int text) {
            this.symbol = symbol;
            this.border = border;
            this.text = text;
        }

        public String symbol() {
            return symbol;
        }

        public int border() {
            return border;
        }

        public int text() {
            return text;
        }
    }

    /** One check: its outcome and a few words about it. */
    public record Check(Mark mark, Component text) {
        /** Whether the check lets the file be used; a note does. */
        public boolean ok() {
            return mark != Mark.FAIL;
        }

        /** The text with the outcome's symbol after it, as a chip shows it. */
        public String label() {
            return text.getString() + " " + mark.symbol();
        }
    }

    private static final int KIB = 1024;

    private FileChecks() {
    }

    public static boolean allOk(List<Check> checks) {
        for (Check check : checks) {
            if (!check.ok()) {
                return false;
            }
        }
        return true;
    }

    /** The checks of a file for a slot; a model texture is checked without its model. */
    public static List<Check> forSlot(FilePicker.Slot slot, BlobInfo info) {
        return switch (slot) {
            case SKIN -> skin(info);
            case BOX -> box(info);
            case LOGO -> logo(info);
            case MODEL_TEXTURE -> modelTexture(info, null);
            case MODEL -> geo(info);
            case ANIMATION -> animation(info);
        };
    }

    public static List<Check> forSlot(FilePicker.Slot slot, FilePicker.LocalFile file) {
        return forSlot(slot, file.info());
    }

    /** A skin: exactly 64×64, and at most 64 KiB. */
    public static List<Check> skin(BlobInfo info) {
        return exactPng(info, WorldContentLimits.SKIN_SIZE, WorldContentLimits.SKIN_BYTES, "check.skin_size");
    }

    /** A box texture: exactly 64×64, and at most 64 KiB. */
    public static List<Check> box(BlobInfo info) {
        return exactPng(info, WorldContentLimits.BOX_SIZE, WorldContentLimits.BOX_BYTES, "check.box_size");
    }

    /** A logo: 16 to 512 pixels a side. */
    public static List<Check> logo(BlobInfo info) {
        return rangePng(info, WorldContentLimits.LOGO_MIN_SIZE, WorldContentLimits.LOGO_MAX_SIZE,
                WorldContentLimits.LOGO_BYTES, "check.logo_size");
    }

    /**
     * A 3D model's texture: 16 to 512 pixels a side, and, once the model is known, the
     * proportions its UV layout was made for.
     */
    public static List<Check> modelTexture(BlobInfo texture, @Nullable BlobInfo model) {
        List<Check> checks = rangePng(texture, WorldContentLimits.MODEL_TEXTURE_MIN_SIZE,
                WorldContentLimits.MODEL_TEXTURE_MAX_SIZE, WorldContentLimits.MODEL_TEXTURE_BYTES, "check.texture_size");
        if (texture.type() != BlobType.PNG || model == null || model.type() != BlobType.GEO) {
            return checks;
        }
        int w = texture.width();
        int h = texture.height();
        if ((long) w * model.texHeight() == (long) h * model.texWidth()) {
            checks.add(ok(CreatorScreen.tr("check.aspect_ok")));
        } else if (model.texWidth() == model.texHeight()) {
            checks.add(fail(CreatorScreen.tr("check.aspect_square", w, h)));
        } else {
            checks.add(fail(CreatorScreen.tr("check.aspect", w, h, model.texWidth(), model.texHeight())));
        }
        return checks;
    }

    /** A model: how many cubes it has, which must be some, and the texture size it declares. */
    public static List<Check> geo(BlobInfo info) {
        List<Check> checks = new ArrayList<>();
        if (info.type() != BlobType.GEO) {
            checks.add(fail(CreatorScreen.tr("check.not_model")));
            return checks;
        }
        checks.add(info.cubes() > 0
                ? ok(CreatorScreen.tr("check.cubes", info.cubes()))
                : fail(CreatorScreen.tr("check.no_cubes")));
        checks.add(ok(CreatorScreen.tr("check.model_texture", info.texWidth(), info.texHeight())));
        return checks;
    }

    /** An animation: the stand pose it must have, and the sit pose it may have. */
    public static List<Check> animation(BlobInfo info) {
        List<Check> checks = new ArrayList<>();
        if (info.type() != BlobType.ANIMATION) {
            checks.add(fail(CreatorScreen.tr("check.not_animation")));
            return checks;
        }
        checks.add(info.hasPose(BlobInfo.POSE_STAND)
                ? ok(CreatorScreen.tr("check.pose_stand"))
                : fail(CreatorScreen.tr("check.no_stand")));
        checks.add(info.hasPose(BlobInfo.POSE_SIT)
                ? ok(CreatorScreen.tr("check.pose_sit"))
                : new Check(Mark.NOTE, CreatorScreen.tr("check.no_sit")));
        return checks;
    }

    private static List<Check> exactPng(BlobInfo info, int side, int maxBytes, String sizeKey) {
        List<Check> checks = new ArrayList<>();
        if (info.type() != BlobType.PNG) {
            checks.add(fail(CreatorScreen.tr("check.not_png")));
            return checks;
        }
        checks.add(info.width() == side && info.height() == side
                ? ok(CreatorScreen.tr("check.size", info.width(), info.height()))
                : fail(CreatorScreen.tr(sizeKey, info.width(), info.height(), side)));
        checks.add(bytes(info, maxBytes));
        return checks;
    }

    private static List<Check> rangePng(BlobInfo info, int min, int max, int maxBytes, String sizeKey) {
        List<Check> checks = new ArrayList<>();
        if (info.type() != BlobType.PNG) {
            checks.add(fail(CreatorScreen.tr("check.not_png")));
            return checks;
        }
        boolean fits = info.width() >= min && info.width() <= max && info.height() >= min && info.height() <= max;
        checks.add(fits
                ? ok(CreatorScreen.tr("check.size", info.width(), info.height()))
                : fail(CreatorScreen.tr(sizeKey, info.width(), info.height(), min, max)));
        checks.add(bytes(info, maxBytes));
        return checks;
    }

    private static Check bytes(BlobInfo info, int maxBytes) {
        // Rounded up, so a file just over the limit never reads as exactly at it
        int kib = (info.size() + KIB - 1) / KIB;
        return info.size() <= maxBytes
                ? ok(CreatorScreen.tr("check.kib", kib))
                : fail(CreatorScreen.tr("check.kib_over", kib, maxBytes / KIB));
    }

    private static Check ok(Component text) {
        return new Check(Mark.OK, text);
    }

    private static Check fail(Component text) {
        return new Check(Mark.FAIL, text);
    }
}
