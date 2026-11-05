package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
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
 * <p>The toast says how the import went, and the screen is told the collection's id once the
 * server applied it.
 */
final class PackImportDialog {
    /** Whether the pack may become a collection of its own, or updates one collection. */
    enum Mode {
        NEW, UPDATE
    }

    // The longest namespace:path WorldCollectionImporter accepts
    private static final int MAX_PACK_SOURCE = 64 + 1 + 32;

    private final CreatorScreen screen;
    private final Mode mode;
    private final Consumer<String> onImported;
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

    private PackImportDialog(CreatorScreen screen, Mode mode, String slug, String name, boolean asDraft,
                             Consumer<String> onImported) {
        this.screen = screen;
        this.mode = mode;
        this.slug = slug;
        this.name = name;
        this.asDraft = asDraft;
        this.onImported = onImported;
    }

    /**
     * The import of a pack as the collection with a slug the operator gives, new or existing.
     *
     * @param onImported told the collection's id once the server applied the import
     */
    static PackImportDialog forNew(CreatorScreen screen, Consumer<String> onImported) {
        return new PackImportDialog(screen, Mode.NEW, "", "", true, onImported);
    }

    /**
     * The import of a pack over one existing collection, which keeps its slug and its state.
     *
     * @param onImported told the collection's id once the server applied the import
     */
    static PackImportDialog forUpdate(CreatorScreen screen, WorldCollectionRecord record,
                                      Consumer<String> onImported) {
        return new PackImportDialog(screen, Mode.UPDATE, record.slug(), record.name(), record.draft(), onImported);
    }

    /** Opens the dialog over the screen. */
    void open() {
        UiModal dialog = new UiModal(tr("home.import_title"), tr("home.import_body")).width(340)
                .accent(DialogFrame.Accent.CREATE, PixelIcon.PLUS);
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
        dialog.cancelButton(tr("cancel"));
        this.confirm = dialog.button(tr("home.import_button"), UiButton.Style.PRIMARY, this::send);
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
        return null;
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
        CollectionActions.send(new WorldCollectionEdit.ImportPack(this.slug, parts[0], parts[1], this.asDraft), () -> {
            this.onImported.accept(id);
            UiToast.show(tr("home.imported", shown));
        });
    }
}
