package com.theplumteam.client.gui;

import com.mojang.authlib.GameProfile;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.SetWorldPlayerEnabledPacket;
import com.theplumteam.util.PlayerSkins;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if <1.21.2 {
import com.mojang.blaze3d.systems.RenderSystem;
//? }

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lists the players of the World Players collection in two columns, those in the collection
 * and those an operator took out of it, and moves a player between them. The server owns the
 * roster: a move only sends a request, and the columns follow the collection the server syncs
 * back.
 */
public class WorldPlayerRosterScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 16;
    private static final int FACE_SIZE = 16;
    private static final int IN_COLOR = 0xFF55C46A;
    private static final int OUT_COLOR = 0xFFE0574A;

    // Skins are looked up once per player while the screen is open
    private static final Map<UUID, GameProfile> PROFILES = new HashMap<>();

    private final Screen parent;
    private final List<FigureDefinition> inside = new ArrayList<>();
    private final List<FigureDefinition> outside = new ArrayList<>();
    private final List<FigureDefinition> visibleInside = new ArrayList<>();
    private final List<FigureDefinition> visibleOutside = new ArrayList<>();

    // What the widgets were built from, to rebuild when either changes
    private FigureCollection shownCollection;
    private boolean shownCanEdit;

    private EditBox searchField;
    private String search = "";
    private String status = "";
    private int insideScroll;
    private int outsideScroll;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int columnWidth;
    private int columnsTop;
    private int columnsBottom;

    public WorldPlayerRosterScreen(Screen parent) {
        super(Component.literal("World Players"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        this.panelWidth = Math.min(440, this.width - 24);
        this.panelHeight = Math.min(300, this.height - 24);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.columnWidth = (this.panelWidth - 32 - 8) / 2;
        this.columnsTop = this.panelY + 34;
        this.columnsBottom = this.panelY + this.panelHeight - 50;

        // The search box outlives rebuilds, so typing keeps its cursor and focus
        int searchWidth = Math.min(140, this.panelWidth / 3);
        this.searchField = new EditBox(this.font, this.panelX + this.panelWidth - 16 - searchWidth, this.panelY + 8,
                searchWidth, 18, Component.literal("Search"));
        this.searchField.setMaxLength(32);
        this.searchField.setHint(Component.literal("Search..."));
        this.searchField.setValue(this.search);
        this.searchField.setResponder(value -> {
            if (!value.equals(this.search)) {
                this.search = value;
                this.insideScroll = 0;
                this.outsideScroll = 0;
                rebuild();
            }
        });

        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        this.shownCollection = CollectionRegistry.getCollection(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID).orElse(null);
        this.shownCanEdit = canEdit();

        this.inside.clear();
        this.outside.clear();
        if (this.shownCollection != null) {
            String query = this.search.trim().toLowerCase(Locale.ROOT);
            for (FigureDefinition figure : this.shownCollection.getFigures()) {
                if (figure.getPlayerUUID() == null
                        || !figure.getName().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                (figure.isEnabled() ? this.inside : this.outside).add(figure);
            }
        }
        Comparator<FigureDefinition> byName = Comparator.comparing(FigureDefinition::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FigureDefinition::getId);
        this.inside.sort(byName);
        this.outside.sort(byName);

        if (this.shownCanEdit) {
            this.status = "Players on the right no longer come out of the machines.";
        } else if (hasPermission()) {
            this.status = "This server cannot change the World Players roster.";
        } else {
            this.status = "Only operators can change the World Players roster.";
        }

        this.addRenderableWidget(this.searchField);

        int rows = visibleRows();
        this.insideScroll = clampScroll(this.insideScroll, this.inside.size(), rows);
        this.outsideScroll = clampScroll(this.outsideScroll, this.outside.size(), rows);
        int leftX = this.panelX + 16;
        int rightX = leftX + this.columnWidth + 8;
        addMoveButtons(this.inside, this.insideScroll, this.visibleInside, leftX + this.columnWidth - 24, "»", false);
        addMoveButtons(this.outside, this.outsideScroll, this.visibleOutside, rightX + 4, "«", true);

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(this.panelX + (this.panelWidth - 100) / 2, this.panelY + this.panelHeight - 28, 100, 20)
                .build());
    }

    private void addMoveButtons(List<FigureDefinition> players, int scroll, List<FigureDefinition> visible,
                                int buttonX, String arrow, boolean enable) {
        visible.clear();
        int rows = visibleRows();
        for (int i = scroll; i < Math.min(scroll + rows, players.size()); i++) {
            FigureDefinition player = players.get(i);
            visible.add(player);
            Button move = Button.builder(Component.literal(arrow),
                            button -> new SetWorldPlayerEnabledPacket(player.getPlayerUUID(), enable).sendToServer())
                    .bounds(buttonX, rowY(i - scroll), 20, 20)
                    .build();
            move.active = this.shownCanEdit;
            this.addRenderableWidget(move);
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.columnsBottom - this.columnsTop - HEADER_HEIGHT - 4) / ROW_HEIGHT);
    }

    private int rowY(int row) {
        return this.columnsTop + HEADER_HEIGHT + 3 + row * ROW_HEIGHT;
    }

    private static int clampScroll(int scroll, int size, int rows) {
        return Math.max(0, Math.min(scroll, size - rows));
    }

    private boolean hasPermission() {
        // Level 2, the same as the Cheats tab; the server checks it again on every move
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
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    //? }
        if (mouseY >= this.columnsTop && mouseY < this.columnsBottom && amount != 0) {
            int step = amount > 0 ? -1 : 1;
            int leftX = this.panelX + 16;
            if (mouseX >= leftX && mouseX < leftX + this.columnWidth) {
                this.insideScroll += step;
                rebuild();
                return true;
            }
            int rightX = leftX + this.columnWidth + 8;
            if (mouseX >= rightX && mouseX < rightX + this.columnWidth) {
                this.outsideScroll += step;
                rebuild();
                return true;
            }
        }
        //? if >=1.21 {
        /*return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        *///? } else {
        return super.mouseScrolled(mouseX, mouseY, amount);
        //? }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Opaque panel over a dimmed world
        graphics.fill(0, 0, this.width, this.height, 0xD0000000);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, 0xFF151515);

        int leftX = this.panelX + 16;
        int rightX = leftX + this.columnWidth + 8;
        graphics.fill(leftX, this.columnsTop, leftX + this.columnWidth, this.columnsBottom, 0xFF0F1410);
        graphics.fill(rightX, this.columnsTop, rightX + this.columnWidth, this.columnsBottom, 0xFF140F0F);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Labels are drawn after the widgets for the same reason as in SettingsScreen:
        // before 1.21.6 anything drawn earlier ends up under the screen background.
        graphics.drawString(this.font, this.title, this.panelX + 16, this.panelY + 13, 0xFFFFFFFF);

        renderColumn(graphics, leftX, "In collection (" + this.inside.size() + ")", IN_COLOR,
                this.visibleInside, this.insideScroll, this.inside.size(), false);
        renderColumn(graphics, rightX, "Out of collection (" + this.outside.size() + ")", OUT_COLOR,
                this.visibleOutside, this.outsideScroll, this.outside.size(), true);

        if (this.inside.isEmpty()) {
            String empty = this.search.isEmpty() ? "No players have joined this world yet." : "No player matches.";
            drawWrappedHint(graphics, empty, leftX);
        }
        if (this.outside.isEmpty()) {
            drawWrappedHint(graphics, this.search.isEmpty() ? "Nobody is out. Press » to take a player out." : "No player matches.", rightX);
        }

        graphics.drawCenteredString(this.font, this.status, this.panelX + this.panelWidth / 2,
                this.columnsBottom + 6, 0xFFAAAAAA);
    }

    private void renderColumn(GuiGraphics graphics, int x, String header, int color, List<FigureDefinition> players,
                              int scroll, int total, boolean out) {
        int right = x + this.columnWidth;
        // One-pixel frame, each pixel drawn once
        graphics.fill(x, this.columnsTop, right, this.columnsTop + 1, color);
        graphics.fill(x, this.columnsBottom - 1, right, this.columnsBottom, color);
        graphics.fill(x, this.columnsTop + 1, x + 1, this.columnsBottom - 1, color);
        graphics.fill(right - 1, this.columnsTop + 1, right, this.columnsBottom - 1, color);
        graphics.fill(x + 1, this.columnsTop + HEADER_HEIGHT, right - 1, this.columnsTop + HEADER_HEIGHT + 1, color);
        graphics.drawString(this.font, header, x + 6, this.columnsTop + 4, color);

        int faceX = out ? x + 30 : x + 6;
        int nameX = faceX + FACE_SIZE + 4;
        int nameWidth = this.columnWidth - FACE_SIZE - 40;
        for (int i = 0; i < players.size(); i++) {
            FigureDefinition player = players.get(i);
            int y = rowY(i);
            drawFace(graphics, skinOf(player), faceX, y + 2);
            if (out) {
                graphics.fill(faceX, y + 2, faceX + FACE_SIZE, y + 2 + FACE_SIZE, 0xAA000000);
            }
            graphics.drawString(this.font, this.font.plainSubstrByWidth(player.getName(), nameWidth),
                    nameX, y + 6, out ? 0xFFB0B0B0 : 0xFFFFFFFF);
        }

        // A scroll bar when the column holds more rows than it shows
        int rows = visibleRows();
        if (total > rows) {
            int trackTop = this.columnsTop + HEADER_HEIGHT + 2;
            int trackHeight = this.columnsBottom - 2 - trackTop;
            int barHeight = Math.max(8, trackHeight * rows / total);
            int barTop = trackTop + (trackHeight - barHeight) * scroll / (total - rows);
            int barX = out ? right - 4 : x + this.columnWidth - 30;
            graphics.fill(barX, barTop, barX + 2, barTop + barHeight, 0xFF8A8A8A);
        }
    }

    private void drawWrappedHint(GuiGraphics graphics, String text, int x) {
        int y = this.columnsTop + HEADER_HEIGHT + 8;
        for (var line : this.font.split(Component.literal(text), this.columnWidth - 12)) {
            graphics.drawString(this.font, line, x + 6, y, 0xFF8A8A8A);
            y += 10;
        }
    }

    /** The player's skin: from the tab list while they are online, else looked up by profile. */
    private ResourceLocation skinOf(FigureDefinition player) {
        UUID uuid = player.getPlayerUUID();
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            PlayerInfo info = this.minecraft.getConnection().getPlayerInfo(uuid);
            if (info != null) {
                //? if >=1.21.9 {
                /*return info.getSkin().body().texturePath();
                *///? } elif >=1.21 {
                /*return info.getSkin().texture();
                *///? } else {
                return info.getSkinLocation();
                //? }
            }
        }
        GameProfile profile = PROFILES.get(uuid);
        if (profile == null) {
            profile = new GameProfile(uuid, player.getName());
            PROFILES.put(uuid, profile);
            //? if >=1.21.9 {
            /*// The populated profile is a new one there, so it replaces the cached one.
            com.theplumteam.client.ClientSkinRegistration.register(profile, prepared -> PROFILES.put(uuid, prepared));
            *///? } elif >=1.21 {
            /*com.theplumteam.client.ClientSkinRegistration.register(profile);
            *///? } else {
            net.minecraft.client.Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, p) -> {}, false);
            //? }
        }
        return PlayerSkins.insecureTexture(profile);
    }

    /** The face and hat layers of a 64x64 skin. */
    private static void drawFace(GuiGraphics graphics, ResourceLocation skin, int x, int y) {
        //? if >=1.21.6 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin, x, y, 8.0f, 8.0f,
                FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, skin, x, y, 40.0f, 8.0f,
                FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
        *///? } elif >=1.21.2 {
        /*graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, skin, x, y, 8.0f, 8.0f,
                FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
        graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, skin, x, y, 40.0f, 8.0f,
                FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
        *///? } else {
        graphics.blit(skin, x, y, FACE_SIZE, FACE_SIZE, 8.0f, 8.0f, 8, 8, 64, 64);
        RenderSystem.enableBlend();
        graphics.blit(skin, x, y, FACE_SIZE, FACE_SIZE, 40.0f, 8.0f, 8, 8, 64, 64);
        RenderSystem.disableBlend();
        //? }
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
