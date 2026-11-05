package com.theplumteam.client.gui.creator;

import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.gui.AdminProgressionScreen;
import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiMenu;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.ClientWorldUploads;
import com.theplumteam.client.worldcontent.CreatorAutosave;
import com.theplumteam.client.worldcontent.CreatorRuns;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
 * now, in the order the home's "More" menu lists them, with the players' progress of a published
 * collection and the import at its end. While the collection's changes are still being saved, or
 * another of the creator's runs of it is on its way ({@link CreatorAutosave#isSaving}), its own
 * actions wait: a duplicate would copy the server state before the save, and the others would
 * race it. Each action counts in {@link CreatorRuns} until the sync after it. The confirmations
 * and the help are dialogs over the screen the actions were made for; the outcome of each is told
 * in the creator's toast. Every action only asks the server, which checks it again; the screens
 * follow the collections the server syncs back.
 */
final class CollectionActions {
    // Wide enough that the help's three paragraphs fit the height of Minecraft's default window
    private static final int HELP_WIDTH = 400;

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

    /** Why a collection's own actions wait now, while its changes are being saved; null when they need not. */
    @Nullable
    static Component savingProblem(String collectionId) {
        return CreatorAutosave.isSaving(collectionId) ? tr("autosave.saving_tip") : null;
    }

    // The menu

    /**
     * A collection's menu: putting it in this machine, when the creator was opened from one;
     * every figure for the operator; the players' progress in a collection that is or was in the
     * machines; a duplicate; then restoring an archived collection or archiving a published one,
     * and deleting it for good, which a published collection must be archived for first; and
     * last the import of a new collection from a data pack.
     */
    UiMenu menu(WorldCollectionRecord record) {
        UiMenu menu = new UiMenu();
        // Everything but the import waits while the collection's changes are being saved
        Component saving = savingProblem(record.id());
        BlockPos machine = this.machinePos;
        if (machine != null) {
            Component putProblem = saving != null ? saving : putProblem(record);
            if (putProblem != null) {
                menu.disabled(tr("home.menu.put"), putProblem);
            } else {
                menu.item(tr("home.menu.put"), () -> put(machine, record.id(), record.name()));
            }
        }
        if (saving != null) {
            menu.disabled(tr("home.menu.unlock"), saving);
        } else if (record.figures().stream().anyMatch(WorldCollectionRecord.Figure::enabled)) {
            menu.item(tr("home.menu.unlock"), tr("home.menu.unlock_hint"), () -> unlock(record.id(), record.name()));
        } else {
            menu.disabled(tr("home.menu.unlock"), tr("home.reason.no_figures"));
        }
        if (!record.draft()) {
            // Players can only have figures of a collection that was in the machines
            if (saving != null) {
                menu.disabled(tr("home.menu.progress"), saving);
            } else {
                menu.item(tr("home.menu.progress"), () -> openProgress(record.id()));
            }
        }
        Component full = saving != null ? saving : tooManyCollections();
        if (full != null) {
            menu.disabled(tr("home.menu.duplicate"), full);
        } else {
            menu.item(tr("home.menu.duplicate"), () -> duplicate(record));
        }
        menu.separator();
        if (record.archived()) {
            if (saving != null) {
                menu.disabled(tr("home.menu.restore"), saving);
            } else {
                menu.item(tr("home.menu.restore"), () -> setArchived(record, false));
            }
        } else if (!record.draft()) {
            if (saving != null) {
                menu.disabled(tr("home.menu.archive"), saving);
            } else {
                menu.warning(tr("home.menu.archive"), () -> confirmArchive(record));
            }
        }
        if (!canPurge(record)) {
            menu.disabled(tr("home.menu.purge"), tr("home.reason.archive_first"));
        } else if (saving != null) {
            menu.disabled(tr("home.menu.purge"), saving);
        } else {
            menu.warning(tr("home.menu.purge"), () -> confirmPurge(record));
        }
        menu.separator();
        Component importProblem = tooManyCollections();
        if (importProblem != null) {
            menu.disabled(tr("home.import"), importProblem);
        } else {
            menu.item(tr("home.import"), this::openImport);
        }
        return menu;
    }

    /** Whether a collection can be deleted for good: a draft or an archived one; a published one is archived first. */
    static boolean canPurge(WorldCollectionRecord record) {
        return record.archived() || record.draft();
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
        // The source counts as busy too, until the copy of it is synced
        CreatorRuns.Entry source = CreatorRuns.begin(record.id(), this);
        CreatorSave.create(slug, List.of(), next -> new WorldCollectionEdit.Duplicate(record.id(), next, name),
                outcome -> {
                    CreatorRuns.finish(source, outcome.ok());
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
        send(record.id(), new WorldCollectionEdit.SetArchived(record.id(), archived), () -> {
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
                () -> send(record.id(), new WorldCollectionEdit.Purge(record.id(), record.id()),
                        () -> UiToast.show(tr("home.purged", name))));
        confirm[0].disable(tr("home.purge_rule"));
        this.screen.openModal(dialog);
    }

    /**
     * The creator's help: what World collections are and how players get them, then the home's
     * keyboard shortcuts, and last how much of the server's file space the collections use and
     * how many of the world's collections exist.
     */
    void openHelp() {
        UiModal dialog = new UiModal(tr("home.help_title"), tr("home.help_body")).width(HELP_WIDTH)
                .accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
        // Each paragraph in a row of its own, as tall as its lines at the width the dialog takes
        int inner = Math.max(160, Math.min(HELP_WIDTH, this.screen.width - 2 * Theme.SPACE_S)) - 2 * DialogFrame.PADDING;
        Font font = Minecraft.getInstance().font;
        for (Paragraph paragraph : List.of(new Paragraph(tr("home.help_keys"), Theme.MUTED),
                new Paragraph(quotaText(), Theme.FAINT))) {
            dialog.node(paragraph, paragraph.height(font, inner));
        }
        dialog.cancelButton(tr("home.help_close"));
        this.screen.openModal(dialog);
    }

    /**
     * How much of the server's file space every World collection uses and how many of the
     * world's collections exist: "3.2 of 64 MiB · 3 of 32 collections".
     */
    static Component quotaText() {
        return Component.empty()
                .append(UiQuotaMeter.text(ClientWorldCollections.quotaUsedKiB(), ClientWorldCollections.quotaMaxKiB()))
                .append(tr("home.collections", ClientWorldCollections.records().size(),
                        WorldContentLimits.MAX_COLLECTIONS));
    }

    /**
     * What the players of this world have of one collection, over the screen the actions were
     * made for, which it goes back to.
     */
    void openProgress(String collectionId) {
        Minecraft.getInstance().setScreen(new AdminProgressionScreen(this.screen, collectionId));
    }

    /**
     * Imports the collection a data pack on the server ships, as a new collection or over the
     * one with the slug the operator gives, and shows it once the server syncs it.
     */
    void openImport() {
        PackImportDialog.forNew(this.screen, this.reveal).open();
    }

    /**
     * Sends one edit of a collection on its own, which counts in {@link CreatorRuns} under the
     * collection until the sync after it; a refusal or a lost connection is told in the toast.
     *
     * @param collectionId the collection it changes, or the one an import makes or replaces
     * @param onSaved      what follows once the server applied it, such as its own message
     */
    static void send(String collectionId, WorldCollectionEdit edit, Runnable onSaved) {
        CreatorRuns.Entry entry = CreatorRuns.begin(collectionId, edit);
        boolean sent = ClientWorldUploads.edit(edit, outcome -> {
            CreatorRuns.finish(entry, outcome.ok());
            if (outcome.ok()) {
                onSaved.run();
            } else if (outcome.disconnected()) {
                UiToast.show(tr("disconnected"));
            } else if (!outcome.cancelled()) {
                UiToast.show(CreatorSave.message(outcome.status()));
            }
        });
        if (!sent) {
            CreatorRuns.forget(entry);
            UiToast.show(tr("not_sent"));
        }
    }

    // A paragraph of a dialog in a row of its own, wrapped to the row's width
    private static final class Paragraph extends UiNode.Box {
        private static final int MAX_LINES = 4;
        private final Component text;
        private final int colour;

        Paragraph(Component text, int colour) {
            this.text = text;
            this.colour = colour;
        }

        int height(Font font, int width) {
            return Math.min(MAX_LINES, Draw.wrap(font, text.getString(), width).size()) * Theme.LINE_HEIGHT - 2;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Draw.lines(graphics, Minecraft.getInstance().font, text.getString(), x, y, width, MAX_LINES, colour);
        }
    }
}
