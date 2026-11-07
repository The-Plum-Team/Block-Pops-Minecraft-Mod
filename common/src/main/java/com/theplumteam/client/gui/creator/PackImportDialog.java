package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCheckbox;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.server.worldcollections.WorldCollectionImporter;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The dialog that imports a collection a data pack on the server ships in
 * {@code data/<namespace>/blockpops_world_collections/<path>/}. The operator types the pack as
 * namespace:path, which must pass the importer's own rules
 * ({@link WorldCollectionImporter#isValidSource}); the server reads only its own datapacks, by
 * that exact name, and checks the pack again.
 *
 * <p>It has two modes. {@link Mode#NEW}, from the overview, imports the pack as the collection
 * with the slug the operator gives, which follows the pack's path until one is typed: a new
 * collection, which starts as a draft unless the operator unticks it, or the existing collection
 * with that slug, updated from the pack. {@link Mode#UPDATE} imports the pack over one existing
 * collection, whose slug it shows and never changes; the collection stays a draft or a published
 * one as it is, since the server keeps its state and its revision. Once that collection is gone,
 * deleted by another operator while the dialog is open, the update is refused rather than sent:
 * the server would take the pack for a new collection.
 *
 * <p>An import over a collection waits while its changes are being saved, which it would race.
 * Opened from the editor of that collection ({@link UpdateHooks}), the dialog follows the editor's
 * saver as it finishes ({@link #recheck}); when the collection is published and has changes not
 * published yet, it says they will be discarded and its button says so too, and the editor hears
 * when the import goes and how it ended, in place of the toast's refusal.
 *
 * <p>The toast says how the import went, and the screen is told the collection's id once the
 * server applied it.
 */
final class PackImportDialog {
    /** Whether the pack may become a collection of its own, or updates one collection. */
    enum Mode {
        NEW, UPDATE
    }

    /** What the editor of the collection an update imports over does around it. */
    interface UpdateHooks {
        /**
         * How many changes not published yet the import would discard, as a published
         * collection's; 0 for none.
         */
        int pendingChanges();

        /** The operator confirmed the import, which is sent next. */
        void sending();

        /**
         * How the import ended: null once the server applied it, else what it said or why it
         * could not be sent, empty for a cancel.
         */
        void landed(@Nullable Component refusal);
    }

    // The longest namespace:path WorldCollectionImporter accepts
    private static final int MAX_PACK_SOURCE = 64 + 1 + 32;
    // The width the dialog asks for
    private static final int WIDTH = 340;

    private final CreatorScreen screen;
    private final Mode mode;
    private final Consumer<String> onImported;
    @Nullable
    private final UpdateHooks hooks;
    // The name of the collection an update imports over, for the reason it is gone; empty for a new one
    private final String name;
    private String source = "";
    private String slug;
    private boolean slugTyped;
    private boolean settingSlug;
    private boolean asDraft;
    @Nullable
    private EditBox slugBox;
    @Nullable
    private UiButton confirm;
    @Nullable
    private UiModal dialog;

    private PackImportDialog(CreatorScreen screen, Mode mode, String slug, String name, boolean asDraft,
                             Consumer<String> onImported, @Nullable UpdateHooks hooks) {
        this.screen = screen;
        this.mode = mode;
        this.slug = slug;
        this.name = name;
        this.asDraft = asDraft;
        this.onImported = onImported;
        this.hooks = hooks;
    }

    /**
     * The import of a pack as the collection with a slug the operator gives, new or existing.
     *
     * @param onImported told the collection's id once the server applied the import
     */
    static PackImportDialog forNew(CreatorScreen screen, Consumer<String> onImported) {
        return new PackImportDialog(screen, Mode.NEW, "", "", true, onImported, null);
    }

    /**
     * The import of a pack over one existing collection, which keeps its slug and its state, as
     * the editor of that collection offers it.
     *
     * @param onImported told the collection's id once the server applied the import
     * @param hooks      what the editor does around the import; null for none
     */
    static PackImportDialog forUpdate(CreatorScreen screen, WorldCollectionRecord record,
                                      Consumer<String> onImported, @Nullable UpdateHooks hooks) {
        return new PackImportDialog(screen, Mode.UPDATE, record.slug(), record.name(), record.draft(), onImported,
                hooks);
    }

    /** Whether the dialog is open on its screen now. */
    boolean isOpen() {
        return this.dialog != null && this.dialog.isOpen();
    }

    /**
     * Checks again whether the import can go, as the editor's saver finishes a save it waited
     * for; the screen calls this while the dialog is open.
     */
    void recheck() {
        check();
    }

    /** Opens the dialog over the screen. */
    void open() {
        UiModal dialog = new UiModal(tr("home.import_title"), tr("home.import_body")).width(WIDTH)
                .accent(DialogFrame.Accent.CREATE, PixelIcon.PLUS);
        this.dialog = dialog;
        dialog.field(tr("home.import_source"), MAX_PACK_SOURCE, "", tr("home.import_source_hint"), value -> {
            this.source = value.strip();
            if (this.mode == Mode.NEW && !this.slugTyped && this.slugBox != null) {
                // setValue tells the slug's responder, which must not take it for typing
                this.settingSlug = true;
                this.slugBox.setValue(suggestedSlug(this.source));
                this.settingSlug = false;
            }
            check();
        });
        this.slugBox = dialog.field(tr("home.import_slug"), Slugs.MAX_LENGTH, this.slug, tr("slug_hint"), value -> {
            this.slug = value;
            if (!this.settingSlug) {
                this.slugTyped = !value.isEmpty();
            }
            check();
        });
        if (this.mode == Mode.NEW) {
            dialog.node(new UiCheckbox(tr("home.import_draft"), true, checked -> this.asDraft = checked),
                    UiCheckbox.BOX + 2);
        } else {
            // The collection the pack updates, shown but never changed nor focused
            this.slugBox.setEditable(false);
            this.slugBox.active = false;
        }
        int discarded = this.hooks != null ? this.hooks.pendingChanges() : 0;
        if (discarded > 0) {
            // A published collection's changes not published yet go with the import
            Component line = discarded == 1 ? tr("options.import_discard_one")
                    : tr("options.import_discard", discarded);
            CollectionActions.Paragraph note = new CollectionActions.Paragraph(line, Theme.AMBER);
            int inner = Math.max(160, Math.min(WIDTH, this.screen.width - 2 * Theme.SPACE_S)) - 2 * DialogFrame.PADDING;
            dialog.node(note, note.height(Minecraft.getInstance().font, inner));
        }
        dialog.cancelButton(tr("cancel"));
        this.confirm = dialog.button(tr(discarded > 0 ? "options.import_discard_button" : "home.import_button"),
                UiButton.Style.PRIMARY, this::send);
        check();
        this.screen.openModal(dialog);
    }

    // The namespace and the path of a typed pack, or null unless the importer accepts both
    @Nullable
    private static String[] packParts(String typed) {
        int colon = typed.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String namespace = typed.substring(0, colon);
        String path = typed.substring(colon + 1);
        return WorldCollectionImporter.isValidSource(namespace, path) ? new String[] {namespace, path} : null;
    }

    private static String suggestedSlug(String typed) {
        String[] parts = packParts(typed);
        return parts != null ? Slugs.collection(parts[1]) : "";
    }

    /** Why the import cannot be sent as typed, or null when it can. */
    @Nullable
    private Component problem() {
        if (this.mode == Mode.UPDATE && !Slugs.isCollectionTaken(this.slug)) {
            // The collection to update is gone: the server would make the pack a new collection
            return tr("editor.gone", this.name);
        }
        if (this.mode == Mode.UPDATE) {
            // Over the open collection, waiting for its changes to be saved comes before what is typed
            Component saving = CollectionActions.savingProblem(WorldContentIds.collectionId(this.slug));
            if (saving != null) {
                return saving;
            }
        }
        if (packParts(this.source) == null) {
            return tr("home.import_source_rule");
        }
        if (!WorldContentIds.isSlug(this.slug)) {
            return tr("slug_rule");
        }
        if (!Slugs.isCollectionTaken(this.slug)) {
            // Only a slug no collection has makes a new one
            return CollectionActions.tooManyCollections();
        }
        // An import over a collection waits for its changes to be saved, which it would race
        return CollectionActions.savingProblem(WorldContentIds.collectionId(this.slug));
    }

    private void check() {
        if (this.confirm != null) {
            this.confirm.disable(problem());
        }
    }

    private void send() {
        String[] parts = packParts(this.source);
        Component problem = problem();
        if (problem != null || parts == null) {
            if (this.mode == Mode.UPDATE && problem != null) {
                // The collection went while the dialog was open, after the button was last checked
                UiToast.show(problem);
            }
            return;
        }
        String id = WorldContentIds.collectionId(this.slug);
        String shown = this.source;
        UpdateHooks told = this.hooks;
        if (told != null) {
            told.sending();
        }
        CollectionActions.send(id, new WorldCollectionEdit.ImportPack(this.slug, parts[0], parts[1], this.asDraft),
                () -> {
                    this.onImported.accept(id);
                    UiToast.show(tr("home.imported", shown));
                    if (told != null) {
                        told.landed(null);
                    }
                }, told == null ? null : told::landed);
    }
}
