package com.theplumteam.client.gui.creator;

import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiSideRow;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * What the creator says about one World collection as the server last synced it: the state its
 * badge and its sidebar row show, with their texts, colours and tooltips; the line under its
 * showcase that says where players find it; the colour of its showcase; how many figures it has,
 * how many of them are in the draw, of each rarity, and how many are retired; how many of the
 * files it names the server lost; and how much of a collection's file space it uses.
 *
 * <p>The state follows one order of priority wherever a collection is shown: archived, then
 * missing files, then draft, then published. A collection is counted once, from its record and
 * the client's copy of the server's file list; a sync brings a new record, and with it a new
 * status. Whether the server hides the collection from its machines is read each time it is
 * asked, since the server says so in a packet of its own.
 */
final class CollectionStatus {
    /** The state a collection's badge shows, in its order of priority. */
    enum State {
        ARCHIVED, MISSING_FILES, DRAFT, PUBLISHED
    }

    /** The line under a showcase: a dot in a colour, and what it says. */
    record StateLine(Component text, int dotColour, int textColour) {
    }

    // The rarities' colours, as the machine's draw result shows them
    private static final int COMMON = 0xFFB0B0B0;
    private static final int RARE = 0xFF6FA0FF;
    private static final int EPIC = 0xFFC58BE8;
    private static final int ARCHIVED_DOT = 0xFF8A8A8A;
    private static final long MIB = 1024L * 1024L;

    private final WorldCollectionRecord record;
    private final int figureCount;
    private final int inDraw;
    private final int missingFiles;
    private final long bytes;
    // Every figure's id, the ones in the draw first, as the machine hands them out
    private final List<String> figureOrder;
    // The figures in the draw of each rarity, in the order of Rarity
    private final int[] rarityCounts = new int[Rarity.values().length];

    private CollectionStatus(WorldCollectionRecord record) {
        this.record = record;
        List<String> order = new ArrayList<>();
        List<String> retiredIds = new ArrayList<>();
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            if (figure.enabled()) {
                order.add(figure.id());
                this.rarityCounts[figure.rarity().ordinal()]++;
            } else {
                retiredIds.add(figure.id());
            }
        }
        this.figureCount = record.figures().size();
        this.inDraw = order.size();
        order.addAll(retiredIds);
        this.figureOrder = List.copyOf(order);
        long total = 0;
        int missing = 0;
        for (String sha : record.referencedShas()) {
            BlobInfo info = ClientWorldCollections.blob(sha);
            if (info != null) {
                total += info.size();
            }
            if (ClientWorldCollections.isMissingOnServer(sha)) {
                missing++;
            }
        }
        this.bytes = total;
        this.missingFiles = missing;
    }

    /** The status of a collection as this client was sent it. */
    static CollectionStatus of(WorldCollectionRecord record) {
        return new CollectionStatus(record);
    }

    WorldCollectionRecord record() {
        return record;
    }

    /** The state the badge shows: the first of archived, missing files and draft that holds, else published. */
    State state() {
        if (record.archived()) {
            return State.ARCHIVED;
        }
        if (missingFiles > 0) {
            return State.MISSING_FILES;
        }
        return record.draft() ? State.DRAFT : State.PUBLISHED;
    }

    /** Whether the server hides the collection from its machines, as it last said. */
    boolean hidden() {
        return ClientHiddenCollections.isHidden(record.id());
    }

    // Counts

    /** Every figure of the collection, retired ones included. */
    int figureCount() {
        return figureCount;
    }

    /** The figures a draw can give, which are the enabled ones. */
    int inDraw() {
        return inDraw;
    }

    /** The figures taken out of the draw, which players who have them keep. */
    int retired() {
        return figureCount - inDraw;
    }

    /** How many of the files the collection names the server lost. */
    int missingFiles() {
        return missingFiles;
    }

    /** Every figure's id, the ones in the draw first, each group in the collection's order. */
    List<String> figureOrder() {
        return figureOrder;
    }

    /** The figure a box of the collection holds, as the machine hands one out: the first in the draw, or null. */
    @Nullable
    String boxFigureId() {
        return inDraw > 0 ? figureOrder.get(0) : null;
    }

    // Texts

    /**
     * How much of a collection's file space ({@link WorldContentLimits#MAX_COLLECTION_BYTES},
     * 16 MiB) it uses: "3.2 of 16 MiB", in tenths of a MiB rounded up, so a collection that
     * stores anything never reads as 0.0.
     */
    Component size() {
        long tenths = (bytes * 10 + MIB - 1) / MIB;
        return tr("show.size", String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10));
    }

    /**
     * The line under a showcase's title: "12 figures · by Neb", the slug in place of an author
     * the collection has none of, and " · 1 retired" after it when any figure is.
     */
    Component subtitle() {
        boolean one = figureCount == 1;
        Component line = record.author().isBlank()
                ? one ? tr("show.subtitle_anon_one", record.slug()) : tr("show.subtitle_anon", figureCount, record.slug())
                : one ? tr("show.subtitle_one", record.author()) : tr("show.subtitle", figureCount, record.author());
        int retired = retired();
        if (retired == 0) {
            return line;
        }
        Component retiredText = retired == 1 ? tr("show.retired_one") : tr("show.retired", retired);
        return Component.empty().append(line).append(" ").append(retiredText);
    }

    /** The figures in the draw of a rarity. */
    int inDraw(Rarity rarity) {
        return rarityCounts[rarity.ordinal()];
    }

    /** How many figures in the draw a rarity has, in words: "9 common", "1 rare". */
    static Component rarityText(Rarity rarity, int count) {
        String key = "show.rarity." + rarity.serializedName();
        return count == 1 ? tr(key + "_one") : tr(key, count);
    }

    /** The colour a rarity's dot is drawn in. */
    static int rarityColour(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> COMMON;
            case RARE -> RARE;
            case EPIC -> EPIC;
            case LEGENDARY -> Theme.GOLD;
        };
    }

    /**
     * The rarities of the figures in the draw as one sentence, "9 common · 1 rare", leaving out
     * those with none; empty when no figure is in the draw.
     */
    Component raritySummary() {
        List<Component> parts = new ArrayList<>();
        for (Rarity rarity : Rarity.values()) {
            int count = inDraw(rarity);
            if (count > 0) {
                parts.add(rarityText(rarity, count));
            }
        }
        Component joined = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined = Component.empty().append(joined).append(" · ");
            }
            joined = Component.empty().append(joined).append(parts.get(i));
        }
        return joined;
    }

    /**
     * Where players find the collection, under its showcase: in every claw machine (or every
     * plush machine for a plush collection) in green, a draft in amber, an archived one in grey,
     * and in red one the server hides or whose files it lost.
     */
    StateLine stateLine() {
        return switch (state()) {
            case ARCHIVED -> new StateLine(tr("show.state.archived"), ARCHIVED_DOT, Theme.FAINT);
            case MISSING_FILES -> new StateLine(tr("card.badge.missing_tip"), Theme.RED, Theme.RED);
            case DRAFT -> new StateLine(tr("show.state.draft"), Theme.AMBER, Theme.FAINT);
            case PUBLISHED -> hidden() ? new StateLine(tr("show.state.hidden"), Theme.RED, Theme.RED)
                    : new StateLine(tr(record.plush() ? "show.state.gacha" : "show.state.claw"), Theme.GREEN,
                    Theme.FAINT);
        };
    }

    // The badge

    /**
     * The badge after a showcase's title: ARCHIVED, the number of files missing or DRAFT; a
     * published collection has none, since its state line says where it is.
     */
    @Nullable
    Component badge() {
        return switch (state()) {
            case ARCHIVED -> tr("card.badge.archived");
            case MISSING_FILES -> missingFiles == 1 ? tr("card.badge.missing_one")
                    : tr("card.badge.missing", missingFiles);
            case DRAFT -> tr("card.badge.draft");
            case PUBLISHED -> null;
        };
    }

    /** What the badge means, for its tooltip; a published collection has no badge. */
    @Nullable
    Component badgeTooltip() {
        return switch (state()) {
            case ARCHIVED -> tr("card.badge.archived_tip");
            case MISSING_FILES -> tr("card.badge.missing_tip");
            case DRAFT -> tr("card.badge.draft_tip");
            case PUBLISHED -> null;
        };
    }

    // The sidebar row

    /**
     * Shows the state at the right end of the collection's sidebar row, in the badge's order of
     * priority: a red "⚠ 2" when the server lost files, a draft's gold dot and word, or else the
     * number of figures, which says in its tooltip when the server hides the collection.
     *
     * @param selected whether the row is the selected one, on whose slate a count is brighter
     */
    void showOnRow(UiSideRow row, boolean selected) {
        if (missingFiles > 0) {
            row.count(Component.literal("⚠ " + missingFiles), Theme.RED, tr("card.badge.missing_tip"));
        } else if (record.draft() && !record.archived()) {
            row.status(tr("side.status.draft"), Theme.GOLD, tr("card.badge.draft_tip"));
        } else if (hidden()) {
            row.count(Component.literal(String.valueOf(figureCount)), selected ? Theme.TEXT_SOFT : Theme.FAINT,
                    tr("home.reason.hidden_machine"));
        } else {
            row.count(Component.literal(String.valueOf(figureCount)));
        }
    }

    /** The collection's background colour, opaque, or its grey for an archived collection. */
    int bandColour() {
        int red = record.red();
        int green = record.green();
        int blue = record.blue();
        if (record.archived()) {
            int grey = (red * 30 + green * 59 + blue * 11) / 100;
            red = grey;
            green = grey;
            blue = grey;
        }
        return 0xFF000000 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
    }
}
