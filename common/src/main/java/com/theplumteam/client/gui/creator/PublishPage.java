package com.theplumteam.client.gui.creator;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.ClientServerSettings;
import com.theplumteam.client.gui.AdminProgressionScreen;
import com.theplumteam.client.gui.FigureDraw;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiChecklist;
import com.theplumteam.client.gui.creator.ui.UiMachineReplica;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiScrollPane;
import com.theplumteam.client.gui.creator.ui.UiSegmented;
import com.theplumteam.client.gui.creator.ui.UiStepper;
import com.theplumteam.client.gui.creator.ui.UiSwatches;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FileRef;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.DrawSimulation;
import com.theplumteam.figure.DrawOdds;
import com.theplumteam.figure.DrawRules;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.Holographic;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord.Kind;
import com.theplumteam.worldcontent.WorldCollectionRecord.Rarity;
import com.theplumteam.worldcontent.WorldCollectionRecord.RarityWeights;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's last tab, "Review & publish": what the collection still needs, how its draw
 * works, and the collection as players will see it in the machine, where a test draw can be
 * played.
 *
 * <p>The page has two views, switched at its top. "Review" lists, on the left, a checklist of
 * everything a published collection needs, each problem with a link to where it is fixed, and
 * how the draw works: every figure's chance as a bar, the token rules of this server and how
 * many days of tokens completing the collection takes, worked out by {@link DrawSimulation}. On
 * the right are the weights of the four rarities, the kind of machine the collection goes in, and
 * its visibility. "How players will see it" shows the claw machine's own screen at full size,
 * because the half-size editor has no room for the machine's figure grid beside the review: as a
 * new player or a collector sees it, with a test draw that runs the machine's light and shows the
 * winner stamped as a test, and a hundred draws counted in a chart. Neither spends a token or
 * gives anything: the draws are made up on this computer.
 *
 * <p>A draft is published with "Publish collection", which saves every change and publishes in
 * one batch the server applies whole, and is disabled, saying why, while any checklist item is a
 * blocker. A published collection can be archived or brought back instead; it is never a draft
 * again. Once published, a dialog offers what comes next: putting it in the machine the creator
 * was opened from, getting every figure to try it, seeing players' progress, or going back.
 */
final class PublishPage implements EditorPage {
    private static final int GAP = 6;
    private static final int COLUMN_GAP = 12;
    private static final int LABEL = 11;
    private static final int SECTION_GAP = 12;
    private static final int BAR_HEIGHT = 12;
    private static final int STEPPER_WIDTH = 64;
    private static final int HISTOGRAM_HEIGHT = 96;
    private static final int TEST_PULLS = 100;
    private static final int MAX_RETIRED_ROWS = 3;
    private static final int HEADING = 0xFFE6E6E6;
    private static final int HELP = 0xFFA8A8A8;
    private static final int REGULAR = 0xFF5AA0FF;
    // Told apart along the chance bar; the colours repeat after eight figures
    private static final int[] SHARE_COLOURS = {0xFFF07C1E, 0xFF5A7CFF, 0xFF7AD06A, 0xFFC9A0E6, 0xFFA07AD0,
            0xFFFFB36A, 0xFF8FA6E6, 0xFFE6D35A};
    private static final Rarity[] RARITIES = Rarity.values();

    private final CreatorEditorScreen editor;
    private final Random random = new Random();
    private final DrawSimulation simulation = new DrawSimulation();

    // The view switch and the footer
    private final UiSegmented views;
    private final UiButton backButton;
    private final UiButton saveDraftButton;
    private final UiButton publishButton;

    // Review: the checklist and the draw on the left
    private final UiScrollPane left = new UiScrollPane();
    private final Text checklistHeading;
    private final UiChecklist checklist = new UiChecklist();
    private final Text drawHeading;
    private final Text chanceLine;
    private final ShareBar shareBar = new ShareBar();
    private final Text regularRule;
    private final Text guaranteedRule;
    private final Text estimate;

    // Review: rarities, kind and visibility on the right
    private final UiScrollPane right = new UiScrollPane();
    private final Text rarityHeading;
    private final Text rarityHelp;
    private final Text[] rarityLabels = new Text[RARITIES.length];
    private final UiStepper[] steppers = new UiStepper[RARITIES.length];
    private final Text typeHeading;
    private final UiSegmented types;
    private final Text typeNote;
    private final Text visibilityHeading;
    private final Radio visibility;
    private final Text visibilityNote;

    // How players will see it
    private final UiSegmented who;
    private final UiButton testPullButton;
    private final UiButton pullsButton;
    private final UiMachineReplica replica = new UiMachineReplica();
    // The figures a test draw found, keyed as the grid asks: "collection:figure"
    private final Set<String> found = new HashSet<>();

    private boolean playersView;
    private boolean collector;
    // What a draft's visibility choice asks for: published, or kept as a draft
    private boolean publishChosen = true;
    private boolean built;
    private int footerY;

    PublishPage(CreatorEditorScreen editor) {
        this.editor = editor;
        this.views = new UiSegmented(0, index -> showPlayers(index == 1), tr("publish.view.review"),
                tr("publish.view.players"));
        this.backButton = new UiButton(tr("publish.back"), UiButton.Style.SECONDARY,
                () -> editor.selectTab(CreatorEditorScreen.Tab.FIGURES));
        this.saveDraftButton = new UiButton(tr("publish.save_draft"), UiButton.Style.SECONDARY,
                () -> editor.save(false, false));
        this.publishButton = new UiButton(tr("publish.publish"), UiButton.Style.GREEN, this::publishOrSave);

        this.checklistHeading = Text.heading(() -> tr("publish.checklist"), null);
        this.drawHeading = Text.heading(() -> tr("publish.draw"), null);
        this.chanceLine = new Text(this::chanceText, () -> Theme.TEXT_SOFT, 3);
        this.regularRule = new Text(() -> tr("publish.rule.regular", DrawRules.MAX_REGULAR_TOKENS,
                DrawRules.REGULAR_TOKEN_COOLDOWN_TICKS / (60 * 60 * 20)), () -> Theme.TEXT_SOFT, 2).square(REGULAR);
        this.guaranteedRule = new Text(PublishPage::guaranteedText, () -> Theme.TEXT_SOFT, 2).square(Theme.GOLD);
        this.estimate = new Text(this::estimateText, () -> HELP, 3);

        this.rarityHeading = Text.heading(() -> tr("publish.rarity"),
                () -> editor.isChanged(CreatorDraft.RARITY_WEIGHTS));
        this.rarityHelp = new Text(() -> tr("publish.rarity_help"), () -> HELP, 4);
        RarityWeights weights = editor.draft().rarityWeights();
        for (int i = 0; i < RARITIES.length; i++) {
            Rarity rarity = RARITIES[i];
            this.rarityLabels[i] = new Text(() -> tr("extras.rarity." + rarity.serializedName()),
                    () -> Theme.TEXT_SOFT, 1);
            this.steppers[i] = new UiStepper(RarityWeights.MIN_WEIGHT, RarityWeights.MAX_WEIGHT, weights.weight(rarity),
                    value -> setWeight(rarity, value));
        }
        this.typeHeading = Text.heading(() -> tr("publish.type"), () -> editor.isChanged(CreatorDraft.PLUSH));
        this.types = new UiSegmented(editor.draft().plush() ? 1 : 0, this::setPlush, tr("publish.type.figures"),
                tr("publish.type.plushes"));
        this.typeNote = new Text(() -> tr(editor.draft().plush() ? "publish.type.plush_note" : "publish.type.figure_note"),
                () -> HELP, 2);
        this.visibilityHeading = Text.heading(() -> tr("publish.visibility"),
                () -> editor.isChanged(CreatorDraft.ARCHIVED));
        this.visibility = new Radio(this::setVisibility);
        this.visibilityNote = new Text(() -> tr("publish.visibility_note"), () -> HELP, 2);

        this.who = new UiSegmented(0, index -> setCollector(index == 1), tr("publish.who.new"),
                tr("publish.who.collector"));
        this.testPullButton = new UiButton(tr("publish.test_pull"), UiButton.Style.PRIMARY, this::testPull);
        this.testPullButton.setTooltip(tr("publish.test_tip"));
        this.pullsButton = new UiButton(tr("publish.pulls", TEST_PULLS), UiButton.Style.SECONDARY, this::pullMany);
        this.pullsButton.setTooltip(tr("publish.test_tip"));
        this.replica.setStamp(tr("test_draw.stamp"));
        setCollector(false);
    }

    private CreatorDraft draft() {
        return this.editor.draft();
    }

    // Building

    @Override
    public void build(UiNode.Rect bodyArea) {
        Font font = Minecraft.getInstance().font;
        int x = bodyArea.x();
        int top = bodyArea.y();
        this.views.select(this.playersView ? 1 : 0);
        this.views.at(x, top, Math.min(bodyArea.width(), this.views.naturalWidth()), UiSegmented.HEIGHT);
        this.editor.add(this.views);

        // The footer: back to the figures on the left, saving and publishing on the right
        this.footerY = bodyArea.bottom() - UiButton.HEIGHT;
        this.backButton.at(x, this.footerY, UiButton.widthFor(font, this.backButton.label(), false), UiButton.HEIGHT);
        this.editor.add(this.backButton);
        refreshFooterLabels();
        int buttonX = placeLeftOf(this.publishButton, bodyArea.right(), this.footerY, 0, font);
        this.editor.add(this.publishButton);
        if (draft().isDraftCollection()) {
            placeLeftOf(this.saveDraftButton, buttonX, this.footerY, GAP, font);
            this.editor.add(this.saveDraftButton);
        }

        int contentTop = top + UiSegmented.HEIGHT + GAP;
        int contentBottom = this.footerY - GAP;
        if (this.playersView) {
            buildPlayers(bodyArea, top, contentTop, contentBottom, font);
        } else {
            this.replica.close();
            int columnWidth = Math.max(0, (bodyArea.width() - COLUMN_GAP) / 2);
            int height = Math.max(0, contentBottom - contentTop);
            this.left.clear();
            this.left.at(x, contentTop, columnWidth, height);
            this.right.clear();
            this.right.at(x + columnWidth + COLUMN_GAP, contentTop, Math.max(0, bodyArea.right() - x - columnWidth
                    - COLUMN_GAP), height);
            addLeft();
            addRight();
            this.editor.add(this.left);
            this.editor.add(this.right);
        }
        this.built = true;
    }

    // Places a button left of a point, as wide as its label needs; returns its left edge
    private static int placeLeftOf(UiButton button, int rightEdge, int y, int gap, Font font) {
        int width = UiButton.widthFor(font, button.label(), false);
        int buttonX = rightEdge - gap - width;
        button.at(buttonX, y, width, UiButton.HEIGHT);
        return buttonX;
    }

    private void buildPlayers(UiNode.Rect bodyArea, int top, int contentTop, int contentBottom, Font font) {
        // The test draws and who the grid is shown to, on the right of the view switch
        int rowRight = bodyArea.right();
        int pullsWidth = UiButton.widthFor(font, this.pullsButton.label(), false);
        int testWidth = UiButton.widthFor(font, this.testPullButton.label(), false);
        int whoLeft = this.views.bounds().right() + 2 * GAP;
        int whoWidth = Math.max(0, Math.min(this.who.naturalWidth(), rowRight - whoLeft - pullsWidth - testWidth - 3 * GAP));
        this.who.select(this.collector ? 1 : 0);
        this.who.at(rowRight - whoWidth, top, whoWidth, UiSegmented.HEIGHT);
        this.who.setVisible(whoWidth >= 60);
        int buttonsRight = this.who.visible() ? rowRight - whoWidth - 2 * GAP : rowRight;
        this.pullsButton.at(buttonsRight - pullsWidth, top, pullsWidth, UiSegmented.HEIGHT);
        this.testPullButton.at(buttonsRight - pullsWidth - GAP - testWidth, top, testWidth, UiSegmented.HEIGHT);
        this.editor.add(this.who);
        this.editor.add(this.pullsButton);
        this.editor.add(this.testPullButton);

        this.replica.setCollection(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT));
        this.replica.setSourceId(draft().collectionId());
        this.replica.at(bodyArea.x(), contentTop, bodyArea.width(), Math.max(0, contentBottom - contentTop));
        this.editor.add(this.replica);
    }

    private void addLeft() {
        addAll(this.left, this.checklistHeading, this.checklist, this.drawHeading, this.chanceLine, this.shareBar,
                this.regularRule, this.guaranteedRule, this.estimate);
    }

    private void addRight() {
        addAll(this.right, this.rarityHeading, this.rarityHelp);
        for (int i = 0; i < RARITIES.length; i++) {
            addAll(this.right, this.rarityLabels[i], this.steppers[i]);
        }
        addAll(this.right, this.typeHeading, this.types, this.typeNote, this.visibilityHeading, this.visibility,
                this.visibilityNote);
    }

    private static void addAll(UiScrollPane pane, UiNode... nodes) {
        for (UiNode node : nodes) {
            pane.add(node);
        }
    }

    /**
     * Places the review's nodes in their columns. It only moves them, never adds them again, so a
     * number being typed into a rarity weight keeps the keyboard while the rows around it move.
     */
    private void layoutReview() {
        int x = this.left.bounds().x();
        int width = this.left.contentWidth();
        int y = this.left.bounds().y();
        y = place(this.checklistHeading, x, y, width, LABEL) + 2;
        int checklistHeight = this.checklist.heightFor(width);
        this.checklist.at(x, y, width, checklistHeight);
        y += checklistHeight + SECTION_GAP;
        y = place(this.drawHeading, x, y, width, LABEL) + 2;
        y = place(this.chanceLine, x, y, width, this.chanceLine.heightFor(width)) + 3;
        this.shareBar.setVisible(!this.shareBar.isEmpty());
        if (this.shareBar.visible()) {
            this.shareBar.at(x, y, width, BAR_HEIGHT);
            y += BAR_HEIGHT + 6;
        }
        y = place(this.regularRule, x, y, width, this.regularRule.heightFor(width)) + 2;
        y = place(this.guaranteedRule, x, y, width, this.guaranteedRule.heightFor(width)) + 4;
        // Its full height, since the estimate arrives later and may take more lines than the wait
        place(this.estimate, x, y, width, this.estimate.maxHeight());

        x = this.right.bounds().x();
        width = this.right.contentWidth();
        y = this.right.bounds().y();
        y = place(this.rarityHeading, x, y, width, LABEL);
        y = place(this.rarityHelp, x, y, width, this.rarityHelp.heightFor(width)) + 4;
        // Two weights a row, each its name above its number
        int half = (width - COLUMN_GAP) / 2;
        for (int i = 0; i < RARITIES.length; i++) {
            int cellX = x + (i % 2) * (half + COLUMN_GAP);
            int cellY = y + (i / 2) * (LABEL + UiStepper.HEIGHT + 4);
            this.rarityLabels[i].at(cellX, cellY, half, LABEL);
            this.steppers[i].at(cellX, cellY + LABEL, Math.min(half, STEPPER_WIDTH), UiStepper.HEIGHT);
        }
        y += 2 * (LABEL + UiStepper.HEIGHT + 4) + SECTION_GAP - 4;
        y = place(this.typeHeading, x, y, width, LABEL) + 1;
        this.types.at(x, y, Math.min(width, this.types.naturalWidth()), UiSegmented.HEIGHT);
        y += UiSegmented.HEIGHT + 3;
        y = place(this.typeNote, x, y, width, this.typeNote.heightFor(width)) + SECTION_GAP;
        y = place(this.visibilityHeading, x, y, width, LABEL) + 2;
        int radioHeight = this.visibility.heightFor(width);
        this.visibility.at(x, y, width, radioHeight);
        y += radioHeight + 2;
        place(this.visibilityNote, x, y, width, this.visibilityNote.heightFor(width));
    }

    private static int place(UiNode.Box node, int x, int y, int width, int height) {
        node.at(x, y, width, height);
        return y + height;
    }

    // What the draft is now

    @Override
    public void draftChanged() {
        CreatorDraft draft = draft();
        refreshChecklist();
        refreshShares();
        RarityWeights weights = draft.rarityWeights();
        for (int i = 0; i < RARITIES.length; i++) {
            // A number being typed stays as typed, even while it is not one the stepper takes
            if (!CreatorScreen.hasKeyboard(this.steppers[i])) {
                this.steppers[i].setValue(weights.weight(RARITIES[i]));
            }
        }
        this.types.select(draft.plush() ? 1 : 0);
        refreshVisibility();
        refreshFooter();
        if (this.built && !this.playersView && this.left.bounds().width() > 0) {
            layoutReview();
        }
    }

    private void refreshFooterLabels() {
        boolean isDraft = draft().isDraftCollection();
        this.publishButton.setLabel(tr(isDraft ? "publish.publish" : "publish.save"));
        this.publishButton.setStyle(UiButton.Style.GREEN);
    }

    private void refreshFooter() {
        refreshFooterLabels();
        Component saveProblem = this.editor.saveProblem();
        this.saveDraftButton.disable(saveProblem);
        if (!draft().isDraftCollection()) {
            this.publishButton.disable(saveProblem);
            return;
        }
        Component problem = null;
        if (this.checklist.blocked()) {
            problem = tr("publish.blocked");
        } else if (!this.publishChosen) {
            problem = tr("publish.kept_draft");
        } else {
            problem = this.editor.sendProblem();
        }
        this.publishButton.disable(problem);
    }

    private void publishOrSave() {
        if (draft().isDraftCollection()) {
            this.editor.save(true, false);
        } else {
            this.editor.save(false, false);
        }
    }

    /**
     * The checklist, from the draft as it is: each line done, a warning, a blocker that holds back
     * publishing, or optional, with a link to where it is fixed.
     */
    private void refreshChecklist() {
        CreatorDraft draft = draft();
        UiChecklist list = this.checklist;
        list.clear();
        Component fix = tr("publish.fix");
        Component view = tr("publish.view");
        Runnable toIdentity = () -> this.editor.selectTab(CreatorEditorScreen.Tab.IDENTITY);

        // Name, author and colour, then the box
        if (this.editor.identityReady()) {
            list.row(UiChecklist.Mark.DONE, tr("publish.check.identity"));
        } else {
            Component problem = this.editor.identityProblem();
            list.row(UiChecklist.Mark.BLOCKER, problem != null ? problem : tr("publish.check.identity"), fix, toIdentity);
        }
        Component boxProblem = this.editor.boxProblem();
        if (boxProblem != null) {
            list.row(UiChecklist.Mark.BLOCKER, boxProblem, fix, toIdentity);
        } else {
            list.row(UiChecklist.Mark.DONE, boxText(draft));
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
            list.row(UiChecklist.Mark.BLOCKER, tr("publish.check.no_figures"), tr("publish.add"),
                    () -> this.editor.selectTab(CreatorEditorScreen.Tab.FIGURES));
        } else if (lost != null) {
            FigureDraft first = lost;
            list.row(UiChecklist.Mark.BLOCKER, lostCount == 1 ? tr("publish.check.lost_one", nameOf(first))
                    : tr("publish.check.lost", lostCount), view, () -> showFigure(first));
        } else {
            list.row(UiChecklist.Mark.DONE, inDraw == 1 ? tr("publish.check.figures_one")
                    : tr("publish.check.figures", inDraw));
        }
        if (unfinished != null) {
            FigureDraft first = unfinished;
            list.row(UiChecklist.Mark.WARNING, unfinishedCount == 1 ? tr("publish.check.unfinished_one", nameOf(first))
                    : tr("publish.check.unfinished", unfinishedCount), view, () -> showFigure(first));
        }
        for (int i = 0; i < retired.size() && i < MAX_RETIRED_ROWS; i++) {
            FigureDraft figure = retired.get(i);
            list.row(UiChecklist.Mark.WARNING, tr("publish.check.retired", nameOf(figure)), view,
                    () -> showFigure(figure));
        }
        if (retired.size() > MAX_RETIRED_ROWS) {
            list.row(UiChecklist.Mark.WARNING, tr("publish.check.retired_more", retired.size() - MAX_RETIRED_ROWS));
        }

        // The optional author link
        if (draft.authorUrl().isEmpty()) {
            list.row(UiChecklist.Mark.TODO, tr("publish.check.no_link"), tr("publish.add"), toIdentity);
        } else {
            list.row(UiChecklist.Mark.DONE, tr("publish.check.link"));
        }

        // What it takes on the server: its definition and its files
        CreatorDraft.Forecast forecast = draft.forecast();
        int recordKiB = (forecast.recordBytes() + 1023) / 1024;
        if (forecast.recordBytes() > WorldContentLimits.RECORD_BYTES) {
            list.row(UiChecklist.Mark.BLOCKER, tr("editor.too_big", recordKiB, WorldContentLimits.RECORD_BYTES / 1024));
        } else {
            list.row(UiChecklist.Mark.DONE, tr("publish.check.size", recordKiB, WorldContentLimits.RECORD_BYTES / 1024));
        }
        if (forecast.budgetProblem() != null) {
            list.row(UiChecklist.Mark.BLOCKER, Component.literal(forecast.budgetProblem()));
        } else {
            int usedKiB = (int) Math.min(Integer.MAX_VALUE, (forecast.collectionBytes() + 1023) / 1024);
            list.row(UiChecklist.Mark.DONE, tr("publish.check.files", UiQuotaMeter.text(usedKiB,
                    (int) (WorldContentLimits.MAX_COLLECTION_BYTES / 1024))));
        }
    }

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

    /** Opens the figure grid with one figure selected, as a checklist line's link does. */
    private void showFigure(FigureDraft figure) {
        this.editor.selectTab(CreatorEditorScreen.Tab.FIGURES);
        this.editor.revealFigures(List.of(figure));
    }

    // The draw

    // The figures in the draw of the editor's preview, which are the draft's complete figures
    private static List<FigureDefinition> pool() {
        FigureCollection preview = CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT);
        return preview != null ? preview.getEnabledFigures() : List.of();
    }

    private static boolean isEven(List<FigureDefinition> pool) {
        for (FigureDefinition figure : pool) {
            if (DrawOdds.weight(figure) != DrawOdds.weight(pool.get(0))) {
                return false;
            }
        }
        return true;
    }

    private void refreshShares() {
        List<FigureDefinition> pool = pool();
        Map<String, Double> chances = DrawOdds.chances(pool);
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            FigureDefinition figure = pool.get(i);
            Double chance = chances.get(figure.getId());
            if (chance != null) {
                shares.add(new Share(figure.getName(), chance, SHARE_COLOURS[i % SHARE_COLOURS.length]));
            }
        }
        this.shareBar.setShares(shares);
    }

    private Component chanceText() {
        List<FigureDefinition> pool = pool();
        if (pool.isEmpty()) {
            return tr("publish.chance.none");
        }
        Map<String, Double> chances = DrawOdds.chances(pool);
        if (isEven(pool)) {
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

    private static String percent(double chance) {
        double value = chance * 100.0;
        return String.format(Locale.ROOT, value >= 0.1 || value == 0 ? "%.1f%%" : "%.2f%%", value);
    }

    private static Component guaranteedText() {
        Integer hour = ClientServerSettings.getGuaranteedResetHour();
        return hour != null ? tr("publish.rule.guaranteed_at", String.format(Locale.ROOT, "%02d:00", hour))
                : tr("publish.rule.guaranteed");
    }

    private Component estimateText() {
        if (pool().isEmpty()) {
            return tr("publish.estimate.none");
        }
        int days = this.simulation.days();
        if (days < 0) {
            return tr(this.simulation.isWorking() ? "publish.estimate.working" : "publish.estimate.none");
        }
        return days == 1 ? tr("publish.estimate.one") : tr("publish.estimate", days);
    }

    private static int[] weights(List<FigureDefinition> pool) {
        int[] weights = new int[pool.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = DrawOdds.weight(pool.get(i));
        }
        return weights;
    }

    private void setWeight(Rarity rarity, int value) {
        RarityWeights now = draft().rarityWeights();
        int[] values = {now.common(), now.rare(), now.epic(), now.legendary()};
        values[rarity.ordinal()] = value;
        if (!RarityWeights.isValid(values[0], values[1], values[2], values[3])) {
            return;
        }
        draft().setRarityWeights(new RarityWeights(values[0], values[1], values[2], values[3]));
        this.editor.changed();
    }

    private void setPlush(int index) {
        draft().setPlush(index == 1);
        this.editor.changed();
    }

    // Visibility

    private void refreshVisibility() {
        CreatorDraft draft = draft();
        String machine = draft.plush() ? "gacha" : "claw";
        if (draft.isDraftCollection()) {
            this.visibility.setOptions(List.of(
                    new Option(tr("publish.visibility.draft"), tr("publish.visibility.draft_help")),
                    new Option(tr("publish.visibility.published"), tr("publish.visibility.published_help." + machine))),
                    this.publishChosen ? 1 : 0);
        } else {
            this.visibility.setOptions(List.of(
                    new Option(tr("publish.visibility.published"), tr("publish.visibility.published_help." + machine)),
                    new Option(tr("publish.visibility.archived"), tr("publish.visibility.archived_help"))),
                    draft.archived() ? 1 : 0);
        }
    }

    private void setVisibility(int index) {
        CreatorDraft draft = draft();
        if (draft.isDraftCollection()) {
            // Only a choice: publishing happens with the button, in one batch with the changes
            this.publishChosen = index == 1;
            refreshFooter();
            return;
        }
        draft.setArchived(index == 1);
        this.editor.changed();
    }

    // How players will see it

    private void showPlayers(boolean players) {
        if (players != this.playersView) {
            this.playersView = players;
            this.editor.rebuildPage();
        }
    }

    private void setCollector(boolean all) {
        this.collector = all;
        this.who.select(all ? 1 : 0);
        if (all) {
            this.replica.setDiscovered(key -> true);
        } else {
            this.replica.setDiscovered(this.found::contains);
        }
    }

    private static String keyOf(FigureDefinition figure) {
        return CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT) + ":" + figure.getId();
    }

    /**
     * Plays a test draw as a regular token would, on the replica's grid: the winner is drawn by
     * weight here and counts as found by the new player shown, so a run of test draws fills the
     * grid. It comes out holographic as often as a real draw does. Nothing is sent to the server.
     */
    private void testPull() {
        List<FigureDefinition> pool = pool();
        if (pool.isEmpty()) {
            return;
        }
        if (this.collector) {
            // As the mockup does, a draw is shown to the new player, who can still find something
            setCollector(false);
        }
        FigureDefinition winner = DrawOdds.pick(pool, this.random);
        String key = keyOf(winner);
        boolean isNew = !this.found.contains(key);
        boolean holographic = this.random.nextFloat() < Holographic.CHANCE;
        if (this.replica.testDraw(winner, isNew, holographic)) {
            this.found.add(key);
        } else {
            this.editor.status(tr("publish.too_narrow"), Theme.AMBER);
        }
    }

    /** Draws a hundred times by weight and shows how often each figure came out. */
    private void pullMany() {
        List<FigureDefinition> pool = pool();
        if (pool.isEmpty()) {
            return;
        }
        int[] counts = new int[pool.size()];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < pool.size(); i++) {
            index.put(pool.get(i).getId(), i);
        }
        for (int pull = 0; pull < TEST_PULLS; pull++) {
            Integer at = index.get(DrawOdds.pick(pool, this.random).getId());
            if (at != null) {
                counts[at]++;
            }
        }
        int distinct = 0;
        for (int count : counts) {
            distinct += count > 0 ? 1 : 0;
        }
        Map<String, Double> chances = DrawOdds.chances(pool);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            FigureDefinition figure = pool.get(i);
            bars.add(new Bar(figure.getName(), counts[i], chances.getOrDefault(figure.getId(), 0.0),
                    SHARE_COLOURS[i % SHARE_COLOURS.length]));
        }
        UiModal dialog = new UiModal(tr("publish.pulls_title", TEST_PULLS),
                tr("publish.pulls_body", distinct, pool.size())).width(360);
        dialog.node(new Histogram(bars, TEST_PULLS), HISTOGRAM_HEIGHT);
        dialog.cancelButton(tr("publish.close"));
        this.editor.openModal(dialog);
    }

    // After publishing

    /**
     * The collection was just published: a dialog says so and offers what comes next, each a
     * button of its own row.
     */
    void published(String name) {
        String id = draft().collectionId();
        BlockPos machinePos = this.editor.machinePos();
        UiModal dialog = new UiModal(tr("editor.published", name), tr("publish.done.body")).width(280);
        if (machinePos != null) {
            UiButton put = action(dialog, tr("home.menu.put"), UiButton.Style.PRIMARY, () -> {
                new ClawMachineCollectionPacket(machinePos, id).sendToServer();
                this.editor.toast(tr("home.put_done", name));
            });
            if (ClientHiddenCollections.isHidden(id)) {
                put.disable(tr("home.reason.hidden_machine"));
            }
        }
        action(dialog, tr("publish.done.unlock"), UiButton.Style.SECONDARY, () -> {
            try {
                new UnlockCollectionPacket(id).sendToServer();
                this.editor.toast(tr("unlock_sent", name));
            } catch (UnsupportedOperationException e) {
                // NeoForge refuses to send a payload the server did not register
                this.editor.toast(tr("not_sent"));
            }
        });
        action(dialog, tr("publish.done.progress"), UiButton.Style.SECONDARY,
                () -> this.editor.openChild(new AdminProgressionScreen(this.editor, id)));
        action(dialog, tr("publish.done.back"), UiButton.Style.SECONDARY, this.editor::onClose);
        // Publishing cannot be taken back, but archiving takes the collection out of the machines again
        action(dialog, tr("publish.done.undo"), UiButton.Style.DANGER_TEXT, () -> archive(id, name));
        dialog.cancelButton(tr("publish.done.stay"));
        this.editor.openModal(dialog);
    }

    private static UiButton action(UiModal dialog, Component label, UiButton.Style style, Runnable onPress) {
        UiButton[] made = new UiButton[1];
        made[0] = new UiButton(label, style, () -> {
            dialog.close();
            onPress.run();
        });
        dialog.node(made[0], style == UiButton.Style.DANGER_TEXT ? 10 : UiButton.HEIGHT);
        return made[0];
    }

    private void archive(String id, String name) {
        boolean sent = ClientWorldUploads.edit(new WorldCollectionEdit.SetArchived(id, true), outcome -> {
            if (outcome.ok()) {
                this.editor.toast(tr("home.archived", name));
            } else if (outcome.disconnected()) {
                this.editor.toast(tr("disconnected"));
            } else if (!outcome.cancelled()) {
                this.editor.toast(CreatorSave.message(outcome.status()));
            }
        });
        if (!sent) {
            this.editor.toast(tr("not_sent"));
        }
    }

    // Ticking and drawing

    @Override
    public void tick() {
        this.simulation.request(weights(pool()));
        this.simulation.tick();
        if (this.playersView) {
            Component none = pool().isEmpty() ? tr("publish.no_pool") : null;
            this.testPullButton.disable(none != null ? none : FigureDraw.isBusy() ? tr("publish.drawing") : null);
            this.pullsButton.disable(none);
        }
    }

    @Override
    public void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!draft().isDraftCollection()) {
            return;
        }
        // What publishing leads to, between the footer's buttons, where it fits
        Font font = Minecraft.getInstance().font;
        int from = this.backButton.bounds().right() + 10;
        int to = this.saveDraftButton.bounds().x() - 10;
        String shown = Draw.cut(font, tr("publish.afterwards").getString(), Math.max(0, to - from));
        if (font.width(shown) >= 80) {
            graphics.drawString(font, shown, to - font.width(shown), this.footerY + 6, HELP, false);
        }
    }

    @Override
    public void hide() {
        // A test draw left playing would keep the machine's light busy after the tab is gone
        this.replica.close();
    }

    @Override
    public void close() {
        this.replica.close();
        this.built = false;
    }

    // The page's own nodes

    /** One figure's share of the draw, for the chance bar. */
    private record Share(String name, double chance, int colour) {
    }

    /**
     * Every figure's chance as a bar split into pieces as wide as the chances, each named in a
     * tooltip, and labelled with the name's start where a piece is wide enough.
     */
    private static final class ShareBar extends UiNode.Box {
        private List<Share> shares = List.of();

        void setShares(List<Share> next) {
            this.shares = List.copyOf(next);
        }

        boolean isEmpty() {
            return this.shares.isEmpty();
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || this.shares.isEmpty() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            // A gap between pieces only while every piece keeps a few pixels
            int gap = width / this.shares.size() >= 6 ? 1 : 0;
            double total = 0;
            for (Share share : this.shares) {
                total += share.chance();
            }
            double at = 0;
            for (int i = 0; i < this.shares.size(); i++) {
                Share share = this.shares.get(i);
                int from = x + (int) Math.round(width * at / total);
                at += share.chance();
                int to = i == this.shares.size() - 1 ? x + width : x + (int) Math.round(width * at / total) - gap;
                if (to <= from) {
                    to = from + 1;
                }
                graphics.fill(from, y, to, y + height, share.colour());
                String label = Draw.cut(font, share.name(), to - from - 2);
                if (to - from >= 16 && !label.endsWith("...") && font.width(label) <= to - from - 2) {
                    graphics.drawString(font, label, (from + to) / 2 - font.width(label) / 2, y + 2, 0xFF000000, false);
                }
                if (mouseX >= from && mouseX < Math.max(to, from + 1) + gap && mouseY >= y && mouseY < y + height) {
                    UiTooltip.set(tr("publish.share", share.name(), percent(share.chance())));
                }
            }
        }
    }

    /** One figure's count out of the test draws, with its chance. */
    private record Bar(String name, int count, double chance, int colour) {
    }

    /**
     * How often each figure came out of a run of test draws: a column per figure as tall as its
     * count, with a tick where its chance says it should reach, and the figure's name, count and
     * chance in a tooltip.
     */
    private static final class Histogram extends UiNode.Box {
        private final List<Bar> bars;
        private final int pulls;

        Histogram(List<Bar> bars, int pulls) {
            this.bars = bars;
            this.pulls = pulls;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (this.bars.isEmpty() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int top = y + Theme.LINE_HEIGHT;
            int bottom = y + height - 2;
            int most = 1;
            for (Bar bar : this.bars) {
                most = Math.max(most, Math.max(bar.count(), (int) Math.ceil(bar.chance() * this.pulls)));
            }
            graphics.drawString(font, tr("publish.pulls_axis", most).getString(), x, y, HELP, false);
            graphics.fill(x, bottom, x + width, bottom + 1, Theme.SEPARATOR);
            int slot = Math.max(1, width / this.bars.size());
            int gap = slot >= 6 ? Math.max(1, slot / 5) : 0;
            int chartHeight = Math.max(1, bottom - top);
            for (int i = 0; i < this.bars.size(); i++) {
                Bar bar = this.bars.get(i);
                int left = x + i * slot;
                if (left >= x + width) {
                    break;
                }
                int right = Math.min(x + width, left + slot - gap);
                int barTop = bottom - Math.round(chartHeight * bar.count() / (float) most);
                if (bar.count() > 0) {
                    graphics.fill(left, barTop, Math.max(left + 1, right), bottom, bar.colour());
                }
                int expectedY = bottom - (int) Math.round(chartHeight * bar.chance() * this.pulls / most);
                graphics.fill(left, expectedY, Math.max(left + 1, right), expectedY + 1, Theme.TEXT);
                if (mouseX >= left && mouseX < left + slot && mouseY >= top && mouseY <= bottom) {
                    UiTooltip.set(List.of(Component.literal(bar.name()),
                            tr("publish.pulls_bar", bar.count(), this.pulls,
                                    String.format(Locale.ROOT, "%.1f", bar.chance() * this.pulls))));
                }
            }
        }
    }

    /** A choice of the visibility radio: a title with a line of help under it. */
    private record Option(Component title, Component help) {
    }

    /**
     * A choice of one of a few options, each a round mark, a title and a line of help under it,
     * as the mockup's visibility choice. Clicking an option chooses it.
     */
    private static final class Radio extends UiNode.Box {
        private static final int MARK = 9;
        private static final int TEXT_X = 14;
        private static final int OPTION_GAP = 5;

        private final IntConsumer onSelect;
        private List<Option> options = List.of();
        private int selected;

        Radio(IntConsumer onSelect) {
            this.onSelect = onSelect;
        }

        void setOptions(List<Option> next, int chosen) {
            this.options = List.copyOf(next);
            this.selected = chosen;
        }

        int heightFor(int forWidth) {
            int total = 0;
            for (int i = 0; i < this.options.size(); i++) {
                total += optionHeight(this.options.get(i), forWidth) + (i > 0 ? OPTION_GAP : 0);
            }
            return total;
        }

        private static int optionHeight(Option option, int forWidth) {
            Font font = Minecraft.getInstance().font;
            return Theme.LINE_HEIGHT + Draw.fit(font, option.help().getString(), Math.max(20, forWidth - TEXT_X), 2).size()
                    * Theme.LINE_HEIGHT;
        }

        private int indexAt(double mouseX, double mouseY) {
            if (!bounds().contains(mouseX, mouseY)) {
                return -1;
            }
            int top = y;
            for (int i = 0; i < this.options.size(); i++) {
                int bottom = top + optionHeight(this.options.get(i), width);
                if (mouseY >= top && mouseY < bottom + OPTION_GAP) {
                    return i;
                }
                top = bottom + OPTION_GAP;
            }
            return -1;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int hovered = indexAt(mouseX, mouseY);
            int top = y;
            for (int i = 0; i < this.options.size(); i++) {
                Option option = this.options.get(i);
                boolean on = i == this.selected;
                int ring = on ? Theme.GOLD : i == hovered ? Theme.TEXT : Theme.CONTROL_OUTLINE;
                // A round mark drawn from rows: a ring, filled gold while chosen
                graphics.fill(x + 2, top, x + MARK - 2, top + 1, ring);
                graphics.fill(x + 2, top + MARK - 1, x + MARK - 2, top + MARK, ring);
                graphics.fill(x, top + 2, x + 1, top + MARK - 2, ring);
                graphics.fill(x + MARK - 1, top + 2, x + MARK, top + MARK - 2, ring);
                graphics.fill(x + 1, top + 1, x + 2, top + 2, ring);
                graphics.fill(x + MARK - 2, top + 1, x + MARK - 1, top + 2, ring);
                graphics.fill(x + 1, top + MARK - 2, x + 2, top + MARK - 1, ring);
                graphics.fill(x + MARK - 2, top + MARK - 2, x + MARK - 1, top + MARK - 1, ring);
                if (on) {
                    graphics.fill(x + 2, top + 2, x + MARK - 2, top + MARK - 2, Theme.GOLD);
                }
                Draw.text(graphics, font, option.title().getString(), x + TEXT_X, top + 1, width - TEXT_X,
                        on ? Theme.TEXT : Theme.TEXT_SOFT);
                Draw.lines(graphics, font, option.help().getString(), x + TEXT_X, top + 1 + Theme.LINE_HEIGHT,
                        width - TEXT_X, 2, HELP);
                top += optionHeight(option, width) + OPTION_GAP;
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !visible()) {
                return false;
            }
            int index = indexAt(click.x(), click.y());
            if (index < 0) {
                return false;
            }
            if (index != this.selected) {
                this.selected = index;
                this.onSelect.accept(index);
            }
            return true;
        }
    }

    /**
     * A line or a few lines of text in the review's columns: a heading, with an amber dot while
     * its section holds unsaved changes, or text wrapped over its height, after a coloured square
     * when it is one of the token rules.
     */
    private static final class Text extends UiNode.Box {
        private final Supplier<Component> text;
        private final IntSupplier colour;
        private final int maxLines;
        private boolean heading;
        @Nullable
        private BooleanSupplier changed;
        private int square;

        Text(Supplier<Component> text, IntSupplier colour, int maxLines) {
            this.text = text;
            this.colour = colour;
            this.maxLines = maxLines;
        }

        static Text heading(Supplier<Component> text, @Nullable BooleanSupplier changed) {
            Text made = new Text(text, () -> HEADING, 1);
            made.heading = true;
            made.changed = changed;
            return made;
        }

        Text square(int squareColour) {
            this.square = squareColour;
            return this;
        }

        private int indent() {
            return this.square != 0 ? 12 : 0;
        }

        int maxHeight() {
            return this.maxLines * Theme.LINE_HEIGHT;
        }

        int heightFor(int forWidth) {
            Font font = Minecraft.getInstance().font;
            int lines = Draw.fit(font, this.text.get().getString(), Math.max(20, forWidth - indent()), this.maxLines).size();
            return Math.max(1, lines) * Theme.LINE_HEIGHT;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String shown = this.text.get().getString();
            if (this.heading) {
                Component bold = Component.literal(Draw.cut(font, shown, Math.max(0, width - 20)))
                        .withStyle(ChatFormatting.BOLD);
                graphics.drawString(font, bold, x, y, this.colour.getAsInt(), false);
                if (this.changed != null && this.changed.getAsBoolean()) {
                    Draw.dot(graphics, x + font.width(bold) + 5, y + 4, 4, Theme.AMBER);
                }
                return;
            }
            if (this.square != 0) {
                graphics.fill(x, y + 1, x + 7, y + 8, this.square);
            }
            Draw.lines(graphics, font, shown, x + indent(), y, Math.max(0, width - indent()),
                    Math.max(1, Math.min(this.maxLines, height / Theme.LINE_HEIGHT)), this.colour.getAsInt());
        }
    }
}
