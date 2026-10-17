package com.theplumteam.client.gui;

import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.client.worldcontent.FilePicker;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists this world's World collections for an operator: each can be opened in the editor,
 * archived or restored, and unlocked for the operator, and a new one can be created from
 * a slug and a name. The server owns the list; every button only sends a request, and the
 * rows follow the collections the server syncs back.
 */
public class WorldCollectionsScreen extends WorldCreatorScreen {
    private static final int ROW_HEIGHT = 24;
    private static final int MAX_ROWS = 8;
    private static final int BUTTONS_WIDTH = 176;
    // Archiving hides a collection from every machine, so its button has to be clicked twice
    private static final long CONFIRM_DELAY_MILLIS = 500;

    private final List<WorldCollectionRecord> visibleRecords = new ArrayList<>();
    private int page;
    private int pageCount = 1;

    private String slug = "";
    private String name = "";
    private Button createButton;
    @Nullable
    private Button armedArchive;
    private long armedAt;
    // A collection this screen created, opened in the editor as soon as the server syncs it
    @Nullable
    private String openWhenSynced;

    public WorldCollectionsScreen(Screen parent) {
        super(label("title"), parent, 560, 340);
    }

    private int rowsPerPage() {
        return Math.max(1, Math.min(MAX_ROWS, (this.panelHeight - 146) / ROW_HEIGHT));
    }

    private int rowY(int index) {
        return this.panelY + 38 + index * ROW_HEIGHT;
    }

    @Override
    protected void build() {
        this.visibleRecords.clear();
        // Widgets are rebuilt on every sync, so nothing stays armed
        this.armedArchive = null;

        List<WorldCollectionRecord> records = ClientWorldCollections.records();
        int rowsPerPage = rowsPerPage();
        this.pageCount = Math.max(1, (records.size() + rowsPerPage - 1) / rowsPerPage);
        this.page = Math.max(0, Math.min(this.page, this.pageCount - 1));
        int firstRow = this.page * rowsPerPage;
        int buttonsX = this.panelX + this.panelWidth - 16 - BUTTONS_WIDTH;

        for (int i = firstRow; i < Math.min(firstRow + rowsPerPage, records.size()); i++) {
            WorldCollectionRecord record = records.get(i);
            this.visibleRecords.add(record);
            int y = rowY(i - firstRow);
            this.addRenderableWidget(Button.builder(label("edit"),
                            button -> this.minecraft.setScreen(new WorldCollectionEditorScreen(this, record.id())))
                    .bounds(buttonsX, y, 52, 20)
                    .build());
            this.addRenderableWidget(Button.builder(label(record.archived() ? "restore" : "archive"),
                            button -> archive(button, record))
                    .bounds(buttonsX + 56, y, 60, 20)
                    .build());
            Button unlock = Button.builder(label("unlock"), button -> unlock(record))
                    .bounds(buttonsX + 120, y, 56, 20)
                    .build();
            // An archived collection, or one with no figure left, has nothing to hand out
            unlock.active = !record.archived() && record.figures().stream().anyMatch(WorldCollectionRecord.Figure::enabled);
            this.addRenderableWidget(unlock);
        }

        int navigationY = this.panelY + this.panelHeight - 104;
        Button previousButton = Button.builder(label("previous"), button -> {
                    this.page--;
                    rebuild();
                })
                .bounds(this.panelX + 16, navigationY, 80, 20)
                .build();
        previousButton.active = this.page > 0;
        this.addRenderableWidget(previousButton);
        Button nextButton = Button.builder(label("next"), button -> {
                    this.page++;
                    rebuild();
                })
                .bounds(this.panelX + this.panelWidth - 96, navigationY, 80, 20)
                .build();
        nextButton.active = this.page + 1 < this.pageCount;
        this.addRenderableWidget(nextButton);

        // New collection: slug, name and Create
        int createY = this.panelY + this.panelHeight - 70;
        int slugWidth = 120;
        int createWidth = 80;
        int nameX = this.panelX + 16 + slugWidth + 6;
        int nameWidth = this.panelWidth - 32 - slugWidth - createWidth - 12;
        textField("slug", this.panelX + 16, createY, slugWidth, 24, this.slug, label("slug_hint"),
                value -> this.slug = value);
        textField("name", nameX, createY, nameWidth, WorldContentLimits.MAX_NAME_LENGTH, this.name,
                label("name_hint"), value -> this.name = value);
        this.createButton = Button.builder(label("create"), button -> create())
                .bounds(this.panelX + this.panelWidth - 16 - createWidth, createY, createWidth, 20)
                .build();
        this.addRenderableWidget(this.createButton);

        this.addRenderableWidget(Button.builder(label("done"), button -> this.onClose())
                .bounds(this.panelX + (this.panelWidth - 100) / 2, this.panelY + this.panelHeight - 28, 100, 20)
                .build());
    }

    @Override
    protected void refreshButtons() {
        if (this.createButton != null) {
            this.createButton.active = !isWaiting() && createProblem() == null;
        }
    }

    /** Why the slug and name cannot make a collection, or null when they can. */
    @Nullable
    private String createProblem() {
        if (this.slug.isEmpty() && this.name.isEmpty()) {
            return "";
        }
        if (!WorldContentIds.isSlug(this.slug)) {
            return tr("slug_rule");
        }
        if (ClientWorldCollections.record(WorldContentIds.collectionId(this.slug)) != null) {
            return tr("slug_taken", WorldContentIds.collectionId(this.slug));
        }
        if (this.name.isBlank() || !WorldCollectionRecord.isPlainText(this.name)) {
            return tr("name_rule");
        }
        if (ClientWorldCollections.records().size() >= WorldContentLimits.MAX_COLLECTIONS) {
            return tr("too_many_collections", WorldContentLimits.MAX_COLLECTIONS);
        }
        return null;
    }

    private void create() {
        if (createProblem() != null) {
            return;
        }
        String id = WorldContentIds.collectionId(this.slug);
        submit(List.of(), new WorldCollectionEdit.Create(this.slug, this.name), outcome -> {
            showOutcome(outcome, Component.translatable("blockpops.world_collections.created", id).getString());
            if (outcome.ok()) {
                this.slug = "";
                this.name = "";
                this.openWhenSynced = id;
                if (this.minecraft.screen == this) {
                    rebuild();
                }
            }
        });
    }

    private void archive(Button button, WorldCollectionRecord record) {
        if (record.archived()) {
            // Restoring only offers the collection again, so it needs no confirmation
            disarm();
            setArchived(record, false);
            return;
        }
        if (button != this.armedArchive) {
            // First click only arms this row; arming another row disarms the previous one
            disarm();
            this.armedArchive = button;
            this.armedAt = System.currentTimeMillis();
            button.setMessage(label("sure"));
            setStatus(tr("archive_confirm", record.name()), WARNING);
            return;
        }
        // The second half of a double-click does not count as the confirmation
        if (System.currentTimeMillis() - this.armedAt < CONFIRM_DELAY_MILLIS) {
            return;
        }
        disarm();
        setArchived(record, true);
    }

    private void disarm() {
        if (this.armedArchive != null) {
            this.armedArchive.setMessage(label("archive"));
            this.armedArchive = null;
        }
    }

    private void setArchived(WorldCollectionRecord record, boolean archived) {
        submit(List.of(), new WorldCollectionEdit.SetArchived(record.id(), archived), outcome -> showOutcome(outcome,
                Component.translatable("blockpops.world_collections." + (archived ? "archived" : "restored"),
                        record.name()).getString()));
    }

    private void unlock(WorldCollectionRecord record) {
        try {
            new UnlockCollectionPacket(record.id()).sendToServer();
            setStatus(tr("unlock_sent", record.name()), SUCCESS);
        } catch (UnsupportedOperationException e) {
            // NeoForge refuses to send a payload the server did not register
            setStatus(tr("not_sent"), ERROR);
        }
    }

    @Override
    protected void onSynced() {
        if (this.openWhenSynced != null && ClientWorldCollections.record(this.openWhenSynced) != null) {
            String id = this.openWhenSynced;
            this.openWhenSynced = null;
            this.minecraft.setScreen(new WorldCollectionEditorScreen(this, id));
        }
    }

    @Override
    protected void accepted(FilePicker.Slot slot, FilePicker.LocalFile file) {
        // This screen has no file slots
    }

    @Override
    protected String idleStatus() {
        String problem = createProblem();
        return problem != null ? problem : "";
    }

    @Override
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int centerX = this.panelX + this.panelWidth / 2;
        List<WorldCollectionRecord> records = ClientWorldCollections.records();
        String usage = tr("usage", records.size(), WorldContentLimits.MAX_COLLECTIONS,
                mib(ClientWorldCollections.quotaUsedKiB()), mib(ClientWorldCollections.quotaMaxKiB()));
        graphics.drawCenteredString(this.font, usage, centerX, this.panelY + 22, MUTED);

        int textX = this.panelX + 16;
        int textWidth = this.panelWidth - 40 - BUTTONS_WIDTH;
        for (int i = 0; i < this.visibleRecords.size(); i++) {
            WorldCollectionRecord record = this.visibleRecords.get(i);
            int y = rowY(i);
            String tag = record.archived() ? tr("tag_archived")
                    : ClientWorldCollections.hasMissingFiles(record.id()) ? tr("tag_missing") : "";
            int tagWidth = tag.isEmpty() ? 0 : this.font.width(tag) + 6;
            drawCut(graphics, record.name(), textX, y + 1, textWidth - tagWidth, record.archived() ? MUTED : TEXT);
            if (!tag.isEmpty()) {
                int nameWidth = this.font.width(cut(record.name(), textWidth - tagWidth));
                graphics.drawString(this.font, tag, textX + nameWidth + 6, y + 1,
                        record.archived() ? WARNING : ERROR);
            }
            long enabled = record.figures().stream().filter(WorldCollectionRecord.Figure::enabled).count();
            drawCut(graphics, tr("row_details", record.id(), enabled, record.figures().size()),
                    textX, y + 11, textWidth, MUTED);
        }
        if (this.visibleRecords.isEmpty()) {
            graphics.drawCenteredString(this.font, tr("empty"), centerX, rowY(0) + 6, MUTED);
        }

        graphics.drawCenteredString(this.font, tr("page", this.page + 1, this.pageCount),
                centerX, this.panelY + this.panelHeight - 98, MUTED);
        drawCut(graphics, tr("create_label"), this.panelX + 16, this.panelY + this.panelHeight - 81,
                this.panelWidth - 32, MUTED);
    }

    /** Kibibytes as mebibytes with one decimal. */
    static String mib(int kib) {
        return String.format(Locale.ROOT, "%.1f", kib / 1024.0);
    }
}
