package com.theplumteam.client.gui.creator;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.worldcontent.CreatorSource;
import com.theplumteam.client.worldcontent.ClientPreviewAssets;
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
import java.util.function.Consumer;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The creator home's form for a new World collection ({@link CreatorHomeScreen}), shown in its
 * content panel while the sidebar's temporary "New" row is selected. It is the editor's Look page
 * ({@link LookPage}) on a collection that does not exist yet: the box in view on the left, and on
 * the right the name, the background, the box and the logo. Only the name is asked for; the rest
 * starts as every new collection does, orange with the mod's usual box, and may be set here or
 * later in the editor, on the same page.
 *
 * <p>Between the name and the sections the form has two lines of its own. The first shows, faint,
 * the id the name makes, free among the collections this client was sent, once a name is typed.
 * Pointing at that line, or reaching it with Tab, offers to change the id, which then gets a
 * labelled field of its own, showing the id it falls back to while it is empty: an id cannot
 * change once the collection exists, and an empty field follows the name again. The second says
 * only what is wrong, or that the collection is being created. "Create", on the panel's footer
 * line under the column, says in its tooltip why it cannot be used yet.
 *
 * <p>A short canvas leaves the subtitle out. A narrow one gives the page the whole panel, as the
 * editor does: Create stands at the sidebar's foot, where the editor has Publish, with Cancel
 * over it, and the message's line shows only while the id's field is open, a refusal being said
 * in a toast otherwise.
 *
 * <p>The box holds a figure in the player's own skin, from the game's skin cache or the default
 * skin, so it never shows empty. That figure is the preview's alone and is never sent.
 *
 * <p>"Create" (or Enter) sends the files picked for the logo and the box image, then one batch
 * that creates the collection as a draft and sets its name, its author (the player's name), its
 * colour, its box and its logo; a slug the server refuses as taken is retried with the next one.
 * From then until the server has synced the new collection, at most for
 * {@value #SYNC_TIMEOUT_MS} ms, the home is locked and only Cancel, Escape and the creator's cross
 * act. Once it has, the home selects it and the collection editor ({@link CreatorEditorScreen})
 * opens on its figures; a sync that does not come in time is said in a toast, and the home
 * selects the collection when it does. A refusal is said under the name and unlocks the form
 * again, and the field that had the keyboard before Create gets it back.
 *
 * <p>Cancel drops the batch if it has not been sent yet. One already sent may still be saved:
 * the home then says so in a toast and selects the collection once it arrives, but never opens it
 * in the editor after a cancel.
 *
 * <p>The form lives as long as it is open, so what is typed and chosen survives the home's
 * rebuilds; the home adds its nodes and fields again in {@link #build} and draws its labels in
 * {@link #draw}.
 */
final class CreateForm implements LookHost {
    /** The colour a new collection starts with, the swatch named Orange. */
    static final int START_COLOUR = 0xF08A24;
    // How long a created collection may take to arrive before the form stops waiting for it
    private static final long SYNC_TIMEOUT_MS = 10_000;
    // The creator's preview of a new collection uses this slot
    private static final int PREVIEW_SLOT = 0;
    // From the content panel's top: the subtitle, then the Look page, which a short canvas shows
    // right under the title; the page keeps this far above the panel's footer line, where Create is
    private static final int SUBTITLE_Y = 38;
    private static final int BLOCK_TOP = 54;
    private static final int BLOCK_TOP_SHORT = 26;
    private static final int BLOCK_BOTTOM_GAP = 8;
    // The narrow page's view may be this low here, where the title takes some of its room
    private static final int NARROW_VIEW_MIN = 44;
    private static final int LINK_HEIGHT = 10;
    // Under the name: the id as a line or as a labelled field, then one line of message
    private static final int LABEL = 8;
    private static final int LABEL_GAP = 2;
    private static final int ROW_GAP = 2;
    private static final int ID_LINE = 10;
    private static final int MESSAGE_LINE = 10;
    private static final int MIN_CREATE_WIDTH = 78;
    // Cancel beside Create
    private static final int CANCEL_GAP = 14;
    // Between the id and the link that changes it
    private static final int LINK_GAP = 6;

    private final CreatorHomeScreen home;
    // The collection being made: its name and look as chosen so far, and the sample figure
    private final CreatorDraft draft;
    private final LookPage look;
    // The id the operator typed; empty follows the name
    private String typedSlug = "";
    private boolean slugOpen;
    private boolean sampleAdded;
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

    // Nodes kept across rebuilds, so the buttons keep their state
    private final UiButton createButton;
    private final UiButton cancelButton;
    private final IdLine idLine = new IdLine();
    private final Shield shield = new Shield();
    @Nullable
    private EditBox nameBox;
    @Nullable
    private EditBox slugBox;

    // Where build() put what draw() draws
    private UiNode.Rect content = UiNode.Rect.EMPTY;
    private UiNode.Rect page = UiNode.Rect.EMPTY;
    private int formX;
    private int formWidth;
    private int idY;
    private int slugFieldY;
    private int messageY;

    CreateForm(CreatorHomeScreen home) {
        this.home = home;
        String player = Minecraft.getInstance().getUser().getName();
        this.sampleName = CreatorHomeScreen.cut(player.isBlank() ? Slugs.FIGURE_FALLBACK : player,
                WorldContentLimits.MAX_NAME_LENGTH);
        this.draft = CreatorDraft.create(Slugs.unique(Slugs.collection(""), Slugs::isCollectionTaken), "");
        this.draft.setRgb(START_COLOUR);
        this.draft.setAuthor(authorName());
        this.look = new LookPage(this);
        this.createButton = new UiButton(tr("create.create"), UiButton.Style.PRIMARY, this::create);
        this.cancelButton = new UiButton(tr("cancel"), UiButton.Style.LINK, this::cancel);
        readSampleSkin();
    }

    // The sample figure wears the player's own skin, read from this computer only
    private void readSampleSkin() {
        OwnSkin.read(result -> {
            FilePicker.PickResult pick = result.pick();
            if (this.disposed || this.sampleAdded || pick.kind() != FilePicker.PickResult.Kind.ACCEPTED
                    || pick.file() == null) {
                // Without a skin the box stays empty, which still shows its colour and frame
                return;
            }
            this.sampleAdded = true;
            this.draft.addFigure(this.sampleName, WorldCollectionRecord.Kind.SKIN, CreatorDraft.FileRef.of(pick.file()),
                    null, null, 1.0f);
            if (this.home.isShown()) {
                refreshPreview();
                this.look.draftChanged();
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
        int top = area.y() + (this.home.isShort() ? BLOCK_TOP_SHORT : BLOCK_TOP);
        // Narrow, the buttons are in the sidebar and the page reaches the panel's foot
        int bottom = narrow ? this.home.footerY() + UiButton.HEIGHT : this.home.footerY() - BLOCK_BOTTOM_GAP;
        this.page = new UiNode.Rect(this.home.left(), top, this.home.inner(), Math.max(0, bottom - top));
        refreshPreview();
        this.slugBox = null;
        this.look.build(this.page);

        boolean locked = locked();
        if (this.nameBox != null) {
            this.nameBox.active = !locked;
        }
        if (locked) {
            // Nothing of the page acts while the collection is being created; Cancel, added after, does
            this.shield.at(this.page.x(), this.page.y(), this.page.width(), this.page.height());
            this.home.add(this.shield);
        }

        this.createButton.disable(problem());
        int cancelWidth = font.width(this.cancelButton.label());
        if (narrow) {
            // At the sidebar's foot, and Cancel in the status slot over it
            this.home.sideFoot(this.createButton);
            UiNode.Rect list = this.home.sideList();
            this.cancelButton.at(list.x() + 1, this.home.slotY(), Math.min(cancelWidth, list.width()), LINK_HEIGHT);
        } else {
            // Under the column, on the footer line, and Cancel beside it
            int buttonX = this.look.columnX();
            int room = Math.max(0, this.home.right() - buttonX);
            int createWidth = Math.min(room, Math.max(MIN_CREATE_WIDTH, this.createButton.naturalWidth()));
            this.createButton.at(buttonX, this.home.footerY(), createWidth, UiButton.HEIGHT);
            this.home.add(this.createButton);
            int cancelX = buttonX + createWidth + CANCEL_GAP;
            this.cancelButton.at(cancelX, this.home.footerY(),
                    Math.min(cancelWidth, Math.max(0, this.home.right() - cancelX)), UiButton.HEIGHT);
        }
        this.home.add(this.cancelButton);

        if (locked) {
            this.focusName = false;
            this.focusSlug = false;
        } else if (this.focusName && this.nameBox != null) {
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

    // The Look page's host

    @Override
    public SidebarScreen screen() {
        return this.home;
    }

    @Override
    public CreatorDraft draft() {
        return this.draft;
    }

    @Override
    public int previewSlot() {
        return PREVIEW_SLOT;
    }

    @Override
    public <T extends UiNode> T add(T node) {
        return this.home.add(node);
    }

    @Override
    public EditBox field(String name, int x, int y, int width, int maxLength, Component hint, String value,
                         Consumer<String> responder) {
        EditBox box = this.home.field("create_" + name, x, y, width, maxLength, hint, value, responder);
        if (name.equals("name")) {
            this.nameBox = box;
        }
        return box;
    }

    @Override
    public <T extends SidebarScreen.Budgeted> T budgetNode(T node) {
        return this.home.budgetNode(node);
    }

    @Override
    public UiNode.Rect contentPanel() {
        return this.content;
    }

    /** The name or the look changed: the box shows it, and Create says whether it can be used. */
    @Override
    public void changed() {
        this.failure = null;
        showFallbackSlug();
        refreshPreview();
        this.createButton.disable(problem());
        this.look.draftChanged();
    }

    @Override
    public void rebuildPage() {
        this.home.formChanged(this);
    }

    @Override
    public void navigated() {
        // Nothing is sent before Create
    }

    @Override
    @Nullable
    public Component boxProblem() {
        return PublishCheck.boxProblem(this.draft);
    }

    @Override
    public boolean footerIsPrimary() {
        return true;
    }

    @Override
    public void numberReleased() {
        // No saver follows the values here
    }

    /** The id's line or its labelled field, then the message's line where there is one. */
    @Override
    public int underNameHeight(boolean narrow) {
        int id = this.slugOpen ? LABEL + LABEL_GAP + UiField.HEIGHT : ID_LINE;
        return hasMessageLine() ? id + ROW_GAP + MESSAGE_LINE : id;
    }

    // The narrow page keeps the message's line only while the id's field is open, which is when
    // what is typed can be wrong in a way the name's own field does not show
    private boolean hasMessageLine() {
        return !this.home.narrow() || this.slugOpen;
    }

    @Override
    public int narrowViewMin(int usual) {
        return Math.min(usual, NARROW_VIEW_MIN);
    }

    @Override
    public void buildUnderName(int x, int y, int width) {
        this.formX = x;
        this.formWidth = width;
        this.idY = y;
        if (this.slugOpen) {
            // Under a label of its own; while empty it shows, faint, the id it falls back to,
            // which a field's hint would hide while the field has the keyboard
            this.slugFieldY = this.idY + LABEL + LABEL_GAP;
            this.slugBox = this.home.field("create_slug", x, this.slugFieldY, width, Slugs.MAX_LENGTH, tr("wizard.id"),
                    this.typedSlug, this::slugTyped);
            this.slugBox.setHint(Component.empty());
            showFallbackSlug();
            this.slugBox.active = !locked();
            this.messageY = this.slugFieldY + UiField.HEIGHT + ROW_GAP;
        } else {
            this.idLine.at(x, this.idY, width, ID_LINE);
            this.home.add(this.idLine);
            this.messageY = this.idY + ID_LINE + ROW_GAP;
        }
    }

    // Typing

    private void slugTyped(String value) {
        this.typedSlug = value;
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

    // The collection

    private String name() {
        return this.draft.name();
    }

    /** The id the name makes, free among the collections this client was sent. */
    private String autoSlug() {
        return Slugs.unique(Slugs.collection(name()), Slugs::isCollectionTaken);
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
     * the world has every collection it may, the name is missing or not plain text, the id typed
     * is not an id or is taken, or the box is to have an image and has none yet.
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
        if (name().isBlank()) {
            return tr("wizard.name_empty");
        }
        Component wrong = wrongInput();
        return wrong != null ? wrong : boxProblem();
    }

    /** What is wrong with what was typed, or null: a name that is not plain text, or an id typed wrong or taken. */
    @Nullable
    private Component wrongInput() {
        if (!name().isEmpty() && !WorldCollectionRecord.isPlainText(name())) {
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
     * Shows the draft in the preview slot, under the id it would be created with while that is
     * one. It is shown there only, and never sent.
     */
    private void refreshPreview() {
        if (this.disposed) {
            return;
        }
        String slug = slug();
        if (WorldContentIds.isSlug(slug)) {
            this.draft.setSlug(slug);
        }
        this.draft.preview(PREVIEW_SLOT);
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
        String shownName = name().strip();
        String author = this.draft.author();
        int red = this.draft.red();
        int green = this.draft.green();
        int blue = this.draft.blue();
        // The box and the logo as chosen: their files go first, and the batch names them
        CreatorDraft.FileRef image = this.draft.boxMode() == CreatorDraft.BoxMode.TEXTURE ? this.draft.boxTexture() : null;
        String colour = this.draft.boxMode() == CreatorDraft.BoxMode.COLOR ? this.draft.boxColor() : null;
        int boxMode = image != null ? WorldCollectionEdit.BOX_TEXTURE
                : colour != null ? WorldCollectionEdit.BOX_COLOR : WorldCollectionEdit.BOX_NONE;
        String boxValue = image != null ? image.sha() : colour != null ? colour : "";
        CreatorDraft.FileRef logo = this.draft.logo();
        WorldCollectionRecord.LogoLayout layout = logo == null ? null : this.draft.logoLayout();
        List<FilePicker.LocalFile> files = new ArrayList<>();
        for (CreatorDraft.FileRef file : new CreatorDraft.FileRef[] {image, logo}) {
            if (file != null && file.local() != null) {
                files.add(file.local());
            }
        }
        this.sentName = shownName;
        this.failure = null;
        this.cancelled = false;
        this.slugHadKeyboard = this.slugBox != null && this.home.getFocused() == this.slugBox;
        // The batch is made again for each slug a retry after ID_TAKEN tries
        CreatorSave.Run started = CreatorSave.create(slug, files, next -> {
            String id = WorldContentIds.collectionId(next);
            List<WorldCollectionEdit> edits = new ArrayList<>();
            edits.add(new WorldCollectionEdit.CreateDraft(next, shownName));
            edits.add(new WorldCollectionEdit.SetCollection(id, shownName, author, red, green, blue, boxMode,
                    boxValue, logo != null ? logo.sha() : ""));
            if (layout != null && !WorldCollectionRecord.LogoLayout.DEFAULT.equals(layout)) {
                // The logo's own size and place; the rest of the extras stay as a new collection has them
                edits.add(new WorldCollectionEdit.SetCollectionExtras(id, "", layout, false, null));
            }
            return new WorldCollectionEdit.Batch(edits);
        }, this::saved);
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
        if (!hasMessageLine()) {
            UiToast.show(this.failure);
        }
        // The lock lifts, and the field that had the keyboard gets it back to put it right: the
        // id's, when it had it or the server found the id taken, else the name's
        boolean slugFirst = this.slugOpen
                && (this.slugHadKeyboard || outcome.status().result() == WorldContentStatus.Result.ID_TAKEN);
        this.focusSlug = slugFirst;
        this.focusName = !slugFirst;
        this.home.formChanged(this);
    }

    /** Runs once per client tick while the home is shown: the page's own tick, and the wait for the new collection's sync. */
    void tick() {
        this.look.tick();
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
        if (CreatorSource.record(id) != null) {
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
        this.look.hide();
        CreatorPreview.clear(PREVIEW_SLOT);
        // The sample skin and the files picked stay loaded only while a synced collection names them
        ClientPreviewAssets.release();
    }

    /** The form closed: its page and its preview go, and a skin read late is dropped. */
    void dispose() {
        this.disposed = true;
        this.look.close();
        hide();
    }

    // Input

    /**
     * A key in one of the form's fields: Enter creates the collection, Tab from the name goes on
     * to the id's line, which offers to change the id, and from the id's field to the page's
     * first row.
     */
    boolean fieldKey(EditBox field, UiInput.Key key) {
        if (field != this.nameBox && field != this.slugBox) {
            return false;
        }
        if (key.enter()) {
            submit();
            return true;
        }
        boolean tab = key.is(InputConstants.KEY_TAB) && !key.shift();
        if (field == this.nameBox && tab && !this.slugOpen && !locked() && this.idLine.visible()) {
            this.home.focusNode(this.idLine);
            return true;
        }
        if (tab && !locked() && (field == this.slugBox || field == this.nameBox && !this.slugOpen)) {
            return this.look.focusFirst();
        }
        return false;
    }

    /** The page's keys while one of its rows has the keyboard, as the editor offers them. */
    boolean shortcut(UiInput.Key key) {
        return !locked() && this.look.shortcut(key);
    }

    /** Escape, before it cancels the form: a sub-view of the page closes, or its row lets the keyboard go. */
    boolean escape() {
        return !locked() && this.look.escape();
    }

    // Drawing

    /** Draws the subtitle, the page's own labels, the id's label and the message, under the form's nodes. */
    void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        if (!this.home.isShort()) {
            drawLine(graphics, font, tr("create.subtitle"), this.home.left(), this.content.y() + SUBTITLE_Y,
                    this.home.inner(), Theme.MUTED, mouseX, mouseY);
        }
        this.look.drawContents(graphics, mouseX, mouseY, partialTick);

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

        if (hasMessageLine()) {
            drawMessage(graphics, font, mouseX, mouseY);
        }
    }

    // The message under the id, on one line with the whole of it in its tooltip when that cut it:
    // that the collection is being created, why the last create did not work, or what is wrong
    // with what was typed
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
        drawLine(graphics, font, text, this.formX, this.messageY, Math.max(1, this.formWidth), colour, mouseX, mouseY);
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

    // Takes every click on the page while the collection is being created
    private static final class Shield extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public boolean click(UiInput.Click click) {
            return isMouseOver(click.x(), click.y());
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
            return super.visible() && !name().isBlank();
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
            if (key.is(InputConstants.KEY_TAB)) {
                // On to the page's first row, as Tab in the name does in the editor
                return look.focusFirst();
            }
            return false;
        }
    }
}
