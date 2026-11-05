package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * What the creator says about one World collection as the server last synced it: the state its
 * badge shows, with the badge's text, colours and tooltip; the colour of its band; how many
 * figures it has, how many of them are in the draw and how many are retired; how many of the
 * files it names the server lost; and how much of a collection's file space it uses.
 *
 * <p>The state follows one order of priority wherever a collection is shown: archived, then
 * missing files, then draft, then published. A collection is counted once, from its record and
 * the client's copy of the server's file list; a sync brings a new record, and with it a new
 * status.
 */
final class CollectionStatus {
    /** The state a collection's badge shows, in its order of priority. */
    enum State {
        ARCHIVED, MISSING_FILES, DRAFT, PUBLISHED
    }

    private final WorldCollectionRecord record;
    private final int figureCount;
    private final int inDraw;
    private final int missingFiles;
    private final long bytes;
    // Every figure's id, the ones in the draw first, as the machine hands them out
    private final List<String> figureOrder;

    private CollectionStatus(WorldCollectionRecord record) {
        this.record = record;
        List<String> order = new ArrayList<>();
        List<String> retiredIds = new ArrayList<>();
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            if (figure.enabled()) {
                order.add(figure.id());
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

    // Texts

    /** How much of a collection's file space it uses: "{@code <used>} of 16 MiB". */
    Component size() {
        int usedKiB = (int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024);
        return UiQuotaMeter.text(usedKiB, (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024));
    }

    /**
     * "8 figures · 7 in the draw · 1 retired", without the retired part if asked or when none is,
     * or "9 figures · archived".
     */
    Component stats(boolean withRetired) {
        Component figures = figureCount == 1 ? tr("card.figure_one") : tr("card.figures", figureCount);
        if (record.archived()) {
            return tr("card.joined", figures, tr("card.archived"));
        }
        Component line = tr("card.joined", figures, tr("card.in_draw", inDraw));
        int retired = retired();
        return retired > 0 && withRetired ? tr("card.joined", line, tr("card.retired", retired)) : line;
    }

    // The badge

    /** The badge's text: ARCHIVED, the number of files missing, DRAFT or PUBLISHED. */
    Component badge() {
        return switch (state()) {
            case ARCHIVED -> tr("card.badge.archived");
            case MISSING_FILES -> missingFiles == 1 ? tr("card.badge.missing_one")
                    : tr("card.badge.missing", missingFiles);
            case DRAFT -> tr("card.badge.draft");
            case PUBLISHED -> tr("card.badge.published");
        };
    }

    /** What the badge means, for its tooltip; a published collection's badge needs none. */
    @Nullable
    Component badgeTooltip() {
        return switch (state()) {
            case ARCHIVED -> tr("card.badge.archived_tip");
            case MISSING_FILES -> tr("card.badge.missing_tip");
            case DRAFT -> tr("card.badge.draft_tip");
            case PUBLISHED -> null;
        };
    }

    int badgeBackground() {
        return switch (state()) {
            case ARCHIVED -> Theme.BADGE_ARCHIVED_BG;
            case MISSING_FILES -> Theme.BADGE_MISSING_BG;
            case DRAFT -> Theme.BADGE_DRAFT_BG;
            case PUBLISHED -> Theme.BADGE_PUBLISHED_BG;
        };
    }

    int badgeForeground() {
        return switch (state()) {
            case ARCHIVED -> Theme.BADGE_ARCHIVED_FG;
            case MISSING_FILES -> Theme.BADGE_MISSING_FG;
            case DRAFT -> Theme.BADGE_DRAFT_FG;
            case PUBLISHED -> Theme.BADGE_PUBLISHED_FG;
        };
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
