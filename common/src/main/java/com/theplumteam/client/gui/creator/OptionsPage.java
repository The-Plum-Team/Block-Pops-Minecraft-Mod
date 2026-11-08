package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiFoldRow;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiStepper;
import com.theplumteam.client.gui.creator.ui.UiToggleRow;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.figure.DrawOdds;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's third tab, "Options": the collection's own settings, one row each, as Quick Skin's
 * settings list them, in a list that scrolls under the title.
 *
 * <p>The rows: a warning of the space while one of the collection's limits, or the server's, is
 * three quarters used or more, red once one is over; whether it is a collection of plushes for
 * the gacha machine rather than of boxed figures for the claw machine; whether its draws may come
 * out holographic; the rarities' weights set by hand, which unfold four steppers while on and
 * fall back to the defaults when turned off; who made it, its author and a link to the author's
 * page, edited in a dialog ({@link AuthorDialog}); an import of a folder of skins into new figures
 * ({@link BulkAddScreen}); an import of a data pack over the collection ({@link PackImportDialog});
 * and, in red, archiving or deleting it, which a menu offers as the collection's state allows.
 * What each setting does is its label's tooltip.
 *
 * <p>The settings and the author are changes of the draft, saved as the editor saves every change;
 * on a published or archived collection one waiting for Publish changes carries an amber dot, and
 * one the server refused, or an author or link that cannot be saved, a red dot, in either mode.
 * Whether the weights are unfolded is the row's own: it follows the weights when the page is made
 * and when they change from outside, as by an undo or another operator, never while the operator
 * steps them. Archiving, restoring and
 * deleting go to the server at once, through {@link CollectionActions}.
 */
final class OptionsPage implements EditorPage {
    // Four steppers in a row from this width, else two rows of two
    private static final int ONE_ROW_WIDTH = 290;
    private static final int STEPPER_WIDTH = 64;
    private static final int LABEL = 11;
    private static final int CELL_GAP = 12;
    private static final int ROW_GAP = 4;
    private static final int INSET = 6;
    // The space warning shows from this share of a limit
    private static final float SPACE_SHOWN = 0.75f;
    private static final Rarity[] RARITIES = Rarity.values();

    private final CreatorEditorScreen editor;
    private final UiScrollPane rows = new UiScrollPane();
    private final SpaceRow space = new SpaceRow();
    private final UiToggleRow plush;
    private final UiToggleRow holographic;
    private final UiToggleRow weights;
    private final RarityLabel[] rarityLabels = new RarityLabel[RARITIES.length];
    private final UiStepper[] steppers = new UiStepper[RARITIES.length];
    private final UiFoldRow author;
    private final UiFoldRow bulk;
    private final UiFoldRow pack;
    private final UiFoldRow danger;
    // The weights the steppers last showed, which tell a change made elsewhere from the operator's
    private RarityWeights shownWeights;

    OptionsPage(CreatorEditorScreen editor) {
        this.editor = editor;
        CreatorDraft draft = editor.draft();
        this.plush = new UiToggleRow(tr("options.plush"), draft.plush(), on -> {
            draft.setPlush(on);
            editor.changed();
        });
        this.holographic = new UiToggleRow(tr("options.holographic"), draft.holographic(), on -> {
            draft.setHolographic(on);
            editor.changed();
        }).tooltip(tr("options.holographic_note"));
        RarityWeights current = draft.rarityWeights();
        boolean byHand = !RarityWeights.DEFAULT.equals(current);
        this.weights = new UiToggleRow(tr("options.weights"), byHand, this::weightsToggled)
                .hint(tr("options.weights_hint"));
        this.weights.setOpen(byHand);
        for (int i = 0; i < RARITIES.length; i++) {
            Rarity rarity = RARITIES[i];
            this.rarityLabels[i] = this.weights.add(new RarityLabel(rarity));
            this.steppers[i] = this.weights.add(new UiStepper(RarityWeights.MIN_WEIGHT, RarityWeights.MAX_WEIGHT,
                    current.weight(rarity), value -> setWeight(rarity, value)));
        }
        this.shownWeights = current;
        this.author = new UiFoldRow(tr("options.author"), authorHint(draft), editor::openAuthor);
        this.bulk = new UiFoldRow(tr("options.bulk"), tr("options.bulk_hint"), editor::openBulkAdd);
        this.pack = new UiFoldRow(tr("options.pack"), null, editor::openImport);
        this.danger = new UiFoldRow(tr("options.danger"), null, this::openDangerMenu).danger(true);
    }

    private CreatorDraft draft() {
        return this.editor.draft();
    }

    // Building

    @Override
    public void build(UiNode.Rect area) {
        this.rows.clear();
        this.rows.at(area.x(), area.y(), area.width(), area.height());
        for (UiNode row : rows()) {
            this.rows.add(row);
        }
        this.editor.add(this.rows);
        layout();
    }

    // The rows, top to bottom
    private List<UiNode> rows() {
        return List.of(this.space, this.plush, this.holographic, this.weights, this.author, this.bulk, this.pack,
                this.danger);
    }

    /**
     * Places the rows one under the other, each as tall as it is now, so the weights unfolding or
     * the space warning coming and going move the rows under them. It only moves them, so a
     * number being typed into a stepper keeps the keyboard.
     */
    private void layout() {
        int x = this.rows.bounds().x();
        int width = this.rows.contentWidth();
        // One unit down, so the first row's hairline above it stays inside the list
        int y = this.rows.bounds().y() + 1;
        boolean first = true;
        for (UiNode node : rows()) {
            UiNode.Box row = (UiNode.Box) node;
            if (!row.visible()) {
                continue;
            }
            if (row instanceof UiFoldRow fold) {
                fold.lineAbove(first);
            } else if (row instanceof UiToggleRow toggle) {
                toggle.lineAbove(first);
            } else if (row instanceof SpaceRow warning) {
                warning.lineAbove = first;
            }
            first = false;
            row.at(x, y, width, UiFoldRow.HEIGHT);
            if (row == this.weights) {
                layoutSteppers(x, width);
            }
            y = row.bounds().bottom();
        }
    }

    // The steppers under the weights' row: in a row of four on a wide list, else two rows of two
    private void layoutSteppers(int x, int width) {
        int columns = width - 2 * INSET >= ONE_ROW_WIDTH ? RARITIES.length : 2;
        int cellWidth = Math.max(0, (width - 2 * INSET - (columns - 1) * CELL_GAP) / columns);
        int top = this.weights.contentTop() + 2;
        for (int i = 0; i < RARITIES.length; i++) {
            int cellX = x + INSET + (i % columns) * (cellWidth + CELL_GAP);
            int cellY = top + (i / columns) * (LABEL + UiStepper.HEIGHT + ROW_GAP);
            this.rarityLabels[i].at(cellX, cellY, cellWidth, LABEL);
            this.steppers[i].at(cellX, cellY + LABEL, Math.min(cellWidth, STEPPER_WIDTH), UiStepper.HEIGHT);
        }
    }

    // What the draft is now

    @Override
    public void draftChanged() {
        CreatorDraft draft = draft();
        this.plush.setValue(draft.plush());
        this.plush.tooltip(tr(draft.plush() ? "publish.type.plush_note" : "publish.type.figure_note"));
        this.holographic.setValue(draft.holographic());
        RarityWeights current = draft.rarityWeights();
        if (!current.equals(this.shownWeights)) {
            // Changed from outside, as by an undo or another operator: the row follows them
            boolean byHand = !RarityWeights.DEFAULT.equals(current);
            this.weights.setValue(byHand);
            this.weights.setOpen(byHand);
            this.shownWeights = current;
        }
        for (int i = 0; i < RARITIES.length; i++) {
            // A number being typed stays as typed, even while it is not one the stepper takes
            if (!CreatorScreen.hasKeyboard(this.steppers[i])) {
                this.steppers[i].setValue(current.weight(RARITIES[i]));
            }
        }
        this.weights.note(chanceText());
        mark(this.plush, CreatorDraft.PLUSH, "extras");
        mark(this.holographic, CreatorDraft.HOLOGRAPHIC, "holographic");
        mark(this.weights, CreatorDraft.RARITY_WEIGHTS, "extras");
        this.author.hint(authorHint(draft));
        markAuthor();
        this.space.refresh(this.editor.forecast());
        this.space.setVisible(this.space.shown());
        this.bulk.disable(draft.isFull() ? tr("figures.full", WorldContentLimits.MAX_FIGURES) : null);
        WorldCollectionRecord record = this.editor.record();
        this.danger.setLabel(tr(record != null && record.draft() ? "options.danger_draft" : "options.danger"));
        if (this.rows.bounds().width() > 0) {
            layout();
        }
    }

    /**
     * A setting's dot: red while the server refused the unit it is sent in, in either mode, amber
     * while it waits for Publish changes; none otherwise. A unit holds several settings, such as
     * the extras, which the look shares, so only a setting of it that waits is marked red.
     */
    private void mark(UiToggleRow row, String key, String unitKey) {
        Component refused = this.editor.heldUnit(unitKey);
        if (refused != null && this.editor.changes().contains(key)) {
            row.dot(Theme.RED, refused);
        } else if (this.editor.isChanged(key)) {
            row.dot(Theme.AMBER, tr("autosave.unpublished_tip_one"));
        } else {
            row.dot(0, null);
        }
    }

    // "Name · link", or "no author", and " · link" when there is one
    private static Component authorHint(CreatorDraft draft) {
        Component name = draft.author().isBlank() ? tr("options.author_none") : Component.literal(draft.author());
        return draft.authorUrl().isEmpty() ? name : Component.empty().append(name).append(tr("options.author_link"));
    }

    // The author's dot: red while the author or the link cannot be saved, or the server refused
    // them, in either mode; amber while either waits for Publish changes
    private void markAuthor() {
        Component held = this.editor.authorHeld();
        if (held != null) {
            this.author.dot(Theme.RED, held);
        } else if (this.editor.isChanged(CreatorDraft.AUTHOR) || this.editor.isChanged(CreatorDraft.AUTHOR_URL)) {
            this.author.dot(Theme.AMBER, tr("autosave.unpublished_tip_one"));
        } else {
            this.author.dot(0, null);
        }
    }

    // The rarities by hand

    /**
     * Turning the weights on only unfolds the steppers, with the weights as they are; turning them
     * off puts the default weights back and folds them.
     */
    private void weightsToggled(boolean on) {
        if (!on) {
            this.shownWeights = RarityWeights.DEFAULT;
            draft().setRarityWeights(RarityWeights.DEFAULT);
            this.editor.changed();
        }
        layout();
    }

    private void setWeight(Rarity rarity, int value) {
        RarityWeights now = draft().rarityWeights();
        int[] values = {now.common(), now.rare(), now.epic(), now.legendary()};
        values[rarity.ordinal()] = value;
        if (!RarityWeights.isValid(values[0], values[1], values[2], values[3])) {
            return;
        }
        RarityWeights next = new RarityWeights(values[0], values[1], values[2], values[3]);
        // The operator's own change, which keeps the steppers unfolded whatever the values
        this.shownWeights = next;
        draft().setRarityWeights(next);
        this.editor.changed();
    }

    /** How the chances of the figures in the draw come out, under the steppers. */
    private static Component chanceText() {
        FigureCollection preview = CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT);
        List<FigureDefinition> pool = preview != null ? preview.getEnabledFigures() : List.of();
        if (pool.isEmpty()) {
            return tr("publish.chance.none");
        }
        Map<String, Double> chances = DrawOdds.chances(pool);
        boolean even = true;
        for (FigureDefinition figure : pool) {
            even &= DrawOdds.weight(figure) == DrawOdds.weight(pool.get(0));
        }
        if (even) {
            return tr("publish.chance.even", percent(1.0 / pool.size()), pool.size());
        }
        double lowest = 1.0;
        double highest = 0.0;
        for (double chance : chances.values()) {
            lowest = Math.min(lowest, chance);
            highest = Math.max(highest, chance);
        }
        return tr("publish.chance.weighted", percent(lowest), percent(highest));
    }

    static String percent(double chance) {
        double value = chance * 100.0;
        return String.format(Locale.ROOT, value >= 0.1 || value == 0 ? "%.1f%%" : "%.2f%%", value);
    }

    // Archiving or deleting

    /**
     * The menu of the red row, as the collection's state allows: a published collection archives,
     * and is deleted only once archived; an archived one is restored or deleted; a draft is
     * deleted. Archiving and restoring wait while a save of the collection is on its way.
     */
    private void openDangerMenu(UiNode.Rect anchor) {
        WorldCollectionRecord record = this.editor.record();
        if (record == null) {
            return;
        }
        UiMenu menu = new UiMenu();
        Component waiting = this.editor.archiveProblem();
        if (record.archived()) {
            if (waiting != null) {
                menu.disabled(tr("home.menu.restore"), waiting);
            } else {
                menu.item(tr("home.menu.restore"), this.editor::restore);
            }
            purgeItem(menu);
        } else if (!record.draft()) {
            if (waiting != null) {
                menu.disabled(tr("home.menu.archive"), waiting);
            } else {
                menu.warning(tr("home.menu.archive"), this.editor::confirmArchive);
            }
            menu.disabled(tr("home.menu.purge"), tr("home.reason.archive_first"));
        } else {
            purgeItem(menu);
        }
        this.editor.openMenu(menu, anchor);
    }

    // Deleting for good, which waits for a bulk add of figures on its way
    private void purgeItem(UiMenu menu) {
        Component waiting = this.editor.purgeProblem();
        if (waiting != null) {
            menu.disabled(tr("home.menu.purge"), waiting);
        } else {
            menu.warning(tr("home.menu.purge"), this.editor::confirmPurge);
        }
    }

    // Drawing

    @Override
    public void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.editor.drawTitle(graphics, tr("options.title"));
    }

    // The page's own nodes

    /** A rarity's name over its stepper, after a dot in its colour. */
    private static final class RarityLabel extends UiNode.Box {
        private static final int DOT_ROOM = 9;
        private final Rarity rarity;

        RarityLabel(Rarity rarity) {
            this.rarity = rarity;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            Draw.dot(graphics, x + 2, y + 4, 5, CollectionStatus.rarityColour(this.rarity));
            UiText.drawFitted(graphics, font, tr("extras.rarity." + this.rarity.serializedName()).getString(),
                    x + DOT_ROOM, y, Math.max(0, width - DOT_ROOM), Theme.TEXT_SOFT);
        }
    }

    /**
     * The space warning: a row that only says, in amber, the one limit used the most once it is
     * three quarters used or more: the collection's files, its definition, or the server's space,
     * orphaned files included as the sync counts them; red once a limit is over, with why in its
     * tooltip.
     */
    private static final class SpaceRow extends UiNode.Box {
        private static final int GLYPH_ROOM = 14;
        private static final int HINT_GAP = 6;
        private boolean lineAbove;
        private Component value = Component.empty();
        @Nullable
        private Component tooltip;
        private int colour = Theme.AMBER;
        private boolean shown;

        boolean shown() {
            return this.shown;
        }

        void refresh(CreatorDraft.Forecast forecast) {
            double files = forecast.collectionBytes() / (double) WorldContentLimits.MAX_COLLECTION_BYTES;
            double definition = forecast.recordBytes() / (double) WorldContentLimits.RECORD_BYTES;
            int quotaMax = ClientWorldCollections.quotaMaxKiB();
            int quotaUsed = ClientWorldCollections.quotaUsedKiB();
            double server = quotaMax > 0 ? quotaUsed / (double) quotaMax : 0.0;
            String budget = forecast.budgetProblem();
            double most = Math.max(files, Math.max(definition, server));
            this.shown = most >= SPACE_SHOWN || budget != null;
            if (!this.shown) {
                return;
            }
            int recordKiB = (forecast.recordBytes() + 1023) / 1024;
            if (definition >= most) {
                this.value = tr("publish.check.size", recordKiB, WorldContentLimits.RECORD_BYTES / 1024);
                this.tooltip = null;
            } else if (files >= most) {
                // Each value says which limit it is: the collection's files, its definition or the server
                int usedKiB = (int) Math.min(Integer.MAX_VALUE, (forecast.collectionBytes() + 1023) / 1024);
                this.value = tr("publish.check.files", UiQuotaMeter.text(usedKiB,
                        (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024)));
                this.tooltip = null;
            } else {
                this.value = tr("options.space_server", UiQuotaMeter.text(quotaUsed, quotaMax));
                this.tooltip = tr("quota.tooltip");
            }
            boolean over = forecast.recordBytes() > WorldContentLimits.RECORD_BYTES || budget != null || most > 1.0;
            this.colour = over ? Theme.RED : Theme.AMBER;
            if (forecast.recordBytes() > WorldContentLimits.RECORD_BYTES) {
                this.tooltip = tr("editor.too_big", recordKiB, WorldContentLimits.RECORD_BYTES / 1024);
            } else if (budget != null) {
                this.tooltip = Component.literal(budget);
            }
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            if (this.lineAbove) {
                graphics.fill(x, y - 1, x + width, y, Surfaces.ROW_LINE);
            }
            graphics.fill(x, y + height - 1, x + width, y + height, Surfaces.ROW_LINE);
            int middle = y + (height - 1) / 2;
            int textX = x + INSET;
            PixelIcon.draw(graphics, Theme.ICON_WARNING, textX, middle - Theme.ICON_WARNING.length / 2, this.colour);
            textX += GLYPH_ROOM;
            String label = tr("options.space").getString();
            UiText.draw(graphics, font, label, textX, middle - 4, Theme.TEXT);
            int hintX = textX + UiText.width(font, label) + HINT_GAP;
            String hint = this.value.getString();
            UiText.drawFitted(graphics, font, hint, hintX, middle - 4, Math.max(0, x + width - INSET - hintX),
                    this.colour);
            if (hovered(mouseX, mouseY)) {
                Component tip = this.tooltip;
                if (tip == null && hintX + UiText.width(font, hint) > x + width - INSET) {
                    tip = this.value;
                }
                if (tip != null) {
                    UiTooltip.set(SidebarScreen.tooltipLines(tip));
                }
            }
        }
    }
}
