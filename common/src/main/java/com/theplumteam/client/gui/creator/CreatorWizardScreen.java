package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.DominantColour;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiColourPicker;
import com.theplumteam.client.gui.creator.ui.UiDropZone;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMachineReplica;
import com.theplumteam.client.gui.creator.ui.UiSwatches;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogCloseButton;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The creator's second screen: a card over the collections overview that starts a new World
 * collection in one step. The operator types its name (the author is filled in with their
 * player name), may add a logo and picks the background colour from the swatches, from the
 * logo's main colour or from a custom colour. Its id is made from the name, made unique among
 * the collections this client was sent; "Advanced options" shows it to change it, since it can
 * never change once the collection exists. Beside the form, the claw machine's screen shows the
 * collection as it is typed.
 *
 * <p>"Create and add figures" stays disabled, saying why, until the name and id are usable.
 * It sends one batch that creates the collection as a draft and sets its look, after the logo's
 * upload, with a progress line meanwhile; a slug the server refuses as taken is retried with
 * the next one. Once the server has synced the new collection, the collection editor
 * ({@link CreatorEditorScreen}) opens on its figures. Leaving while it is being sent stops
 * nothing already sent: the overview selects the collection when it arrives.
 *
 * <p>The card is drawn as the menus' dialogs are ({@link DialogFrame}), with the green header of a
 * dialog that creates something, the steps of a new collection on the header's right and an X
 * that leaves, as Escape and Cancel do.
 */
public class CreatorWizardScreen extends CreatorScreen {
    // The mockup's 940 by 672 dialog at about half its pixel size, as every creator size is,
    // a little wider so the machine's screen beside the form has room for its two columns
    private static final int MAX_CARD_WIDTH = 540;
    private static final int MAX_CARD_HEIGHT = 320;
    // Room for the twelve colours and a thirteenth, custom one on a single row
    private static final int MAX_LEFT_WIDTH = 250;
    private static final int SCREEN_MARGIN = 8;
    // The status line goes under the card, which leaves it this much room on a short window
    private static final int STATUS_GAP = 4;
    private static final int STATUS_ROOM = STATUS_GAP + 9;
    // The toast's height, which goes above the card where there is room for it
    private static final int TOAST_ROOM = 25 + 4;
    private static final int PAD = 12;
    private static final int COLUMN_GAP = 14;
    private static final int HEADER = DialogFrame.HEADER_HEIGHT + 6;
    private static final int LABEL = 11;
    private static final int FIELD = 20;
    private static final int ROW_GAP = 4;
    // The lines under the fields of a tall card: the name's state is one, the author's help two
    private static final int HELP_LINES = 3;
    private static final int ID_FIELD_WIDTH = 110;
    private static final int CREATE_WIDTH = 150;
    private static final int STEP_BOX = 11;
    private static final int STEP_LINE = 14;
    // The steps on the header's strip: the current one white, the others faded into the strip
    private static final int STEP_OFF = 0xFFA8C8B0;
    private static final int STEP_EDGE = 0x80FFFFFF;
    // The room the header's X takes at its right end
    private static final int CLOSE_ROOM = 6 + DialogCloseButton.SIZE + 8;
    private static final int LABEL_COLOUR = Theme.TEXT_SOFT;
    private static final int HELP = Theme.MUTED;
    // The colour the mockup starts on, the swatch named Orange
    private static final int START_COLOUR = 0xF08A24;
    // The creator's previews of this screen use this slot
    private static final int PREVIEW_SLOT = 0;
    // How long a created collection may take to arrive before the wizard stops waiting
    private static final long SYNC_TIMEOUT_MS = 10_000;
    //? if >=1.21.5 && <1.21.6 {
    /*// In front of everything the overview draws on 1.21.5, whose cards lift their badges and buttons 400
    private static final float OVERVIEW_DEPTH = 1000.0f;
    *///? }

    private String name = "";
    private String author;
    private int rgb = START_COLOUR;
    @Nullable
    private FilePicker.LocalFile logo;
    // The id the operator typed under "Advanced options"; empty follows the name
    private String typedSlug = "";
    private boolean advancedOpen;

    // The save in flight, then the collection it created while the wizard waits for its sync
    @Nullable
    private CreatorSave.Run run;
    private String sentName = "";
    @Nullable
    private String createdId;
    private long createdAt;

    // Nodes kept across rebuilds, so the logo's state and the replica's list stay
    private final UiDropZone logoZone;
    private final UiSwatches swatches;
    private final UiButton fromLogoButton;
    private final UiButton customButton;
    private final UiButton advancedButton;
    private final UiButton cancelButton;
    private final UiButton createButton;
    private final UiMachineReplica replica = new UiMachineReplica();
    @Nullable
    private EditBox slugBox;

    // Where build() put the labels drawContents() draws
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private int nameLabelY;
    private int nameStatusY;
    private int authorLabelY;
    private int authorHelpY = -1;
    private int colourLabelY;
    private int swatchHeight;
    private int advancedX;
    private int advancedWidth;
    private int idLabelY = -1;
    private int idNoteY = -1;
    private int previewLabelY;
    private int previewHelpY = -1;
    private int footerY;

    public CreatorWizardScreen(Screen parent) {
        super(tr("wizard.title"), parent);
        String player = Minecraft.getInstance().getUser().getName();
        this.author = CreatorHomeScreen.cut(player, WorldContentLimits.MAX_AUTHOR_LENGTH);
        this.logoZone = UiDropZone.single(this, FilePicker.Slot.LOGO, tr("wizard.logo"), file -> {
            this.logo = file;
            changed();
        }).help(tr("wizard.logo_help", WorldContentLimits.LOGO_MIN_SIZE, WorldContentLimits.LOGO_MAX_SIZE))
                .onRemove(() -> {
                    this.logo = null;
                    changed();
                });
        this.swatches = UiSwatches.colours(this.rgb, this::setColour);
        this.fromLogoButton = new UiButton(tr("wizard.from_logo"), UiButton.Style.LINK, this::colourFromLogo);
        this.customButton = new UiButton(tr("wizard.custom"), UiButton.Style.LINK,
                () -> UiColourPicker.open(this, this.rgb, this::setColour));
        this.advancedButton = new UiButton(tr("wizard.advanced"), UiButton.Style.LINK, () -> {
            this.advancedOpen = !this.advancedOpen;
            rebuild();
        });
        this.cancelButton = new UiButton(tr("cancel"), UiButton.Style.SECONDARY, this::onClose);
        this.createButton = new UiButton(tr("wizard.create"), UiButton.Style.PRIMARY, this::create);
        this.replica.setCompact(true);
        this.replica.setCollection(CreatorPreview.previewId(PREVIEW_SLOT));
        refreshPreview();
    }

    // Building

    @Override
    protected boolean drawsParentBelow() {
        // The overview is drawn under this screen, so it is laid out again when the window changes size
        return true;
    }

    @Override
    protected void build() {
        fitCard();
        int left = this.panelX + PAD;
        int right = this.panelX + this.panelWidth - PAD;
        int inner = right - left;
        this.leftX = left;
        this.leftWidth = Math.min(MAX_LEFT_WIDTH, Math.max(120, (inner - COLUMN_GAP) * 55 / 100));
        this.rightX = left + this.leftWidth + COLUMN_GAP;
        this.rightWidth = Math.max(0, right - this.rightX);
        this.footerY = DialogFrame.bodyBottom(this.panelY, this.panelHeight) - 8 - UiButton.HEIGHT;
        addRenderableWidget(DialogCloseButton.forDialog(this.panelX, this.panelY, this.panelWidth, tr("cancel"),
                button -> onClose()));
        int bodyTop = this.panelY + HEADER;
        int bodyBottom = this.footerY - 6;

        // A tall enough card puts the help lines under their fields, as the mockup does. It is
        // decided with the advanced options closed, so opening them never moves the form
        this.swatchHeight = this.swatches.heightFor(this.leftWidth);
        int formHeight = 2 * (LABEL + FIELD + ROW_GAP) + UiDropZone.HEIGHT + ROW_GAP
                + LABEL - 3 + this.swatchHeight + 2 + LABEL + ROW_GAP;
        boolean roomy = bodyTop + formHeight + HELP_LINES * LABEL + 4 + LABEL + 1 <= bodyBottom;

        // Name, with its length and whether it can be used
        int y = bodyTop;
        this.nameLabelY = y;
        textField("name", left, y + LABEL, this.leftWidth, WorldContentLimits.MAX_NAME_LENGTH, tr("wizard.name_hint"),
                this.name, this::nameTyped);
        y += LABEL + FIELD + ROW_GAP;
        if (roomy) {
            this.nameStatusY = y - 2;
            y += LABEL + 2;
        } else {
            this.nameStatusY = -1;
        }

        // Author, filled in with the player's name
        this.authorLabelY = y;
        textField("author", left, y + LABEL, this.leftWidth, WorldContentLimits.MAX_AUTHOR_LENGTH,
                tr("wizard.author_hint"), this.author, value -> {
                    this.author = value;
                    changed();
                });
        y += LABEL + FIELD + ROW_GAP;
        if (roomy) {
            this.authorHelpY = y - 2;
            y += (HELP_LINES - 1) * LABEL + 2;
        } else {
            this.authorHelpY = -1;
        }

        // Logo
        this.logoZone.at(left, y, this.leftWidth, UiDropZone.HEIGHT);
        addNode(this.logoZone);
        y += UiDropZone.HEIGHT + ROW_GAP;

        // Background colour: the swatches, then the chosen colour and the two other ways to pick one
        this.colourLabelY = y;
        this.swatches.select(this.rgb);
        this.swatches.at(left, y + LABEL - 3, this.leftWidth, this.swatchHeight);
        addNode(this.swatches);
        y += LABEL - 3 + this.swatchHeight + 2;
        int fromLogoWidth = this.font.width(this.fromLogoButton.label());
        int customWidth = this.font.width(this.customButton.label());
        this.fromLogoButton.at(left, y, fromLogoWidth, 10);
        this.customButton.at(left + fromLogoWidth + 12, y, customWidth, 10);
        this.fromLogoButton.disable(this.logo == null ? tr("wizard.no_logo") : null);
        addNode(this.fromLogoButton);
        addNode(this.customButton);
        y += LABEL + ROW_GAP;

        // Advanced options under the form, or under the preview when the card is too short for them there
        boolean advancedLeft = y + advancedHeight(this.leftWidth) <= bodyBottom;
        this.advancedX = advancedLeft ? left : this.rightX;
        this.advancedWidth = advancedLeft ? this.leftWidth : this.rightWidth;
        int advancedTop = advancedLeft ? y : bodyBottom - advancedHeight(this.rightWidth);
        buildAdvanced(advancedTop);

        // The machine's screen as it will show the collection
        this.previewLabelY = bodyTop;
        int replicaBottom = advancedLeft ? bodyBottom - LABEL : advancedTop - ROW_GAP;
        this.previewHelpY = advancedLeft ? bodyBottom - LABEL + 3 : -1;
        this.replica.at(this.rightX, bodyTop + LABEL, this.rightWidth, Math.max(0, replicaBottom - bodyTop - LABEL));
        addNode(this.replica);

        // Footer: Cancel on the left, the create button on the right with its note before it
        this.cancelButton.at(left, this.footerY, Math.max(60, UiButton.widthFor(this.font, this.cancelButton.label(),
                false)), UiButton.HEIGHT);
        int createWidth = Math.min(inner / 2, Math.max(CREATE_WIDTH, this.createButton.naturalWidth()));
        this.createButton.at(right - createWidth, this.footerY, createWidth, UiButton.HEIGHT);
        this.createButton.disable(problem());
        addNode(this.cancelButton);
        addNode(this.createButton);
    }

    // The card is the mockup's dialog at most, centred over the overview, and moves up on a
    // short window so the status line still fits under it rather than over the footer
    private void fitCard() {
        int cardWidth = Math.min(MAX_CARD_WIDTH, this.width - 2 * SCREEN_MARGIN);
        int cardHeight = Math.min(MAX_CARD_HEIGHT, this.height - 2 * SCREEN_MARGIN - STATUS_ROOM);
        this.panelWidth = Math.max(0, cardWidth);
        this.panelHeight = Math.max(0, cardHeight);
        this.panelX = (this.width - this.panelWidth) / 2;
        int highest = this.height - SCREEN_MARGIN - STATUS_ROOM - this.panelHeight;
        this.panelY = Math.max(SCREEN_MARGIN, Math.min((this.height - this.panelHeight) / 2, highest));
    }

    // The link, then while open the id's row and its note, wrapped to the width
    private int advancedHeight(int width) {
        if (!this.advancedOpen) {
            return LABEL + 1;
        }
        int noteLines = Draw.fit(this.font, tr("wizard.id_note").getString(), width, 2).size();
        return LABEL + 1 + FIELD + 2 + noteLines * Theme.LINE_HEIGHT;
    }

    private void buildAdvanced(int top) {
        this.advancedButton.setLabel(tr(this.advancedOpen ? "wizard.advanced_open" : "wizard.advanced"));
        int linkWidth = this.font.width(this.advancedButton.label());
        this.advancedButton.at(this.advancedX, top + 1, Math.min(linkWidth, this.advancedWidth), 10);
        addNode(this.advancedButton);
        if (!this.advancedOpen) {
            this.slugBox = null;
            this.idLabelY = -1;
            this.idNoteY = -1;
            return;
        }
        int rowY = top + LABEL + 1;
        int labelWidth = this.font.width(tr("wizard.id")) + 6;
        int fieldWidth = Math.max(40, Math.min(ID_FIELD_WIDTH, this.advancedWidth - labelWidth));
        this.idLabelY = rowY + 6;
        this.slugBox = textField("slug", this.advancedX + labelWidth, rowY, fieldWidth, Slugs.MAX_LENGTH,
                Component.literal(autoSlug()), this.typedSlug, this::slugTyped);
        this.idNoteY = rowY + FIELD + 2;
    }

    // Typing

    private void nameTyped(String value) {
        this.name = value;
        if (this.typedSlug.isEmpty() && this.slugBox != null) {
            // The empty id field shows the id the name makes as its hint
            this.slugBox.setHint(Component.literal(autoSlug()));
        }
        changed();
    }

    private void slugTyped(String value) {
        this.typedSlug = value;
        if (value.isEmpty() && this.slugBox != null) {
            this.slugBox.setHint(Component.literal(autoSlug()));
        }
        changed();
    }

    private void setColour(int chosen) {
        this.rgb = chosen & 0xFFFFFF;
        this.swatches.select(this.rgb);
        changed();
        if (this.swatches.heightFor(this.leftWidth) != this.swatchHeight) {
            // A custom colour's own tile went onto a row of its own, or left it
            rebuild();
        }
    }

    private void colourFromLogo() {
        FilePicker.LocalFile file = this.logo;
        if (file == null) {
            return;
        }
        DominantColour.ofPngLater(this, file.bytes(), colour -> {
            if (colour.isPresent()) {
                setColour(colour.getAsInt());
            } else {
                status(tr("wizard.no_colour"), Theme.RED);
            }
        });
    }

    /** Shows the change in the preview and in what the create button allows. */
    private void changed() {
        refreshPreview();
        this.createButton.disable(problem());
        this.fromLogoButton.disable(this.logo == null ? tr("wizard.no_logo") : null);
    }

    // The collection

    /** The id the name makes, free among the collections this client was sent. */
    private String autoSlug() {
        return Slugs.unique(Slugs.collection(this.name), Slugs::isCollectionTaken);
    }

    /** The id the collection will be created with: the one typed, or the one the name makes. */
    private String slug() {
        return this.typedSlug.isEmpty() ? autoSlug() : this.typedSlug;
    }

    /** Why the collection cannot be created as it is, or null when it can. */
    @Nullable
    private Component problem() {
        if (this.run != null || this.createdId != null) {
            return tr("wizard.creating");
        }
        if (ClientWorldCollections.records().size() >= WorldContentLimits.MAX_COLLECTIONS) {
            return tr("too_many_collections", WorldContentLimits.MAX_COLLECTIONS);
        }
        Component nameProblem = nameProblem();
        if (nameProblem != null) {
            return nameProblem;
        }
        String slug = slug();
        if (!WorldContentIds.isSlug(slug)) {
            return tr("slug_rule");
        }
        if (Slugs.isCollectionTaken(slug)) {
            return tr("slug_taken", slug);
        }
        if (!WorldCollectionRecord.isPlainText(this.author)) {
            return tr("wizard.author_rule");
        }
        return null;
    }

    @Nullable
    private Component nameProblem() {
        if (this.name.isBlank()) {
            return tr("wizard.name_empty");
        }
        if (!WorldCollectionRecord.isPlainText(this.name)) {
            return tr("name_rule");
        }
        return null;
    }

    /**
     * The collection as the form describes it. While the name or the id cannot be used, a
     * stand-in keeps the preview showing the rest.
     */
    private CreatorDraft draft() {
        String slug = slug();
        String shownName = this.name.strip();
        boolean usable = nameProblem() == null;
        CreatorDraft draft = CreatorDraft.create(WorldContentIds.isSlug(slug) ? slug : Slugs.COLLECTION_FALLBACK,
                usable ? shownName : tr("home.new").getString());
        if (WorldCollectionRecord.isPlainText(this.author)) {
            draft.setAuthor(this.author.strip());
        }
        draft.setRgb(this.rgb);
        if (this.logo != null) {
            draft.setLogo(CreatorDraft.FileRef.of(this.logo));
        }
        return draft;
    }

    private void refreshPreview() {
        draft().preview(PREVIEW_SLOT);
    }

    private void create() {
        if (problem() != null) {
            return;
        }
        CreatorDraft draft = draft();
        this.sentName = draft.name();
        status(Component.empty());
        CreatorSave.Run started = CreatorSave.save(draft, false, this::saved);
        // A save that could not be handed over has already told saved() before it returns
        this.run = started.isFinished() ? null : started;
        this.createButton.disable(problem());
    }

    private void saved(CreatorSave.Outcome outcome) {
        this.run = null;
        boolean shown = this.minecraft.screen == this;
        if (outcome.ok()) {
            this.createdId = outcome.collectionId();
            this.createdAt = Util.getMillis();
            if (shown) {
                openWhenSynced();
            } else {
                // The operator left while it was being sent: the overview shows it when it arrives
                revealInOverview(outcome.collectionId());
                UiToast.show(tr("wizard.created", this.sentName));
            }
            return;
        }
        if (shown) {
            status(outcome.message(), Theme.RED);
            this.createButton.disable(problem());
        } else if (outcome.ending() != CreatorSave.Ending.CANCELLED) {
            UiToast.show(outcome.message());
        }
    }

    /** Opens the editor once the server synced the new collection, or gives up after a while. */
    private void openWhenSynced() {
        String id = this.createdId;
        if (id == null || this.minecraft.screen != this) {
            return;
        }
        if (ClientWorldCollections.record(id) != null) {
            this.createdId = null;
            revealInOverview(id);
            BlockPos machinePos = this.parent instanceof CreatorHomeScreen home ? home.machinePos() : null;
            // On its figures, which a new collection needs next
            this.minecraft.setScreen(new CreatorEditorScreen(this.parent, id, machinePos,
                    CreatorEditorScreen.Tab.FIGURES));
        } else if (Util.getMillis() - this.createdAt > SYNC_TIMEOUT_MS) {
            this.createdId = null;
            revealInOverview(id);
            UiToast.show(tr("wizard.not_synced", this.sentName));
            leave();
        }
    }

    private void revealInOverview(String id) {
        if (this.parent instanceof CreatorHomeScreen home) {
            home.reveal(id);
        }
    }

    @Override
    protected void onSync() {
        // A new collection may have taken the id the name makes; build() and the button follow
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.screen == this) {
            openWhenSynced();
        }
    }

    // Drawing

    //? if >=1.21.5 && <1.21.6 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // Ends the lift drawBackground started
        GuiPose.pop(graphics);
    }
    *///? }

    /**
     * The overview this card opened over, dimmed. The card has to cover the overview's items
     * and figures, which each version needs done its own way, as SettingsScreen does it.
     */
    @Override
    protected void drawBackground(GuiGraphics graphics, float partialTick) {
        // No pointer, so nothing under the card lights up or shows a tooltip
        this.parent.render(graphics, -1, -1, partialTick);
        GuiPose.flush(graphics);
        //? if >=1.21.6 {
        /*GuiPose.nextStratum(graphics);
        *///? } elif >=1.21.5 {
        /*// 1.21.5 removed the global depth state, so the card is pushed forward in the pose
        GuiPose.push(graphics);
        GuiPose.lift(graphics, OVERVIEW_DEPTH);
        *///? } elif >=1.21.2 {
        /*RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256);
        *///? } else {
        RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256, false);
        //? }
        graphics.fill(0, 0, this.width, this.height, Theme.MODAL_DIM);
        GuiPose.nextStratum(graphics);
    }

    @Override
    protected void drawPanel(GuiGraphics graphics) {
        // The title is drawn with the steps, which share the header with it
        DialogFrame.draw(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight,
                DialogFrame.Accent.CREATE, PixelIcon.PLUS, Component.empty());
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawHeader(graphics);
        int left = this.leftX;
        int width = this.leftWidth;

        // Name: its label, its length and whether it can be used
        Draw.string(graphics, this.font, tr("wizard.name"), left, this.nameLabelY, LABEL_COLOUR);
        String counter = this.name.length() + "/" + WorldContentLimits.MAX_NAME_LENGTH;
        int counterX = left + width - this.font.width(counter);
        Draw.string(graphics, this.font, counter, counterX, this.nameLabelY,
                this.name.length() >= WorldContentLimits.MAX_NAME_LENGTH ? Theme.AMBER : HELP);
        Component nameProblem = nameProblem();
        Component nameStatus = nameProblem == null ? tr("wizard.name_ok") : nameProblem;
        int statusColour = nameProblem == null ? Theme.GREEN : this.name.isEmpty() ? HELP : Theme.RED;
        if (this.nameStatusY >= 0) {
            Draw.text(graphics, this.font, nameStatus.getString(), left, this.nameStatusY, width, statusColour);
        } else {
            // A short card says it on the label's line, before the counter
            int labelEnd = left + this.font.width(tr("wizard.name")) + 8;
            Draw.text(graphics, this.font, nameStatus.getString(), labelEnd, this.nameLabelY,
                    Math.max(0, counterX - 8 - labelEnd), statusColour);
        }

        // Author
        Draw.string(graphics, this.font, tr("wizard.author"), left, this.authorLabelY, LABEL_COLOUR);
        if (!WorldCollectionRecord.isPlainText(this.author)) {
            int labelEnd = left + this.font.width(tr("wizard.author")) + 8;
            Draw.text(graphics, this.font, tr("wizard.author_rule").getString(), labelEnd, this.authorLabelY,
                    Math.max(0, left + width - labelEnd), Theme.RED);
        }
        if (this.authorHelpY >= 0) {
            Draw.lines(graphics, this.font, tr("wizard.author_help").getString(), left, this.authorHelpY, width,
                    HELP_LINES - 1, HELP);
        }

        // Background colour, with the chosen one's name and hex on the right of its label
        String value = tr("wizard.colour_value", UiSwatches.nameOf(this.rgb), UiColourPicker.hex(this.rgb)).getString();
        int valueX = left + width - this.font.width(value);
        Draw.string(graphics, this.font, value, valueX, this.colourLabelY, Theme.TEXT_SOFT);
        Component colourLabel = tr("wizard.colour");
        Draw.string(graphics, this.font, colourLabel, left, this.colourLabelY, LABEL_COLOUR);
        int helpX = left + this.font.width(colourLabel);
        String colourHelp = tr("wizard.colour_help").getString();
        if (helpX + this.font.width(colourHelp) <= valueX - 8) {
            // Whole or not at all: a cut "· the…" says nothing
            Draw.string(graphics, this.font, colourHelp, helpX, this.colourLabelY, HELP);
        }

        // Advanced options: the id and what to know about it
        graphics.fill(this.advancedX, this.advancedButton.bounds().y() - 2, this.advancedX + this.advancedWidth,
                this.advancedButton.bounds().y() - 1, Theme.SEPARATOR);
        if (this.idLabelY >= 0) {
            Draw.string(graphics, this.font, tr("wizard.id"), this.advancedX, this.idLabelY, Theme.CONTROL_TEXT);
            String slug = slug();
            if (this.typedSlug.isEmpty() || WorldContentIds.isSlug(slug) && !Slugs.isCollectionTaken(slug)) {
                Draw.lines(graphics, this.font, tr("wizard.id_note").getString(), this.advancedX, this.idNoteY,
                        this.advancedWidth, 2, HELP);
            } else {
                Component rule = WorldContentIds.isSlug(slug) ? tr("slug_taken", slug) : tr("slug_rule");
                Draw.lines(graphics, this.font, rule.getString(), this.advancedX, this.idNoteY, this.advancedWidth, 2,
                        Theme.RED);
            }
        }

        // The preview's label and help
        Draw.text(graphics, this.font, tr("wizard.preview").getString(), this.rightX, this.previewLabelY,
                this.rightWidth, LABEL_COLOUR);
        if (this.previewHelpY >= 0) {
            Draw.text(graphics, this.font, tr("wizard.preview_help").getString(), this.rightX, this.previewHelpY,
                    this.rightWidth, HELP);
        }

        drawFooterNote(graphics);
    }

    /**
     * The title in the header's strip, and on its right the three steps, the first one current,
     * then how long it takes, as room allows.
     */
    private void drawHeader(GuiGraphics graphics) {
        int left = this.panelX + 10 + PixelIcon.width(PixelIcon.PLUS) + 6;
        int right = this.panelX + this.panelWidth - CLOSE_ROOM;
        int middle = (this.panelY + 2 + this.panelY + DialogFrame.HEADER_HEIGHT - 1) / 2;
        int titleWidth = PixelFont.width(this.title, PixelFont.crispScale(1.0f));

        List<Component> steps = List.of(tr("wizard.step.look"), tr("wizard.step.figures"),
                tr("wizard.step.publish"));
        int stepsWidth = 0;
        for (Component step : steps) {
            stepsWidth += STEP_BOX + 4 + this.font.width(step);
        }
        stepsWidth += (steps.size() - 1) * (STEP_LINE + 8);
        Component time = tr("wizard.time");
        int timeWidth = this.font.width(time) + 10;
        // The estimate goes first when the header runs out of room, then the steps
        boolean withTime = right - left - titleWidth - 12 >= stepsWidth + timeWidth;
        boolean withSteps = right - left - titleWidth - 12 >= stepsWidth;
        int x = right - stepsWidth - (withTime ? timeWidth : 0);
        Draw.heading(graphics, this.title, left, middle + 0.5f, Math.max(0, (withSteps ? x - 12 : right) - left), 1.0f,
                Theme.TEXT);
        if (!withSteps) {
            return;
        }
        int boxY = middle - STEP_BOX / 2;
        for (int i = 0; i < steps.size(); i++) {
            boolean current = i == 0;
            if (current) {
                graphics.fill(x, boxY, x + STEP_BOX, boxY + STEP_BOX, Theme.TEXT);
            } else {
                Draw.outline(graphics, x, boxY, STEP_BOX, STEP_BOX, STEP_EDGE);
            }
            String number = String.valueOf(i + 1);
            int numberX = x + (STEP_BOX - this.font.width(number) + 1) / 2;
            if (current) {
                graphics.drawString(this.font, number, numberX, boxY + 2, DialogFrame.Accent.CREATE.strip, false);
            } else {
                Draw.string(graphics, this.font, number, numberX, boxY + 2, STEP_OFF);
            }
            x += STEP_BOX + 4;
            Draw.string(graphics, this.font, steps.get(i).getString(), x, boxY + 2, current ? Theme.TEXT : STEP_OFF);
            x += this.font.width(steps.get(i));
            if (i < steps.size() - 1) {
                graphics.fill(x + 4, boxY + 5, x + 4 + STEP_LINE, boxY + 6, STEP_EDGE);
                x += STEP_LINE + 8;
            }
        }
        if (withTime) {
            Draw.string(graphics, this.font, time.getString(), x + 10, boxY + 2, STEP_OFF);
        }
    }

    /** Before the create button: what creating does, or how far it is while it is sent. */
    private void drawFooterNote(GuiGraphics graphics) {
        int noteLeft = this.cancelButton.bounds().right() + 12;
        int noteRight = this.createButton.bounds().x() - 8;
        int room = Math.max(0, noteRight - noteLeft);
        CreatorSave.Run sending = this.run;
        if (sending == null && this.createdId == null) {
            List<String> lines = Draw.fit(this.font, tr("wizard.note").getString(), room, 2);
            int y = this.footerY + (UiButton.HEIGHT - lines.size() * Theme.LINE_HEIGHT) / 2 + 1;
            for (String line : lines) {
                Draw.string(graphics, this.font, line, noteRight - this.font.width(line), y, HELP);
                y += Theme.LINE_HEIGHT;
            }
            return;
        }
        Component text;
        float fraction;
        ClientWorldUploads.Progress upload = sending != null ? sending.progress().upload() : null;
        if (sending == null) {
            text = tr("wizard.opening");
            fraction = 1.0f;
        } else if (upload != null && !upload.file().isEmpty() && upload.total() > 0) {
            int percent = (int) (100L * upload.sent() / upload.total());
            text = tr("wizard.uploading", upload.file(), percent);
            fraction = upload.sent() / (float) upload.total();
        } else {
            text = tr("wizard.creating");
            fraction = upload != null && upload.file().isEmpty() ? 1.0f : 0.0f;
        }
        int textY = this.footerY + 3;
        Draw.text(graphics, this.font, text.getString(), noteLeft, textY, room, Theme.PENDING);
        Draw.progressBar(graphics, noteLeft, textY + 11, room, 3, fraction, Theme.TRACK, Theme.GREEN);
    }

    @Override
    protected int statusY() {
        // Under the card, on the dimmed overview, where fitCard() left room for it
        return this.panelY + this.panelHeight + STATUS_GAP;
    }

    @Override
    protected int toastBottom() {
        // A message left from the overview, such as an archive's undo, goes above the card
        // where there is room, so it covers neither the form nor its buttons
        return this.panelY >= TOAST_ROOM ? this.panelY - 4 : super.toastBottom();
    }

    // Input

    @Override
    protected boolean shortcut(UiInput.Key key) {
        if (key.enter()) {
            return this.createButton.press();
        }
        return false;
    }

    // Closing

    @Override
    public void onClose() {
        // A batch already sent is saved anyway, and the overview selects it when it arrives
        CreatorSave.Run sending = this.run;
        if (sending != null) {
            sending.cancel();
        }
        String created = this.createdId;
        if (created != null) {
            // Created, but left before its sync arrived: the overview selects it once it does
            this.createdId = null;
            revealInOverview(created);
            UiToast.show(tr("wizard.created", this.sentName));
        }
        leave();
    }

    @Override
    public void removed() {
        super.removed();
        this.replica.close();
        CreatorPreview.clear(PREVIEW_SLOT);
        // The logo stays loaded while a synced collection names it, as the new one does
        ClientPreviewAssets.release();
    }
}
