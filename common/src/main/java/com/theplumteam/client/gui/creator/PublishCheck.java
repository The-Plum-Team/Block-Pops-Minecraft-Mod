package com.theplumteam.client.gui.creator;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiSwatches;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * What a World collection needs before it can be published, worked out from the editor's
 * working copy ({@link CreatorDraft}) as it is now: a list of items in the order the review shows
 * them, each done, a warning, a blocker that holds publishing back, or an optional step still to
 * do. An item about something the operator can fix says where it is fixed ({@link Fix}): the
 * collection's identity and box, its figures, or one figure.
 *
 * <p>The blockers: a name, author or author link that cannot be saved ({@link #identityProblem});
 * a box chosen without its colour or image ({@link #boxProblem}); no complete figure in the draw;
 * a figure naming a file the server lost; a definition over {@link WorldContentLimits#RECORD_BYTES};
 * and files over the collection's or the server's space. Figures still missing a file are
 * warnings, since publishing leaves them out, and so are retired ones, which players who have
 * them keep. The author link is optional.
 */
final class PublishCheck {
    /** How an item stands. */
    enum Mark {
        DONE, WARNING, BLOCKER, TODO
    }

    /** Where an item's problem is fixed. */
    enum Place {
        /** The collection's name, author, link, colour and box. */
        IDENTITY,
        /** The figures, to add one. */
        FIGURES,
        /** One figure, which {@link Fix#figure()} names. */
        FIGURE
    }

    /**
     * Where an item is fixed, and the link that takes the operator there.
     *
     * @param figure the figure, for {@link Place#FIGURE}; null otherwise
     */
    record Fix(Place place, Component link, @Nullable FigureDraft figure) {
        static Fix identity(Component link) {
            return new Fix(Place.IDENTITY, link, null);
        }

        static Fix figures(Component link) {
            return new Fix(Place.FIGURES, link, null);
        }

        static Fix figure(FigureDraft figure) {
            return new Fix(Place.FIGURE, tr("publish.view"), figure);
        }
    }

    /**
     * One line of the check.
     *
     * @param fix where it is fixed, or null for an item with nothing to fix or nowhere to go
     */
    record Item(Mark mark, Component text, @Nullable Fix fix) {
        boolean blocks() {
            return this.mark == Mark.BLOCKER;
        }
    }

    // Retired figures are listed one by one up to this many, then counted
    private static final int MAX_RETIRED_ROWS = 3;

    private final List<Item> items;

    private PublishCheck(List<Item> items) {
        this.items = List.copyOf(items);
    }

    /** The check of a draft as it is now. */
    static PublishCheck of(CreatorDraft draft) {
        List<Item> items = new ArrayList<>();
        Component fix = tr("publish.fix");

        // Name, author and colour, then the box
        if (identityReady(draft)) {
            items.add(done(tr("publish.check.identity")));
        } else {
            Component problem = identityProblem(draft);
            items.add(new Item(Mark.BLOCKER, problem != null ? problem : tr("publish.check.identity"),
                    Fix.identity(fix)));
        }
        Component boxProblem = boxProblem(draft);
        if (boxProblem != null) {
            items.add(new Item(Mark.BLOCKER, boxProblem, Fix.identity(fix)));
        } else {
            items.add(done(boxText(draft)));
        }

        // The figures: some in the draw, none missing a file
        int inDraw = 0;
        FigureDraft lost = null;
        int lostCount = 0;
        FigureDraft unfinished = null;
        int unfinishedCount = 0;
        List<FigureDraft> retired = new ArrayList<>();
        for (FigureDraft figure : draft.figures()) {
            if (!figure.isComplete()) {
                unfinishedCount++;
                unfinished = unfinished != null ? unfinished : figure;
                continue;
            }
            if (lostOnServer(figure)) {
                lostCount++;
                lost = lost != null ? lost : figure;
            }
            if (figure.enabled()) {
                inDraw++;
            } else {
                retired.add(figure);
            }
        }
        if (inDraw == 0) {
            items.add(new Item(Mark.BLOCKER, tr("publish.check.no_figures"), Fix.figures(tr("publish.add"))));
        } else if (lost != null) {
            items.add(new Item(Mark.BLOCKER, lostCount == 1 ? tr("publish.check.lost_one", nameOf(lost))
                    : tr("publish.check.lost", lostCount), Fix.figure(lost)));
        } else {
            items.add(done(inDraw == 1 ? tr("publish.check.figures_one") : tr("publish.check.figures", inDraw)));
        }
        if (unfinished != null) {
            items.add(new Item(Mark.WARNING, unfinishedCount == 1
                    ? tr("publish.check.unfinished_one", nameOf(unfinished))
                    : tr("publish.check.unfinished", unfinishedCount), Fix.figure(unfinished)));
        }
        for (int i = 0; i < retired.size() && i < MAX_RETIRED_ROWS; i++) {
            FigureDraft figure = retired.get(i);
            items.add(new Item(Mark.WARNING, tr("publish.check.retired", nameOf(figure)), Fix.figure(figure)));
        }
        if (retired.size() > MAX_RETIRED_ROWS) {
            items.add(new Item(Mark.WARNING, tr("publish.check.retired_more", retired.size() - MAX_RETIRED_ROWS),
                    null));
        }

        // The optional author link
        if (draft.authorUrl().isEmpty()) {
            items.add(new Item(Mark.TODO, tr("publish.check.no_link"), Fix.identity(tr("publish.add"))));
        } else {
            items.add(done(tr("publish.check.link")));
        }

        // What it takes on the server: its definition and its files
        CreatorDraft.Forecast forecast = draft.forecast();
        int recordKiB = (forecast.recordBytes() + 1023) / 1024;
        if (forecast.recordBytes() > WorldContentLimits.RECORD_BYTES) {
            items.add(new Item(Mark.BLOCKER, tr("editor.too_big", recordKiB, WorldContentLimits.RECORD_BYTES / 1024),
                    null));
        } else {
            items.add(done(tr("publish.check.size", recordKiB, WorldContentLimits.RECORD_BYTES / 1024)));
        }
        if (forecast.budgetProblem() != null) {
            items.add(new Item(Mark.BLOCKER, Component.literal(forecast.budgetProblem()), null));
        } else {
            int usedKiB = (int) Math.min(Integer.MAX_VALUE, (forecast.collectionBytes() + 1023) / 1024);
            items.add(done(tr("publish.check.files", UiQuotaMeter.text(usedKiB,
                    (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024)))));
        }
        return new PublishCheck(items);
    }

    private static Item done(Component text) {
        return new Item(Mark.DONE, text, null);
    }

    /** Every item, in the review's order. */
    List<Item> items() {
        return this.items;
    }

    /** Whether any item is a blocker, which holds publishing back. */
    boolean blocked() {
        for (Item item : this.items) {
            if (item.blocks()) {
                return true;
            }
        }
        return false;
    }

    // The collection's own rules, which saving follows too

    /** Whether the name, author and link can be saved, whatever the box. */
    static boolean identityReady(CreatorDraft draft) {
        return !draft.name().isBlank() && WorldCollectionRecord.isPlainText(draft.name())
                && WorldCollectionRecord.isPlainText(draft.author())
                && (draft.authorUrl().isEmpty() || WorldCollectionRecord.isValidAuthorUrl(draft.authorUrl()));
    }

    /** Why the name, author, link or box cannot be saved as they are, or null when they can. */
    @Nullable
    static Component identityProblem(CreatorDraft draft) {
        if (draft.name().isBlank()) {
            return tr("wizard.name_empty");
        }
        if (!WorldCollectionRecord.isPlainText(draft.name())) {
            return tr("name_rule");
        }
        if (!WorldCollectionRecord.isPlainText(draft.author())) {
            return tr("wizard.author_rule");
        }
        if (!draft.authorUrl().isEmpty() && !WorldCollectionRecord.isValidAuthorUrl(draft.authorUrl())) {
            return tr("identity.url_rule");
        }
        return boxProblem(draft);
    }

    /** Why the box cannot be saved as chosen, or null when it can. */
    @Nullable
    static Component boxProblem(CreatorDraft draft) {
        return switch (draft.boxMode()) {
            case COLOR -> draft.boxColor() == null ? tr("identity.box_colour_missing") : null;
            case TEXTURE -> draft.boxTexture() == null ? tr("identity.box_image_missing") : null;
            case NONE -> null;
        };
    }

    // The items' facts and wording

    // Whether the server lost a file the figure names
    private static boolean lostOnServer(FigureDraft figure) {
        List<FileRef> refs = new ArrayList<>();
        refs.add(figure.texture());
        if (figure.kind() == Kind.MODEL) {
            refs.add(figure.model());
            refs.add(figure.animation());
        }
        for (CreatorDraft.VariantDraft variant : figure.variants()) {
            refs.add(variant.texture());
        }
        for (FileRef ref : refs) {
            if (ref != null && ref.local() == null && ClientWorldCollections.isMissingOnServer(ref.sha())) {
                return true;
            }
        }
        return false;
    }

    // "Box: Purple", "Box: your own image" or "Box: the mod's normal box", with the logo when it has one
    private static Component boxText(CreatorDraft draft) {
        Component box = switch (draft.boxMode()) {
            case COLOR -> {
                PopBlockColor colour = boxColour(draft.boxColor());
                yield tr("publish.check.box_colour", colour != null ? UiSwatches.nameOf(colour) : "");
            }
            case TEXTURE -> tr("publish.check.box_image");
            case NONE -> tr("publish.check.box_default");
        };
        return draft.logo() != null ? tr("publish.check.box_logo", box) : box;
    }

    @Nullable
    private static PopBlockColor boxColour(@Nullable String name) {
        for (PopBlockColor colour : PopBlockColor.values()) {
            if (colour.getSerializedName().equals(name)) {
                return colour;
            }
        }
        return null;
    }

    private static String nameOf(FigureDraft figure) {
        return figure.name().isBlank() ? figure.id() : figure.name();
    }
}
