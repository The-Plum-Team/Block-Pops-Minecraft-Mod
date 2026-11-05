package com.theplumteam.client.gui.creator;

import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * What the creator does to a whole World collection, rather than to its contents: putting it in
 * the claw or gacha machine the creator was opened from, giving the operator every figure to try
 * it out, duplicating it into a new draft, archiving it or restoring it, and deleting a draft or
 * an archived collection for good. Archiving asks first and its message offers an undo; deleting
 * asks the operator to type the collection's name. It also opens the creator's help and the
 * import of a collection a data pack ships ({@link PackImportDialog}).
 *
 * <p>{@link #menu} offers a collection's actions, each disabled with the reason it cannot be used
 * now, in the order the overview's card menu lists them. The confirmations and the help are
 * dialogs over the screen the actions were made for; the outcome of each is told in the
 * creator's toast. Every action only asks the server, which checks it again; the screens follow
 * the collections the server syncs back.
 */
final class CollectionActions {
    private final CreatorScreen screen;
    @Nullable
    private final BlockPos machinePos;
    private final Consumer<String> reveal;

    /**
     * @param screen     the screen the dialogs open over
     * @param machinePos the claw or gacha machine the creator was opened from, which the menu
     *                   offers to put a collection in; null when there is none
     * @param reveal     shows a collection this screen asked for, such as a duplicate or an
     *                   import, once the server syncs it, given its id
     */
    CollectionActions(CreatorScreen screen, @Nullable BlockPos machinePos, Consumer<String> reveal) {
        this.screen = screen;
        this.machinePos = machinePos;
        this.reveal = reveal;
    }

    // The rules

    /** Why no collection can be added now, by a new one, a duplicate or an import, or null when one can. */
    @Nullable
    static Component tooManyCollections() {
        if (ClientWorldCollections.records().size() >= WorldContentLimits.MAX_COLLECTIONS) {
            return tr("too_many_collections", WorldContentLimits.MAX_COLLECTIONS);
        }
        return null;
    }

    /**
     * Why a collection cannot go in a machine now, or null when it can: the server hides it from
     * its machines, or it is archived, or it is a draft.
     */
    @Nullable
    static Component putProblem(WorldCollectionRecord record) {
        if (ClientHiddenCollections.isHidden(record.id())) {
            return tr("home.reason.hidden_machine");
        }
        if (record.archived()) {
            return tr("home.reason.archived_machine");
        }
        if (record.draft()) {
            return tr("home.reason.draft_machine");
        }
        return null;
    }

    // The menu

    /**
     * A collection's menu: putting it in this machine, when the creator was opened from one;
     * every figure for the operator; a duplicate; then restoring an archived collection or
     * archiving a published one, and deleting it for good, which a published collection must be
     * archived for first.
     */
    UiMenu menu(WorldCollectionRecord record) {
        UiMenu menu = new UiMenu();
        BlockPos machine = this.machinePos;
        if (machine != null) {
            Component putProblem = putProblem(record);
            if (putProblem != null) {
                menu.disabled(tr("home.menu.put"), putProblem);
            } else {
                menu.item(tr("home.menu.put"), () -> put(machine, record.id(), record.name()));
            }
        }
        if (record.figures().stream().anyMatch(WorldCollectionRecord.Figure::enabled)) {
            menu.item(tr("home.menu.unlock"), tr("home.menu.unlock_hint"), () -> unlock(record.id(), record.name()));
        } else {
            menu.disabled(tr("home.menu.unlock"), tr("home.reason.no_figures"));
        }
        Component full = tooManyCollections();
        if (full != null) {
            menu.disabled(tr("home.menu.duplicate"), full);
        } else {
            menu.item(tr("home.menu.duplicate"), () -> duplicate(record));
        }
        menu.separator();
        if (record.archived()) {
            menu.item(tr("home.menu.restore"), () -> setArchived(record, false));
        } else if (!record.draft()) {
            menu.warning(tr("home.menu.archive"), () -> confirmArchive(record));
        }
        if (record.archived() || record.draft()) {
            menu.warning(tr("home.menu.purge"), () -> confirmPurge(record));
        } else {
            menu.disabled(tr("home.menu.purge"), tr("home.reason.archive_first"));
        }
        return menu;
    }

    // The actions

    /** Puts a collection in the machine at a position, for every player of that machine. */
    static void put(BlockPos machinePos, String collectionId, String name) {
        new ClawMachineCollectionPacket(machinePos, collectionId).sendToServer();
        UiToast.show(tr("home.put_done", name));
    }

    /** Gives the operator every figure of a collection, to try it out. */
    static void unlock(String collectionId, String name) {
        try {
            new UnlockCollectionPacket(collectionId).sendToServer();
            UiToast.show(tr("unlock_sent", name));
        } catch (UnsupportedOperationException e) {
            // NeoForge refuses to send a payload the server did not register
            UiToast.show(tr("not_sent"));
        }
    }

    /**
     * Copies a collection into a new draft named after it, under a slug of its own, and shows
     * the copy once the server syncs it.
     */
    void duplicate(WorldCollectionRecord record) {
        String name = CreatorHomeScreen.cut(tr("home.copy_name", record.name()).getString(),
                WorldContentLimits.MAX_NAME_LENGTH);
        String slug = Slugs.unique(record.slug(), Slugs::isCollectionTaken);
        CreatorSave.create(slug, List.of(), next -> new WorldCollectionEdit.Duplicate(record.id(), next, name),
                outcome -> {
                    if (outcome.ok()) {
                        this.reveal.accept(outcome.collectionId());
                        UiToast.show(tr("home.duplicated", name));
                    } else if (outcome.ending() != CreatorSave.Ending.DISCONNECTED) {
                        UiToast.show(outcome.message());
                    }
                });
    }

    /** Asks before archiving a collection, which takes it out of the machines. */
    void confirmArchive(WorldCollectionRecord record) {
        UiModal dialog = new UiModal(tr("home.archive_title", record.name()), tr("home.archive_body"))
                .accent(DialogFrame.Accent.DANGER, Theme.ICON_ARCHIVE);
        dialog.cancelButton(tr("cancel"));
        dialog.button(tr("home.archive_button"), UiButton.Style.DANGER, () -> setArchived(record, true));
        this.screen.openModal(dialog);
    }

    /**
     * Archives a collection or brings it back. The message of an archive offers to undo it,
     * which restores the collection.
     */
    void setArchived(WorldCollectionRecord record, boolean archived) {
        send(new WorldCollectionEdit.SetArchived(record.id(), archived), () -> {
            if (archived) {
                UiToast.show(tr("home.archived", record.name()), tr("home.undo"), () -> setArchived(record, false));
            } else {
                UiToast.show(tr("home.restored", record.name()));
            }
        });
    }

    /**
     * Asks before deleting a draft or an archived collection for good: the button stays
     * disabled until the operator types the collection's name.
     */
    void confirmPurge(WorldCollectionRecord record) {
        String name = record.name();
        UiModal dialog = new UiModal(tr("home.purge_title", name), tr("home.purge_body"))
                .accent(DialogFrame.Accent.DANGER, PixelIcon.BIN);
        UiButton[] confirm = new UiButton[1];
        dialog.field(tr("home.purge_field", name), WorldContentLimits.MAX_NAME_LENGTH, "", Component.literal(name),
                value -> confirm[0].disable(value.strip().equals(name.strip()) ? null : tr("home.purge_rule")));
        dialog.cancelButton(tr("cancel"));
        confirm[0] = dialog.button(tr("home.purge_button"), UiButton.Style.DANGER,
                () -> send(new WorldCollectionEdit.Purge(record.id(), record.id()),
                        () -> UiToast.show(tr("home.purged", name))));
        confirm[0].disable(tr("home.purge_rule"));
        this.screen.openModal(dialog);
    }

    /** The creator's help: what World collections are and how players get them. */
    void openHelp() {
        UiModal dialog = new UiModal(tr("home.help_title"), tr("home.help_body")).width(320)
                .accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
        dialog.cancelButton(tr("home.help_close"));
        this.screen.openModal(dialog);
    }

    /**
     * Imports the collection a data pack on the server ships, as a new collection or over the
     * one with the slug the operator gives, and shows it once the server syncs it.
     */
    void openImport() {
        PackImportDialog.forNew(this.screen, this.reveal).open();
    }

    /**
     * Sends one edit on its own; a refusal or a lost connection is told in the toast.
     *
     * @param onSaved what follows once the server applied it, such as its own message
     */
    static void send(WorldCollectionEdit edit, Runnable onSaved) {
        boolean sent = ClientWorldUploads.edit(edit, outcome -> {
            if (outcome.ok()) {
                onSaved.run();
            } else if (outcome.disconnected()) {
                UiToast.show(tr("disconnected"));
            } else if (!outcome.cancelled()) {
                UiToast.show(CreatorSave.message(outcome.status()));
            }
        });
        if (!sent) {
            UiToast.show(tr("not_sent"));
        }
    }
}
