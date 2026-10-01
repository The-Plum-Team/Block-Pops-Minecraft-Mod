package com.theplumteam.client.gui;

import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.AdminProgressionPacket;
import com.theplumteam.network.AdminProgressionSyncPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Operator screen for viewing an online player's collection progress and re-locking or
 * unlocking whole collections. The server owns every check and every change; this screen
 * only sends requests and draws the last answer.
 */
public class AdminProgressionScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int GRID_TOP = 58;
    private static final int MAX_COLUMN_WIDTH = 380;
    private static final int BUTTONS_WIDTH = 100;
    private static final int COUNT_WIDTH = 44;

    private final Screen parent;
    private final List<FigureCollection> collections = new ArrayList<>();
    private int rowsPerColumn = 1;
    private int columnWidth = MAX_COLUMN_WIDTH;
    private int gridX;

    // The player this screen last asked the server about
    private UUID viewed;
    // A re-lock cannot be undone, so its button has to be clicked twice
    private Button armedLock;
    private long armedAt;
    // Set when the server never registered the editor's packet (an older BlockPops)
    private boolean unsupported;

    public AdminProgressionScreen(Screen parent) {
        super(Component.literal("Player Progression"));
        this.parent = parent;
        // Never show what an earlier session or server answered.
        AdminProgressionSyncPacket.clearLatest();
    }

    @Override
    protected void init() {
        super.init();

        // Widgets are rebuilt on resize, so nothing stays armed
        this.armedLock = null;

        collections.clear();
        for (FigureCollection collection : CollectionRegistry.getAllCollections()) {
            // Filter out the default collection, as the Cheats tab does
            if (!collection.getId().equals("default")) {
                collections.add(collection);
            }
        }

        // Rows wrap into as many columns as the height requires
        int gridBottom = this.height - 34;
        this.rowsPerColumn = Math.max(1, (gridBottom - GRID_TOP) / ROW_HEIGHT);
        int columns = Math.max(1, (collections.size() + rowsPerColumn - 1) / rowsPerColumn);
        this.columnWidth = Math.min(MAX_COLUMN_WIDTH, (this.width - 20) / columns);
        this.gridX = (this.width - columns * columnWidth) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("<"), button -> cycleTarget(-1))
                .bounds(this.width / 2 - 130, 30, 20, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), button -> cycleTarget(1))
                .bounds(this.width / 2 + 110, 30, 20, 20).build());

        for (int i = 0; i < collections.size(); i++) {
            String collectionId = collections.get(i).getId();
            int buttonsX = rowX(i) + columnWidth - BUTTONS_WIDTH;
            this.addRenderableWidget(Button.builder(Component.literal("Lock"),
                            button -> lock(button, collectionId))
                    .bounds(buttonsX, rowY(i), 44, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Unlock"),
                            button -> {
                                disarm();
                                edit(AdminProgressionPacket.UNLOCK, collectionId);
                            })
                    .bounds(buttonsX + 48, rowY(i), 48, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());

        // Start with the admin's own progress
        if (this.viewed == null && this.minecraft.player != null) {
            this.viewed = this.minecraft.player.getUUID();
        }
        if (this.viewed != null) {
            view(this.viewed);
        }
    }

    private int rowX(int index) {
        return gridX + (index / rowsPerColumn) * columnWidth;
    }

    private int rowY(int index) {
        return GRID_TOP + (index % rowsPerColumn) * ROW_HEIGHT;
    }

    /** The server's last answer, once it is about the player this screen asked for. */
    private AdminProgressionSyncPacket snapshot() {
        AdminProgressionSyncPacket latest = AdminProgressionSyncPacket.latest();
        return latest != null && latest.getTarget().equals(this.viewed) ? latest : null;
    }

    private void view(UUID player) {
        disarm();
        this.viewed = player;
        send(AdminProgressionPacket.VIEW, "");
    }

    private void send(int action, String collectionId) {
        try {
            new AdminProgressionPacket(this.viewed, action, collectionId).sendToServer();
        } catch (UnsupportedOperationException e) {
            // NeoForge refuses to send a payload the server did not register. Letting
            // that leave a click handler would crash the client.
            this.unsupported = true;
        }
    }

    private void cycleTarget(int step) {
        AdminProgressionSyncPacket snapshot = snapshot();
        if (snapshot == null || snapshot.getPlayerIds().isEmpty()) {
            return;
        }
        List<UUID> players = snapshot.getPlayerIds();
        // A player who left has no place in the list; start again from the first one
        int current = players.indexOf(this.viewed);
        view(players.get(current < 0 ? 0 : Math.floorMod(current + step, players.size())));
    }

    /** Whether the server confirmed that the player on screen is online, so can be edited. */
    private boolean canEdit() {
        AdminProgressionSyncPacket snapshot = snapshot();
        return snapshot != null && snapshot.getPlayerIds().contains(this.viewed);
    }

    private void edit(int action, String collectionId) {
        if (canEdit()) {
            send(action, collectionId);
        }
    }

    private void lock(Button button, String collectionId) {
        if (!canEdit()) {
            return;
        }
        if (button != this.armedLock) {
            // First click only arms this row; arming another row disarms the previous one
            disarm();
            this.armedLock = button;
            this.armedAt = System.currentTimeMillis();
            button.setMessage(Component.literal("Sure?"));
            return;
        }
        // The second half of a double-click does not count as the confirmation
        if (System.currentTimeMillis() - this.armedAt < 500) {
            return;
        }
        disarm();
        edit(AdminProgressionPacket.LOCK, collectionId);
    }

    private void disarm() {
        if (this.armedLock != null) {
            this.armedLock.setMessage(Component.literal("Lock"));
            this.armedLock = null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // From 1.20.2 the base screen draws its own background; drawing it again here
        // would blur twice in one frame, which 1.21.6 rejects.
        //? if <1.21 {
        this.renderBackground(graphics);
        //? }
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        AdminProgressionSyncPacket snapshot = snapshot();
        int selected = snapshot == null ? -1 : snapshot.getPlayerIds().indexOf(this.viewed);
        String header;
        if (snapshot == null) {
            header = this.unsupported ? "The server's BlockPops is too old"
                    : "Waiting for the server (operators only)...";
        } else if (selected < 0) {
            header = "Player left - pick another";
        } else {
            header = snapshot.getPlayerNames().get(selected)
                    + " (" + (selected + 1) + "/" + snapshot.getPlayerIds().size() + " online)";
        }
        graphics.drawCenteredString(this.font, header, this.width / 2, 36, selected < 0 ? 0xFFAAAAAA : 0xFFFFFFFF);

        int nameWidth = columnWidth - BUTTONS_WIDTH - COUNT_WIDTH - 8;
        for (int i = 0; i < collections.size(); i++) {
            FigureCollection collection = collections.get(i);
            int textY = rowY(i) + 6;
            graphics.drawString(this.font, this.font.plainSubstrByWidth(collection.getName(), nameWidth),
                    rowX(i) + 4, textY, 0xFFFFFFFF);
            if (selected < 0) {
                continue;
            }
            int discovered = 0;
            for (FigureDefinition figure : collection.getFigures()) {
                if (snapshot.getDiscovered().contains(collection.getId() + ":" + figure.getId())) {
                    discovered++;
                }
            }
            graphics.drawString(this.font, discovered + "/" + collection.getFigures().size(),
                    rowX(i) + columnWidth - BUTTONS_WIDTH - COUNT_WIDTH, textY, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        // Return to the settings modal
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
