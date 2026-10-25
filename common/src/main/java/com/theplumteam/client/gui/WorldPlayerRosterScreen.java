package com.theplumteam.client.gui;

import com.theplumteam.client.QuickSkinClient;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.DialogScreen;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.SetWorldPlayerEnabledPacket;
import com.theplumteam.util.PlayerSkins;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
 * A dialog over the settings that lists the players of the World Players collection in two
 * columns, those in the collection and those an operator took out of it, and moves a player
 * between them. The server owns the roster: a move only sends a request, and the columns
 * follow the collection the server syncs back.
 *
 * <p>Each column is a sunk well with a header and a row per player, in the settings' row style:
 * the face and name, a hairline under the row and a small button that moves the player across.
 */
public class WorldPlayerRosterScreen extends DialogScreen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 18;
    private static final int FACE_SIZE = 16;
    private static final int MOVE_WIDTH = 20;
    private static final int MOVE_HEIGHT = 18;
    private static final int COLUMN_GAP = 8;

    private static final int HEADER_BG = 0xFF2A2B2E;
    private static final int HEADER_LINE = 0xFF46474A;
    private static final int SCROLL_BAR = 0xFF6E6F73;

    // Skins are looked up once per player while the screen is open
    private static final Map<UUID, GameProfile> PROFILES = new HashMap<>();

    private final List<FigureDefinition> inside = new ArrayList<>();
    private final List<FigureDefinition> outside = new ArrayList<>();
    private final List<FigureDefinition> visibleInside = new ArrayList<>();
    private final List<FigureDefinition> visibleOutside = new ArrayList<>();
    // The move buttons of the rows shown, replaced on every rebuild
    private final List<AbstractWidget> moveButtons = new ArrayList<>();

    // What the widgets were built from, to rebuild when either changes
    private FigureCollection shownCollection;
    private boolean shownCanEdit;

    private EditBox searchField;
    private String search = "";
    private String status = "";
    private int insideScroll;
    private int outsideScroll;

    private int columnWidth;
    private int leftX;
    private int rightX;
    private int columnsTop;
    private int columnsBottom;

    public WorldPlayerRosterScreen(Screen parent) {
        super(Component.literal("World Players"), parent);
    }

    @Override
    protected DialogFrame.Accent accent() {
        return DialogFrame.Accent.COLLECTION;
    }

    @Override
    protected String[] icon() {
        return PixelIcon.PLAYER;
    }

    @Override
    protected int preferredHeight() {
        return 300;
    }

    @Override
    protected void initDialog() {
        this.leftX = this.dialogX + DialogFrame.PADDING;
        this.columnWidth = (this.dialogWidth - 2 * DialogFrame.PADDING - COLUMN_GAP) / 2;
        this.rightX = this.leftX + this.columnWidth + COLUMN_GAP;
        this.columnsTop = bodyTop() + 28;
        this.columnsBottom = bodyBottom() - 20;

        // The search box outlives rebuilds, so typing keeps its cursor and focus
        int searchWidth = Math.min(140, this.dialogWidth / 3);
        this.searchField = new EditBox(this.font, this.dialogX + this.dialogWidth - DialogFrame.PADDING - searchWidth,
                bodyTop() + 5, searchWidth, 16, Component.literal("Search"));
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
        this.addRenderableWidget(this.searchField);

        rebuild();
    }

    private void rebuild() {
        this.moveButtons.forEach(this::removeWidget);
        this.moveButtons.clear();
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

        int rows = visibleRows();
        this.insideScroll = clampScroll(this.insideScroll, this.inside.size(), rows);
        this.outsideScroll = clampScroll(this.outsideScroll, this.outside.size(), rows);
        addMoveButtons(this.inside, this.insideScroll, this.visibleInside,
                this.leftX + this.columnWidth - 4 - MOVE_WIDTH, "»", false);
        addMoveButtons(this.outside, this.outsideScroll, this.visibleOutside, this.rightX + 4, "«", true);
    }

    private void addMoveButtons(List<FigureDefinition> players, int scroll, List<FigureDefinition> visible,
                                int buttonX, String arrow, boolean enable) {
        visible.clear();
        int rows = visibleRows();
        for (int i = scroll; i < Math.min(scroll + rows, players.size()); i++) {
            FigureDefinition player = players.get(i);
            visible.add(player);
            StyledButton move = new StyledButton(buttonX, rowY(i - scroll) + (ROW_HEIGHT - 1 - MOVE_HEIGHT) / 2,
                    MOVE_WIDTH, MOVE_HEIGHT, Component.literal(arrow),
                    button -> new SetWorldPlayerEnabledPacket(player.getPlayerUUID(), enable).sendToServer())
                    .lipHeight(2);
            move.active = this.shownCanEdit;
            this.moveButtons.add(move);
            this.addRenderableWidget(move);
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.columnsBottom - 2 - rowY(0)) / ROW_HEIGHT);
    }

    private int rowY(int row) {
        return this.columnsTop + HEADER_HEIGHT + 2 + row * ROW_HEIGHT;
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
            if (mouseX >= this.leftX && mouseX < this.leftX + this.columnWidth) {
                this.insideScroll += step;
                rebuild();
                return true;
            }
            if (mouseX >= this.rightX && mouseX < this.rightX + this.columnWidth) {
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
    protected void renderBody(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderColumnBody(graphics, this.leftX, this.visibleInside.size(), this.insideScroll, this.inside.size(), false);
        renderColumnBody(graphics, this.rightX, this.visibleOutside.size(), this.outsideScroll, this.outside.size(), true);
    }

    /** A column's well, its header band and the hairlines under its rows, under the move buttons. */
    private void renderColumnBody(GuiGraphics graphics, int x, int shownRows, int scroll, int total, boolean out) {
        int right = x + this.columnWidth;
        Surfaces.well(graphics, x, this.columnsTop, right, this.columnsBottom, Surfaces.BORDER, Surfaces.WELL);
        int headerBottom = this.columnsTop + HEADER_HEIGHT;
        graphics.fill(x + 2, this.columnsTop + 2, right - 2, headerBottom - 1, HEADER_BG);
        graphics.fill(x + 2, headerBottom - 1, right - 2, headerBottom, HEADER_LINE);
        for (int i = 0; i < shownRows; i++) {
            int lineY = rowY(i) + ROW_HEIGHT - 1;
            graphics.fill(x + 2, lineY, right - 2, lineY + 1, Surfaces.ROW_LINE);
        }

        // A scroll bar when the column holds more rows than it shows, clear of the move buttons
        int rows = visibleRows();
        if (total > rows) {
            int trackTop = headerBottom + 2;
            int trackHeight = this.columnsBottom - 3 - trackTop;
            int barHeight = Math.max(8, trackHeight * rows / total);
            int barTop = trackTop + (trackHeight - barHeight) * scroll / (total - rows);
            int barX = out ? right - 5 : right - 4 - MOVE_WIDTH - 5;
            graphics.fill(barX, barTop, barX + 2, barTop + barHeight, SCROLL_BAR);
        }
    }

    @Override
    protected void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Labels are drawn after the widgets: before 1.21.6 anything drawn earlier ends up
        // under the widgets' fills
        int total = this.inside.size() + this.outside.size();
        String summary = total + (total == 1 ? " player, " : " players, ") + this.outside.size() + " out";
        UiText.drawFitted(graphics, this.font, summary, this.leftX, bodyTop() + 9,
                this.searchField.getX() - 8 - this.leftX, Surfaces.TEXT_DIM);

        renderColumn(graphics, this.leftX, "In collection", this.visibleInside, this.inside.size(), false);
        renderColumn(graphics, this.rightX, "Out of collection", this.visibleOutside, this.outside.size(), true);

        if (this.inside.isEmpty()) {
            String empty = this.search.isEmpty() ? "No players have joined this world yet." : "No player matches.";
            drawWrappedHint(graphics, empty, this.leftX);
        }
        if (this.outside.isEmpty()) {
            drawWrappedHint(graphics, this.search.isEmpty() ? "Nobody is out. Press » to take a player out." : "No player matches.",
                    this.rightX);
        }

        String fitted = UiText.fit(this.font, this.status, this.dialogWidth - 2 * DialogFrame.PADDING);
        UiText.drawCentered(graphics, this.font, fitted, this.dialogX + this.dialogWidth / 2,
                this.columnsBottom + 6, Surfaces.TEXT_DIM);
    }

    private void renderColumn(GuiGraphics graphics, int x, String header, List<FigureDefinition> players,
                              int total, boolean out) {
        int right = x + this.columnWidth;
        String count = String.valueOf(total);
        int headerY = this.columnsTop + (HEADER_HEIGHT - 8) / 2 + 1;
        UiText.drawFitted(graphics, this.font, header, x + 6, headerY,
                this.columnWidth - 18 - this.font.width(count), Surfaces.TEXT);
        UiText.draw(graphics, this.font, count, right - 6 - this.font.width(count), headerY, Surfaces.TEXT_DIM);

        int faceX = out ? x + 4 + MOVE_WIDTH + 6 : x + 6;
        int nameX = faceX + FACE_SIZE + 4;
        int nameWidth = this.columnWidth - FACE_SIZE - MOVE_WIDTH - 24;
        for (int i = 0; i < players.size(); i++) {
            FigureDefinition player = players.get(i);
            int y = rowY(i);
            int faceY = y + (ROW_HEIGHT - 1 - FACE_SIZE) / 2;
            drawFace(graphics, skinOf(player), faceX, faceY);
            if (out) {
                graphics.fill(faceX, faceY, faceX + FACE_SIZE, faceY + FACE_SIZE, 0xAA000000);
            }
            UiText.drawFitted(graphics, this.font, player.getName(), nameX, y + (ROW_HEIGHT - 1 - 8) / 2,
                    nameWidth, out ? Surfaces.TEXT_DIM : Surfaces.TEXT);
        }
    }

    private void drawWrappedHint(GuiGraphics graphics, String text, int x) {
        int y = rowY(0) + 6;
        int room = this.columnWidth - 12;
        for (String line : UiText.wrap(this.font, text, room)) {
            UiText.drawFitted(graphics, this.font, line, x + 6, y, room, Surfaces.TEXT_MUTED);
            y += 10;
        }
    }

    /**
     * The player's skin: Quick Skin's, which it knows for players who are offline too, else the
     * tab list's while they are online, else looked up by profile.
     */
    private ResourceLocation skinOf(FigureDefinition player) {
        UUID uuid = player.getPlayerUUID();
        ResourceLocation quickSkin = QuickSkinClient.playerSkin(uuid);
        if (quickSkin != null) {
            return quickSkin;
        }
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
        // A click outside the dialog closes it, as in SettingsScreen
        if (isOutsideDialog(bpEvent.x(), bpEvent.y())) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(bpEvent, bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click outside the dialog closes it, as in SettingsScreen
        if (isOutsideDialog(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    private boolean isOutsideDialog(double mouseX, double mouseY) {
        return mouseX < this.dialogX || mouseX > this.dialogX + this.dialogWidth
                || mouseY < this.dialogY || mouseY > this.dialogY + this.dialogHeight;
    }
}
