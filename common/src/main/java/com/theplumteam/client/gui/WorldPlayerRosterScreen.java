package com.theplumteam.client.gui;

import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.SetWorldPlayerEnabledPacket;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists every player of the World Players collection so an operator can disable or
 * re-enable them. The server owns the roster: a toggle only sends a request, and the
 * list follows the collection the server syncs back.
 */
public class WorldPlayerRosterScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final List<FigureDefinition> visiblePlayers = new ArrayList<>();

    // What the widgets were built from, to rebuild when either changes
    private FigureCollection shownCollection;
    private boolean shownCanEdit;

    private String status = "";
    private int page;
    private int pageCount = 1;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public WorldPlayerRosterScreen(Screen parent) {
        super(Component.literal("World Players"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        this.panelWidth = Math.min(420, this.width - 24);
        this.panelHeight = Math.min(350, this.height - 24);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        this.visiblePlayers.clear();
        this.shownCollection = CollectionRegistry.getCollection(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID).orElse(null);
        this.shownCanEdit = canEdit();

        List<FigureDefinition> players = new ArrayList<>();
        int disabledCount = 0;
        if (this.shownCollection != null) {
            for (FigureDefinition figure : this.shownCollection.getFigures()) {
                if (figure.getPlayerUUID() != null) {
                    players.add(figure);
                    if (!figure.isEnabled()) {
                        disabledCount++;
                    }
                }
            }
        }
        players.sort(Comparator.comparing(FigureDefinition::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FigureDefinition::getId));

        if (this.shownCanEdit) {
            this.status = "Players: " + players.size() + " (" + disabledCount + " disabled)";
        } else if (hasPermission()) {
            this.status = "This server cannot change the World Players roster.";
        } else {
            this.status = "Only operators can change the World Players roster.";
        }

        int rowsPerPage = Math.max(1, (this.panelHeight - 106) / ROW_HEIGHT);
        this.pageCount = Math.max(1, (players.size() + rowsPerPage - 1) / rowsPerPage);
        this.page = Math.min(this.page, this.pageCount - 1);
        int firstRow = this.page * rowsPerPage;
        int toggleX = this.panelX + this.panelWidth - 96;

        for (int i = firstRow; i < Math.min(firstRow + rowsPerPage, players.size()); i++) {
            FigureDefinition player = players.get(i);
            this.visiblePlayers.add(player);

            // The label only changes once the server has synced the collection back
            Button toggle = Button.builder(Component.literal(player.isEnabled() ? "Disable" : "Enable"),
                            button -> new SetWorldPlayerEnabledPacket(player.getPlayerUUID(), !player.isEnabled()).sendToServer())
                    .bounds(toggleX, this.panelY + 48 + (i - firstRow) * ROW_HEIGHT, 80, 20)
                    .build();
            toggle.active = this.shownCanEdit;
            this.addRenderableWidget(toggle);
        }

        int navigationY = this.panelY + this.panelHeight - 54;
        Button previousButton = Button.builder(Component.literal("Previous"), button -> {
                    this.page--;
                    rebuild();
                })
                .bounds(this.panelX + 16, navigationY, 80, 20)
                .build();
        previousButton.active = this.page > 0;
        this.addRenderableWidget(previousButton);

        Button nextButton = Button.builder(Component.literal("Next"), button -> {
                    this.page++;
                    rebuild();
                })
                .bounds(toggleX, navigationY, 80, 20)
                .build();
        nextButton.active = this.page + 1 < this.pageCount;
        this.addRenderableWidget(nextButton);

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(this.panelX + (this.panelWidth - 100) / 2, this.panelY + this.panelHeight - 28, 100, 20)
                .build());
    }

    private boolean hasPermission() {
        // Level 2, the same as the Cheats tab; the server checks it again on every toggle
        return this.minecraft != null && this.minecraft.player != null
                && ServerLevels.hasCommandLevel(this.minecraft.player, 2);
    }

    private boolean canEdit() {
        return hasPermission() && canServerReceive();
    }

    /**
     * A server that predates the roster never registered the packet, and some loaders
     * reject sending to a channel the server did not announce.
     */
    private static boolean canServerReceive() {
        //? if >=26 {
        /*return NetworkManager.canServerReceive(
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<
                        com.theplumteam.network.PacketNetworking.RawPayload>(SetWorldPlayerEnabledPacket.ID));
        *///? } else {
        return NetworkManager.canServerReceive(SetWorldPlayerEnabledPacket.ID);
        //? }
    }

    @Override
    public void tick() {
        super.tick();
        // A re-sync replaces the registered collection, and operator status can change while the screen is open
        FigureCollection current = CollectionRegistry.getCollection(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID).orElse(null);
        if (current != this.shownCollection || canEdit() != this.shownCanEdit) {
            rebuild();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Opaque panel over a dimmed world
        graphics.fill(0, 0, this.width, this.height, 0xD0000000);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, 0xFF151515);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Labels are drawn after the widgets for the same reason as in SettingsScreen:
        // before 1.21.6 anything drawn earlier ends up under the screen background.
        int centerX = this.panelX + this.panelWidth / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, this.panelY + 12, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, centerX, this.panelY + 28, 0xFFAAAAAA);

        int textWidth = this.panelWidth - 128;
        for (int i = 0; i < this.visiblePlayers.size(); i++) {
            FigureDefinition player = this.visiblePlayers.get(i);
            int rowY = this.panelY + 48 + i * ROW_HEIGHT;
            String name = player.isEnabled() ? player.getName() : player.getName() + " (disabled)";
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, textWidth),
                    this.panelX + 16, rowY + 1, player.isEnabled() ? 0xFFFFFFFF : 0xFFFF5555);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(player.getId(), textWidth),
                    this.panelX + 16, rowY + 11, 0xFFAAAAAA);
        }

        if (this.visiblePlayers.isEmpty()) {
            graphics.drawCenteredString(this.font, "No players have joined this world yet.",
                    centerX, this.panelY + 60, 0xFFAAAAAA);
        }
        graphics.drawCenteredString(this.font, "Page " + (this.page + 1) + " of " + this.pageCount,
                centerX, this.panelY + this.panelHeight - 48, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        // Return to parent screen
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
