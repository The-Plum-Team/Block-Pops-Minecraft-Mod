package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiBoxView;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.client.worldcontent.OwnSkin;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.registry.ModSounds;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The creator home's form for a new World collection ({@link CreatorHomeScreen}), shown in its
 * content panel while the sidebar's temporary "New" row is selected: a collection starts from its
 * name alone, and everything else is set later in the editor.
 *
 * <p>The form holds the name, with its length beside its label, and under it, faint, the id the
 * name makes, free among the collections this client was sent, once a name is typed. Pointing at
 * that line, or reaching it with Tab, offers to change the id, which then gets a labelled field of
 * its own, showing the id it falls back to while it is empty: an id cannot change once the
 * collection exists, and an empty field follows the name again. A message under them says only
 * what is wrong, or that the collection is being created; "Create" says in its tooltip why it
 * cannot be used yet. Beside the form, the box the collection will get holds a figure in the
 * player's own skin, from the game's skin cache or the default skin, in the collection's colour as
 * the home's showcase will show it, and turns when dragged. That box is a preview of its own,
 * which is never sent, so the sample figure is never created.
 *
 * <p>On a wide canvas the form starts under the subtitle, its label level with the box's top; on a
 * narrow one, which the form and box nearly fill, the two are centred in the height there is.
 *
 * <p>"Create" (or Enter) sends one batch that creates the collection as a draft and sets its
 * author, the player's name, and its orange colour; a slug the server refuses as taken is retried
 * with the next one. From then until the server has synced the new collection, at most for
 * {@value #SYNC_TIMEOUT_MS} ms, the home is locked and only Cancel, Escape and the creator's cross
 * act. Once it has, the home selects it and the collection editor ({@link CreatorEditorScreen})
 * opens on its figures; a sync that does not come in time is said in a toast, and the home
 * selects the collection when it does. A refusal is said under the form and unlocks it again,
 * and the field that had the keyboard before Create gets it back.
 *
 * <p>Cancel drops the batch if it has not been sent yet. One already sent may still be saved:
 * the home then says so in a toast and selects the collection once it arrives, but never opens it
 * in the editor after a cancel.
 *
 * <p>The form lives as long as it is open, so what is typed survives the home's rebuilds; the
 * home adds its nodes and fields again in {@link #build} and draws its labels in {@link #draw}.
 */
final class CreateForm {
    /** The colour a new collection starts with, the swatch named Orange. */
    static final int START_COLOUR = 0xF08A24;
    // How long a created collection may take to arrive before the form stops waiting for it
    private static final long SYNC_TIMEOUT_MS = 10_000;
    // The creator's preview of a new collection uses this slot
    private static final int PREVIEW_SLOT = 0;
    // From the content panel's top: the subtitle, then the block of the form and its box, raised
    // on a short canvas; the block keeps this far above the panel's footer line
    private static final int SUBTITLE_Y = 38;
    private static final int SUBTITLE_Y_SHORT = 26;
    private static final int BLOCK_TOP = 54;
    private static final int BLOCK_TOP_SHORT = 40;
    private static final int BLOCK_BOTTOM_GAP = 8;
    // The box: square, about this share of the content's width, within bounds
    private static final float PREVIEW_SHARE = 0.38f;
    private static final int MIN_PREVIEW = 96;
    private static final int MIN_PREVIEW_SHORT = 80;
    private static final int MAX_PREVIEW = 170;
    // Its caption, under it in up to two lines; a short canvas leaves it out
    private static final int CAPTION_GAP = 6;
    private static final int CAPTION_LINES = 2;
    // Between the form and the box
    private static final int FORM_GAP = 20;
    private static final int FORM_GAP_NARROW = 12;
    // The form's rows, from its top: the label, the name, the id as a line or as a labelled
    // field, the message and the buttons
    private static final int LABEL = 8;
    private static final int LABEL_GAP = 2;
    private static final int ROW_GAP = 4;
    private static final int ID_LINE = 10;
    private static final int MESSAGE_LINES = 2;
    private static final int BUTTON_GAP = 8;
    private static final int MIN_CREATE_WIDTH = 78;
    // Cancel beside Create, or on its own line under it on a narrow canvas
    private static final int CANCEL_GAP = 14;
    private static final int CANCEL_GAP_NARROW = 6;
    private static final int LINK_HEIGHT = 10;
    // Between the id and the link that changes it
    private static final int LINK_GAP = 6;

    private final CreatorHomeScreen home;
    private String name = "";
    // The id the operator typed; empty follows the name
    private String typedSlug = "";
    private boolean slugOpen;
    // The skin the box's sample figure wears once it is read, and its name
    @Nullable
    private FilePicker.LocalFile sampleSkin;
    private final String sampleName;
    private boolean disposed;
    // The create in flight, then the collection it created while the form waits for its sync
    @Nullable
    private CreatorSave.Run run;
    @Nullable
    private String createdId;
    private long createdAt;
    private String sentName = "";
    private boolean cancelled;
    // Whether the id's field had the keyboard when Create was pressed, to give it back on a refusal
    private boolean slugHadKeyboard;
    // Why the last create did not work, shown until the next edit
    @Nullable
    private Component failure;
    // Which field takes the keyboard at the next build
    private boolean focusName = true;
    private boolean focusSlug;

    // Nodes kept across rebuilds, so the box keeps its turn and the buttons their state
    private final UiBoxView preview = new UiBoxView().modeSwitch(false).dragHint(false).stageBackground(true);
    private final UiButton createButton;
    private final UiButton cancelButton;
    private final IdLine idLine = new IdLine();
    @Nullable
    private EditBox nameBox;
    @Nullable
    private EditBox slugBox;

    // Where build() put what draw() draws
    private UiNode.Rect content = UiNode.Rect.EMPTY;
    private int formX;
    private int formY;
    private int formWidth;
    private int idY;
    private int slugFieldY;
    private int messageY;
    private UiNode.Rect previewBounds = UiNode.Rect.EMPTY;
    private List<String> caption = List.of();
    private int captionWidth;

    CreateForm(CreatorHomeScreen home) {
        this.home = home;
        String player = Minecraft.getInstance().getUser().getName();
        this.sampleName = CreatorHomeScreen.cut(player.isBlank() ? Slugs.FIGURE_FALLBACK : player,
                WorldContentLimits.MAX_NAME_LENGTH);
        this.createButton = new UiButton(tr("create.create"), UiButton.Style.PRIMARY, this::create);
        this.cancelButton = new UiButton(tr("cancel"), UiButton.Style.LINK, this::cancel);
        this.preview.setButtons(false);
        readSampleSkin();
    }

    // The sample figure wears the player's own skin, read from this computer only
    private void readSampleSkin() {
        OwnSkin.read(result -> {
            FilePicker.PickResult pick = result.pick();
            if (this.disposed || pick.kind() != FilePicker.PickResult.Kind.ACCEPTED || pick.file() == null) {
                // Without a skin the box stays empty, which still shows its colour and frame
                return;
            }
            this.sampleSkin = pick.file();
            if (this.home.isShown()) {
                refreshPreview();
            }
        });
    }

    // Building

    /**
     * Adds the form's nodes and fields to the home for its content panel; runs on every rebuild
     * of the home while the form is open, so it reads the form as it is now.
     */
    void build(UiNode.Rect area) {
        this.content = area;
        Font font = Minecraft.getInstance().font;
        boolean narrow = this.home.narrow();
        boolean isShort = this.home.isShort();
        int inner = this.home.inner();
        int top = area.y() + (isShort ? BLOCK_TOP_SHORT : BLOCK_TOP);
        int room = Math.max(0, this.home.footerY() - BLOCK_BOTTOM_GAP - top);

        // The box, square, with its caption under it where the canvas has the height. The caption
        // may reach half the gap past the box on either side: into the gap before it, and into
        // the panel's padding, which is wider
        int gap = narrow ? FORM_GAP_NARROW : FORM_GAP;
        int captionRoom = isShort ? 0 : CAPTION_GAP + CAPTION_LINES * Theme.LINE_HEIGHT;
        int size = SidebarScreen.clamp(Math.min(Math.round(inner * PREVIEW_SHARE), room - captionRoom),
                isShort ? MIN_PREVIEW_SHORT : MIN_PREVIEW, MAX_PREVIEW);
        this.captionWidth = size + gap;
        this.caption = isShort ? List.of()
                : Draw.fit(font, tr("create.preview").getString(), this.captionWidth, CAPTION_LINES);
        int previewHeight = size + (this.caption.isEmpty() ? 0 : CAPTION_GAP + this.caption.size() * Theme.LINE_HEIGHT);

        // The form beside it. On a wide canvas both start under the subtitle, the form's label
        // level with the box's top. On a narrow one, which they nearly fill, the block is centred
        // with the id as one line, so opening the id's field moves only what is under it, and
        // raised where the open field would pass the footer line
        this.formWidth = Math.max(0, inner - size - gap);
        this.formX = this.home.left();
        int formHeight = formHeight(narrow, false);
        int previewY;
        if (narrow || isShort) {
            int block = Math.max(formHeight, previewHeight);
            int blockY = top + Math.max(0, (room - block) / 2);
            int overflow = blockY + (block - formHeight) / 2 + formHeight(narrow, true) - (top + room);
            blockY = Math.max(top, blockY - Math.max(0, overflow));
            this.formY = blockY + (block - formHeight) / 2;
            previewY = blockY + (block - previewHeight) / 2;
        } else {
            this.formY = top;
            previewY = top;
        }
        this.previewBounds = new UiNode.Rect(this.home.right() - size, previewY, size, size);

        boolean locked = locked();
        int fieldY = this.formY + LABEL + LABEL_GAP;
        this.nameBox = this.home.field("create_name", this.formX, fieldY, this.formWidth,
                WorldContentLimits.MAX_NAME_LENGTH, tr("wizard.name_hint"), this.name, this::nameTyped);
        this.nameBox.active = !locked;
        this.idY = fieldY + UiField.HEIGHT + ROW_GAP;
        if (this.slugOpen) {
            // Under a label of its own; while empty it shows, faint, the id it falls back to,
            // which a field's hint would hide while the field has the keyboard
            this.slugFieldY = this.idY + LABEL + LABEL_GAP;
            this.slugBox = this.home.field("create_slug", this.formX, this.slugFieldY, this.formWidth,
                    Slugs.MAX_LENGTH, tr("wizard.id"), this.typedSlug, this::slugTyped);
            this.slugBox.setHint(Component.empty());
            showFallbackSlug();
            this.slugBox.active = !locked;
            this.messageY = this.slugFieldY + UiField.HEIGHT + ROW_GAP;
        } else {
            this.slugBox = null;
            this.idLine.at(this.formX, this.idY, this.formWidth, ID_LINE);
            this.home.add(this.idLine);
            this.messageY = this.idY + ID_LINE + ROW_GAP;
        }

        // Create, then Cancel beside it, or under it where the form is narrow
        int buttonY = this.messageY + MESSAGE_LINES * Theme.LINE_HEIGHT + BUTTON_GAP;
        int createWidth = narrow ? this.formWidth
                : Math.min(this.formWidth, Math.max(MIN_CREATE_WIDTH, this.createButton.naturalWidth()));
        this.createButton.at(this.formX, buttonY, createWidth, UiButton.HEIGHT);
        this.createButton.disable(problem());
        this.home.add(this.createButton);
        int cancelWidth = font.width(this.cancelButton.label());
        if (narrow) {
            this.cancelButton.at(this.formX, buttonY + UiButton.HEIGHT + CANCEL_GAP_NARROW,
                    Math.min(cancelWidth, this.formWidth), LINK_HEIGHT);
        } else {
            int cancelX = this.formX + createWidth + CANCEL_GAP;
            int cancelRoom = Math.max(0, this.formX + this.formWidth - cancelX);
            this.cancelButton.at(cancelX, buttonY, Math.min(cancelWidth, cancelRoom), UiButton.HEIGHT);
        }
        this.home.add(this.cancelButton);

        UiNode.Rect box = this.previewBounds;
        this.preview.at(box.x(), box.y(), box.width(), box.height());
        this.home.add(this.preview);
        // Without a model to spare, the view shows its background alone
        this.home.budgetPreview(granted -> this.preview.setCollection(granted > 0
                ? CreatorPreview.previewId(PREVIEW_SLOT) : null));
        refreshPreview();

        if (locked) {
            this.focusName = false;
            this.focusSlug = false;
        } else if (this.focusName) {
            this.focusName = false;
            this.home.focusField(this.nameBox);
        }
    }

    /**
     * The home was built again for a change of the form's own, which gives the keyboard back to
     * the field that had it: the id's field takes it instead once it has opened.
     */
    void rebuilt() {
        if (this.focusSlug && this.slugBox != null && !locked()) {
            this.focusSlug = false;
            this.home.focusField(this.slugBox);
        }
    }

    // The form's height: its label, the name, the id as a line or a labelled field, the message,
    // Create, and Cancel under it where the form is narrow
    private static int formHeight(boolean narrow, boolean slugField) {
        int id = slugField ? LABEL + LABEL_GAP + UiField.HEIGHT : ID_LINE;
        int height = LABEL + LABEL_GAP + UiField.HEIGHT + ROW_GAP + id + ROW_GAP
                + MESSAGE_LINES * Theme.LINE_HEIGHT + BUTTON_GAP + UiButton.HEIGHT;
        return narrow ? height + CANCEL_GAP_NARROW + LINK_HEIGHT : height;
    }

    /** Gives the name field the keyboard at the next build, as when the home is shown again. */
    void focusName() {
        this.focusName = true;
    }

    /** Gives the name field the keyboard now, unless the form is locked. */
    void focusNameNow() {
        if (!locked() && this.nameBox != null) {
            this.home.focusField(this.nameBox);
        }
    }

    // Typing

    private void nameTyped(String value) {
        this.name = value;
        this.failure = null;
        showFallbackSlug();
        changed();
    }

    private void slugTyped(String value) {
        this.typedSlug = value;
        this.failure = null;
        showFallbackSlug();
        changed();
    }

    // The empty id field shows, faint, the id the name makes, which it falls back to, cut to the
    // field's text area; drawn after the cursor, so it shows while the field has the keyboard too
    private void showFallbackSlug() {
        EditBox field = this.slugBox;
        if (field != null) {
            field.setSuggestion(this.typedSlug.isEmpty()
                    ? Draw.cut(Minecraft.getInstance().font, autoSlug(), Math.max(0, field.getWidth() - 2)) : null);
        }
    }

    /** Swaps the id's line for a field of its own, which takes the keyboard. */
    private void openSlug() {
        if (locked() || this.slugOpen) {
            return;
        }
        this.slugOpen = true;
        this.focusSlug = true;
        this.home.formChanged(this);
    }

    // Shows the change in the box and in what Create allows
    private void changed() {
        refreshPreview();
        this.createButton.disable(problem());
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

    /** Whether the home is locked: from Create until the new collection's sync, or the wait ends. */
    boolean locked() {
        return this.run != null || this.createdId != null;
    }

    /**
     * Why the collection cannot be created as it is, or null when it can: one is being created,
     * the world has every collection it may, the name is missing or not plain text, or the id
     * typed is not an id or is taken.
     */
    @Nullable
    private Component problem() {
        if (locked()) {
            return tr("wizard.creating");
        }
        Component full = CollectionActions.tooManyCollections();
        if (full != null) {
            return full;
        }
        if (this.name.isBlank()) {
            return tr("wizard.name_empty");
        }
        return wrongInput();
    }

    /** What is wrong with what was typed, or null: a name that is not plain text, or an id typed wrong or taken. */
    @Nullable
    private Component wrongInput() {
        if (!this.name.isEmpty() && !WorldCollectionRecord.isPlainText(this.name)) {
            return tr("name_rule");
        }
        String slug = slug();
        if (!WorldContentIds.isSlug(slug)) {
            return tr("slug_rule");
        }
        if (Slugs.isCollectionTaken(slug)) {
            return tr("slug_taken", slug);
        }
        return null;
    }

    /**
     * The box's own collection: the name typed, or a stand-in while it cannot be used, in the
     * orange a new collection starts with, with no box art and one figure in the player's skin,
     * so the box never shows empty. It is shown in the preview slot only, and never sent.
     */
    private void refreshPreview() {
        if (this.disposed) {
            return;
        }
        String slug = slug();
        String shownName = !this.name.isBlank() && WorldCollectionRecord.isPlainText(this.name) ? this.name.strip()
                : tr("home.new").getString();
        CreatorDraft draft = CreatorDraft.create(WorldContentIds.isSlug(slug) ? slug : Slugs.COLLECTION_FALLBACK,
                CreatorHomeScreen.cut(shownName, WorldContentLimits.MAX_NAME_LENGTH));
        draft.setRgb(START_COLOUR);
        FilePicker.LocalFile skin = this.sampleSkin;
        if (skin != null) {
            draft.addFigure(this.sampleName, WorldCollectionRecord.Kind.SKIN, CreatorDraft.FileRef.of(skin), null, null,
                    1.0f);
        }
        draft.preview(PREVIEW_SLOT);
    }

    /** Presses Create as a click would, when it can be used. */
    void submit() {
        this.createButton.press();
    }

    private void create() {
        if (problem() != null) {
            return;
        }
        String slug = slug();
        String shownName = this.name.strip();
        String author = authorName();
        int rgb = START_COLOUR;
        this.sentName = shownName;
        this.failure = null;
        this.cancelled = false;
        this.slugHadKeyboard = this.slugBox != null && this.home.getFocused() == this.slugBox;
        // The batch is made again for each slug a retry after ID_TAKEN tries
        CreatorSave.Run started = CreatorSave.create(slug, List.of(), next -> new WorldCollectionEdit.Batch(List.of(
                new WorldCollectionEdit.CreateDraft(next, shownName),
                new WorldCollectionEdit.SetCollection(WorldContentIds.collectionId(next), shownName, author,
                        rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, WorldCollectionEdit.BOX_NONE, "", ""))),
                this::saved);
        // A create that could not be handed over has already told saved() before it returns
        this.run = started.isFinished() ? null : started;
        this.home.formChanged(this);
    }

    // The player's name as the collection's author, or none when it cannot be one
    private static String authorName() {
        String player = CreatorHomeScreen.cut(Minecraft.getInstance().getUser().getName(),
                WorldContentLimits.MAX_AUTHOR_LENGTH);
        return WorldCollectionRecord.isPlainText(player) ? player : "";
    }

    private void saved(CreatorSave.Outcome outcome) {
        this.run = null;
        if (this.cancelled || !this.home.isShown()) {
            // Cancelled, or the creator closed meanwhile: a collection created anyway is
            // selected once it arrives, and never opened in the editor
            if (outcome.ok()) {
                this.home.reveal(outcome.collectionId());
                UiToast.show(tr("wizard.created", this.sentName));
            } else if (!this.cancelled && outcome.ending() != CreatorSave.Ending.CANCELLED) {
                UiToast.show(outcome.message());
            }
            if (!this.cancelled) {
                this.home.closeForm(this);
            }
            return;
        }
        if (outcome.ok()) {
            this.createdId = outcome.collectionId();
            this.createdAt = Util.getMillis();
            openWhenSynced();
            if (this.createdId != null) {
                // Still waiting for its sync: the message says it is opening
                this.home.formChanged(this);
            }
            return;
        }
        this.failure = outcome.message();
        // The lock lifts, and the field that had the keyboard gets it back to put it right: the
        // id's, when it had it or the server found the id taken, else the name's
        boolean slugFirst = this.slugOpen
                && (this.slugHadKeyboard || outcome.status().result() == WorldContentStatus.Result.ID_TAKEN);
        this.focusSlug = slugFirst;
        this.focusName = !slugFirst;
        this.home.formChanged(this);
    }

    /** Runs once per client tick while the home is shown: the wait for the new collection's sync. */
    void tick() {
        if (this.createdId != null) {
            openWhenSynced();
        }
    }

    /** Opens the editor once the server synced the new collection, or gives up after a while. */
    private void openWhenSynced() {
        String id = this.createdId;
        if (id == null || !this.home.isShown()) {
            return;
        }
        if (ClientWorldCollections.record(id) != null) {
            this.createdId = null;
            this.home.created(this, id);
        } else if (Util.getMillis() - this.createdAt > SYNC_TIMEOUT_MS) {
            this.createdId = null;
            UiToast.show(tr("wizard.not_synced", this.sentName));
            this.home.closeForm(this);
            this.home.reveal(id);
        }
    }

    /**
     * Cancel, Escape or the creator's cross: drops the create if it has not been sent yet and
     * closes the form. A create already sent may still be saved, or may have been: the home then
     * says so and selects the collection once it arrives, without opening it.
     */
    void cancel() {
        this.cancelled = true;
        CreatorSave.Run sending = this.run;
        if (sending != null) {
            // A batch that has not started is dropped, which tells saved() at once
            sending.cancel();
        }
        String created = this.createdId;
        this.createdId = null;
        this.home.closeForm(this);
        if (created != null) {
            this.home.reveal(created);
            UiToast.show(tr("wizard.created", this.sentName));
        }
    }

    /** Lets go of the preview while the home is not shown; the next build shows it again. */
    void hide() {
        this.preview.close();
        CreatorPreview.clear(PREVIEW_SLOT);
        // The sample skin stays loaded only while a synced collection names it, which none does
        ClientPreviewAssets.release();
    }

    /** The form closed: its preview goes, and a skin read late is dropped. */
    void dispose() {
        this.disposed = true;
        hide();
    }

    // Input

    /**
     * A key in one of the form's fields: Enter creates the collection, and Tab from the name goes
     * on to the id's line, which offers to change the id.
     */
    boolean fieldKey(EditBox field, UiInput.Key key) {
        if (field != this.nameBox && field != this.slugBox) {
            return false;
        }
        if (key.enter()) {
            submit();
            return true;
        }
        if (field == this.nameBox && key.is(InputConstants.KEY_TAB) && !key.shift() && !this.slugOpen && !locked()
                && this.idLine.visible()) {
            this.home.focusNode(this.idLine);
            return true;
        }
        return false;
    }

    // Drawing

    /** Draws the form's labels, its message and the box's caption, under the form's nodes. */
    void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int inner = this.home.inner();
        int subtitleY = this.content.y() + (this.home.isShort() ? SUBTITLE_Y_SHORT : SUBTITLE_Y);
        drawLine(graphics, font, tr("create.subtitle"), this.formX, subtitleY, inner, Theme.MUTED, mouseX, mouseY);

        // The name's label, shrunk to leave its length room, and the length, amber once it is full
        String counter = this.name.length() + "/" + WorldContentLimits.MAX_NAME_LENGTH;
        int counterX = this.formX + this.formWidth - font.width(counter);
        SidebarScreen.smallLabel(graphics, tr("wizard.name"), this.formX, this.formY + LABEL / 2,
                Math.max(0, counterX - 8 - this.formX), Theme.TEXT_SOFT);
        Draw.string(graphics, font, counter, counterX, this.formY,
                this.name.length() >= WorldContentLimits.MAX_NAME_LENGTH ? Theme.AMBER : Theme.FAINT);

        // The id's field under its label, with what an id is as its tooltip
        EditBox slugField = this.slugBox;
        if (slugField != null) {
            SidebarScreen.smallLabel(graphics, tr("wizard.id"), this.formX, this.idY + LABEL / 2, this.formWidth,
                    Theme.TEXT_SOFT);
            if (mouseX >= this.formX && mouseX < this.formX + this.formWidth && mouseY >= this.slugFieldY
                    && mouseY < this.slugFieldY + UiField.HEIGHT) {
                UiTooltip.set(SidebarScreen.tooltipLines(tr("wizard.id_note")));
            }
        }

        drawMessage(graphics, font, mouseX, mouseY);

        // The caption under the box, and how it turns while the pointer is over it
        UiNode.Rect box = this.previewBounds;
        int captionY = box.bottom() + CAPTION_GAP;
        for (String line : this.caption) {
            Draw.centred(graphics, font, line, box.centerX(), captionY, this.captionWidth, Theme.MUTED);
            captionY += Theme.LINE_HEIGHT;
        }
        if (box.contains(mouseX, mouseY) && !Minecraft.getInstance().mouseHandler.isLeftPressed()) {
            UiTooltip.set(tr("preview.drag_hint"));
        }
    }

    // The message under the id: that the collection is being created, why the last create did
    // not work, or what is wrong with what was typed, in two lines, the whole of it in its tooltip
    // when that cut it
    private void drawMessage(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Component text;
        int colour;
        if (this.run != null) {
            text = tr("wizard.creating");
            colour = Theme.MUTED;
        } else if (this.createdId != null) {
            text = tr("wizard.opening");
            colour = Theme.MUTED;
        } else if (this.failure != null) {
            text = this.failure;
            colour = Theme.RED;
        } else {
            text = wrongInput();
            colour = Theme.RED;
        }
        if (text == null || text.getString().isEmpty()) {
            return;
        }
        String whole = text.getString();
        List<String> lines = Draw.fit(font, whole, Math.max(1, this.formWidth), MESSAGE_LINES);
        int y = this.messageY;
        for (String line : lines) {
            Draw.string(graphics, font, line, this.formX, y, colour);
            y += Theme.LINE_HEIGHT;
        }
        boolean cut = Draw.wrap(font, whole, Math.max(1, this.formWidth)).size() > lines.size();
        if (cut && mouseX >= this.formX && mouseX < this.formX + this.formWidth && mouseY >= this.messageY - 1
                && mouseY < y - 2) {
            UiTooltip.set(SidebarScreen.tooltipLines(text));
        }
    }

    // A line of text cut to a width, with the whole of it in its tooltip when it was cut
    private static void drawLine(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int colour,
                                 int mouseX, int mouseY) {
        String whole = text.getString();
        Draw.text(graphics, font, whole, x, y, width, colour);
        if (font.width(whole) > width && mouseX >= x && mouseX < x + width && mouseY >= y - 1 && mouseY < y + 10) {
            UiTooltip.set(SidebarScreen.tooltipLines(text));
        }
    }

    /**
     * The id the name makes, faint, on one line under the name, once a name is typed: before
     * that it would only be the stand-in id, and nothing is there to change. Under the pointer,
     * or once Tab reaches it, a link after it offers to change it; Enter or Space then does, and
     * Shift+Tab goes back to the name. The id's text says in its tooltip what the id is.
     */
    private final class IdLine extends UiNode.Box {
        // Where the link was drawn last, for the click that follows; -1 while none is shown
        private int linkX = -1;
        private int linkWidth;

        @Override
        public boolean visible() {
            return super.visible() && !name.isBlank();
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean over = hovered(mouseX, mouseY);
            boolean keyboard = CreatorScreen.hasKeyboard(this);
            boolean offered = !locked() && (over || keyboard);
            String slug = autoSlug();
            Component link = tr("create.id_change");
            int linkTextWidth = font.width(link);
            int slugRoom = offered ? Math.max(0, this.width - LINK_GAP - linkTextWidth) : this.width;
            String shown = Draw.cut(font, slug, slugRoom);
            Draw.string(graphics, font, shown, this.x, this.y + 1, Theme.FAINT);
            boolean overLink = false;
            if (offered) {
                this.linkX = this.x + font.width(shown) + LINK_GAP;
                this.linkWidth = Math.max(0, Math.min(linkTextWidth, this.x + this.width - this.linkX));
                overLink = over && mouseX >= this.linkX && mouseX < this.linkX + this.linkWidth;
                int colour = overLink || keyboard ? Theme.LINK_HOVER : Theme.LINK;
                String linkShown = Draw.cut(font, link.getString(), this.linkWidth);
                Draw.string(graphics, font, linkShown, this.linkX, this.y + 1, colour);
                graphics.fill(this.linkX, this.y + 10, this.linkX + font.width(linkShown), this.y + 11, colour);
            } else {
                this.linkX = -1;
            }
            if (over && !overLink && mouseX < this.x + font.width(shown)) {
                // What the id is, on the id's text only: a tooltip is drawn at the pointer, so on
                // the link it would cover the link
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(Component.empty().append(tr("wizard.id")).append(": " + slug));
                tooltip.addAll(SidebarScreen.tooltipLines(tr("wizard.id_note")));
                UiTooltip.set(tooltip);
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            if (!locked() && this.linkX >= 0 && click.x() >= this.linkX && click.x() < this.linkX + this.linkWidth) {
                UiSounds.play(ModSounds.CLICK);
                openSlug();
            }
            // The line takes the click, and with it the keyboard, which shows its link
            return true;
        }

        @Override
        public boolean key(UiInput.Key key) {
            if (locked() || !CreatorScreen.hasKeyboard(this)) {
                return false;
            }
            if (key.enter() || key.is(InputConstants.KEY_SPACE)) {
                UiSounds.play(ModSounds.CLICK);
                openSlug();
                return true;
            }
            if (key.is(InputConstants.KEY_TAB) && key.shift() && nameBox != null) {
                home.focusField(nameBox);
                return true;
            }
            return false;
        }
    }
}
