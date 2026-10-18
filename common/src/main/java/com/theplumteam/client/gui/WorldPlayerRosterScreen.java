package com.theplumteam.client.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.util.GuiPose;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A modal over the settings that lists the players of the World Players collection in two
 * columns, those in the collection and those an operator took out of it, and moves a player
 * between them. The server owns the roster: a move only sends a request, and the columns
 * follow the collection the server syncs back.
 */
public class WorldPlayerRosterScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 16;
    private static final int FACE_SIZE = 16;

    // The settings modal's styling
    private static final int PANEL_OUTLINE = 0x60FFFFFF;
    private static final int MODAL_BASE = 0xFF000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFAAAAAA;

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
        this.columnsTop = this.panelY + 40;
        this.columnsBottom = this.panelY + this.panelHeight - 50;

        // The search box outlives rebuilds, so typing keeps its cursor and focus
        int searchWidth = Math.min(140, this.panelWidth / 3);
        this.searchField = new EditBox(this.font, this.panelX + this.panelWidth - 16 - searchWidth, this.panelY + 12,
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

        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
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
        // The settings stay underneath, as SettingsScreen keeps the machine screen under itself
        if (this.parent != null) {
            this.parent.render(graphics, -1, -1, partialTicks);
        }
        GuiPose.flush(graphics);

        // Force the modal above the parent, the same way SettingsScreen does
        //? if >=1.21.6 {
        /*GuiPose.push(graphics);
        *///? } elif >=1.21.5 {
        /*graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        *///? } elif >=1.21.2 {
        /*RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256); // Clear depth buffer only
        *///? } else {
        RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256, false); // Clear depth buffer only
        //? }

        // The settings modal's panel: an opaque base, the themed translucent black and a subtle outline
        graphics.fill(0, 0, this.width, this.height, 0x70000000);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, MODAL_BASE);
        int alpha = (int) (ClientConfig.getInstance().panelOpacity * 255);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, alpha << 24);
        graphics.renderOutline(this.panelX, this.panelY, this.panelWidth, this.panelHeight, PANEL_OUTLINE);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // Labels are drawn after the widgets for the same reason as in SettingsScreen:
        // before 1.21.6 anything drawn earlier ends up under the screen background.
        int total = this.inside.size() + this.outside.size();
        graphics.drawString(this.font, this.title, this.panelX + 16, this.panelY + 10, TEXT_COLOR);
        graphics.drawString(this.font, total + (total == 1 ? " player, " : " players, ") + this.outside.size() + " out",
                this.panelX + 16, this.panelY + 22, SECONDARY_COLOR);

        int leftX = this.panelX + 16;
        int rightX = leftX + this.columnWidth + 8;
        renderColumn(graphics, leftX, "In collection", this.visibleInside, this.insideScroll, this.inside.size(), false);
        renderColumn(graphics, rightX, "Out of collection", this.visibleOutside, this.outsideScroll, this.outside.size(), true);

        if (this.inside.isEmpty()) {
            String empty = this.search.isEmpty() ? "No players have joined this world yet." : "No player matches.";
            drawWrappedHint(graphics, empty, leftX);
        }
        if (this.outside.isEmpty()) {
            drawWrappedHint(graphics, this.search.isEmpty() ? "Nobody is out. Press » to take a player out." : "No player matches.", rightX);
        }

        graphics.drawCenteredString(this.font, this.status, this.panelX + this.panelWidth / 2,
                this.columnsBottom + 6, SECONDARY_COLOR);

        //? if >=1.21.5 {
        /*GuiPose.pop(graphics);
        *///? }
    }

    private void renderColumn(GuiGraphics graphics, int x, String header, List<FigureDefinition> players,
                              int scroll, int total, boolean out) {
        int right = x + this.columnWidth;
        // The settings panel's outline, with a rule under the header as in the collection list
        graphics.renderOutline(x, this.columnsTop, this.columnWidth, this.columnsBottom - this.columnsTop, PANEL_OUTLINE);
        graphics.fill(x + 1, this.columnsTop + HEADER_HEIGHT, right - 1, this.columnsTop + HEADER_HEIGHT + 1, PANEL_OUTLINE);
        graphics.drawString(this.font, header, x + 6, this.columnsTop + 4, TEXT_COLOR);
        String count = String.valueOf(total);
        graphics.drawString(this.font, count, right - 6 - this.font.width(count), this.columnsTop + 4, SECONDARY_COLOR);

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
                    nameX, y + 6, out ? SECONDARY_COLOR : TEXT_COLOR);
        }

        // A scroll bar when the column holds more rows than it shows
        int rows = visibleRows();
        if (total > rows) {
            int trackTop = this.columnsTop + HEADER_HEIGHT + 2;
            int trackHeight = this.columnsBottom - 2 - trackTop;
            int barHeight = Math.max(8, trackHeight * rows / total);
            int barTop = trackTop + (trackHeight - barHeight) * scroll / (total - rows);
            int barX = out ? right - 4 : x + this.columnWidth - 30;
            graphics.fill(barX, barTop, barX + 2, barTop + barHeight, SECONDARY_COLOR);
        }
    }

    private void drawWrappedHint(GuiGraphics graphics, String text, int x) {
        int y = this.columnsTop + HEADER_HEIGHT + 8;
        for (var line : this.font.split(Component.literal(text), this.columnWidth - 12)) {
            graphics.drawString(this.font, line, x + 6, y, SECONDARY_COLOR);
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
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        // A click outside the panel closes the modal, as in SettingsScreen
        if (isOutsidePanel(bpEvent.x(), bpEvent.y())) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(bpEvent, bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click outside the panel closes the modal, as in SettingsScreen
        if (isOutsidePanel(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    private boolean isOutsidePanel(double mouseX, double mouseY) {
        return mouseX < this.panelX || mouseX > this.panelX + this.panelWidth
                || mouseY < this.panelY || mouseY > this.panelY + this.panelHeight;
    }

    @Override
    public void onClose() {
        // Back to the settings, which stayed underneath
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
