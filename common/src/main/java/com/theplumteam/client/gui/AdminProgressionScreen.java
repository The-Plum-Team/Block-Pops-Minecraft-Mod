package com.theplumteam.client.gui;

import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.DialogScreen;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.network.AdminProgressionPacket;
import com.theplumteam.network.AdminProgressionSyncPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Operator screen for viewing an online player's collection progress and re-locking or
 * unlocking whole collections. The server owns every check and every change; this screen
 * only sends requests and draws the last answer. Opened from the World collection creator, it
 * lists only the collection just published.
 *
 * <p>A dialog in the collections' purple over the screen it opens from: the player picker under
 * the header, then a row per collection (its name, the player's count and the Lock and Unlock
 * buttons), wrapping into as many columns as the dialog's height requires.
 */
public class AdminProgressionScreen extends DialogScreen {
    private static final int PADDING = DialogFrame.PADDING;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_BUTTON_HEIGHT = 18;
    private static final int PICKER_HEIGHT = 20;
    private static final int PICKER_GAP = 8;
    private static final int FOOTER_HEIGHT = 22;
    private static final int FOOTER_GAP = 8;
    /** The dialog's height around its rows: header, picker, footer, padding and lip. */
    private static final int CHROME_HEIGHT = DialogFrame.HEADER_HEIGHT + PADDING + PICKER_HEIGHT + PICKER_GAP
            + FOOTER_GAP + FOOTER_HEIGHT + PADDING + 2 + Surfaces.LIP_HEIGHT;
    private static final int MAX_DIALOG_HEIGHT = 340;
    private static final int MIN_DIALOG_WIDTH = 360;
    private static final int PREFERRED_COLUMN_WIDTH = 280;
    private static final int MAX_COLUMN_WIDTH = 380;
    private static final int COLUMN_GAP = 10;
    private static final int LOCK_WIDTH = 46;
    private static final int UNLOCK_WIDTH = 50;
    private static final int BUTTONS_WIDTH = LOCK_WIDTH + 4 + UNLOCK_WIDTH;
    private static final int COUNT_WIDTH = 44;

    // The one collection listed, or null for every collection
    @Nullable
    private final String onlyCollectionId;
    private final List<FigureCollection> collections = new ArrayList<>();
    private int rowsPerColumn = 1;
    private int columnWidth = MAX_COLUMN_WIDTH;
    private int gridX;
    private int gridTop;

    // The player this screen last asked the server about
    private UUID viewed;
    // A re-lock cannot be undone, so its button has to be clicked twice
    private StyledButton armedLock;
    private long armedAt;
    // Set when the server never registered the editor's packet (an older BlockPops)
    private boolean unsupported;

    public AdminProgressionScreen(Screen parent) {
        this(parent, null);
    }

    /**
     * @param onlyCollectionId the one collection to list, such as a World collection the
     *                         creator just published; null lists every collection
     */
    public AdminProgressionScreen(Screen parent, @Nullable String onlyCollectionId) {
        super(Component.literal("Player Progression"), parent);
        this.onlyCollectionId = onlyCollectionId;
        // Never show what an earlier session or server answered.
        AdminProgressionSyncPacket.clearLatest();
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
        return Math.min(MAX_DIALOG_HEIGHT, CHROME_HEIGHT + Math.max(1, collections.size()) * ROW_HEIGHT);
    }

    @Override
    protected int preferredWidth() {
        // As many columns as the rows need at the height the dialog will get
        int height = Math.min(preferredHeight(), this.height - 16);
        int rows = Math.max(1, (height - CHROME_HEIGHT) / ROW_HEIGHT);
        int columns = Math.max(1, (collections.size() + rows - 1) / rows);
        return Math.max(MIN_DIALOG_WIDTH, columns * PREFERRED_COLUMN_WIDTH + (columns - 1) * COLUMN_GAP + 2 * PADDING);
    }

    @Override
    protected void init() {
        // Widgets are rebuilt on resize, so nothing stays armed
        this.armedLock = null;

        // The collections first: the dialog's size depends on how many there are
        collections.clear();
        if (this.onlyCollectionId != null) {
            // Looked up by id, so a plush collection, which the figure list leaves out, is found too
            CollectionRegistry.getCollection(this.onlyCollectionId).ifPresent(collections::add);
        } else {
            for (FigureCollection collection : CollectionRegistry.getAllCollections()) {
                // Filter out the default collection, as the Cheats tab does
                if (!collection.getId().equals("default")) {
                    collections.add(collection);
                }
            }
        }

        super.init();

        // Start with the admin's own progress
        if (this.viewed == null && this.minecraft.player != null) {
            this.viewed = this.minecraft.player.getUUID();
        }
        if (this.viewed != null) {
            view(this.viewed);
        }
    }

    @Override
    protected void initDialog() {
        int innerLeft = dialogX + PADDING;
        int innerWidth = dialogWidth - 2 * PADDING;
        int pickerY = bodyTop() + PADDING;
        this.addRenderableWidget(new StyledButton(innerLeft, pickerY, PICKER_HEIGHT, PICKER_HEIGHT,
                Component.literal("<"), button -> cycleTarget(-1)).lipHeight(2));
        this.addRenderableWidget(new StyledButton(innerLeft + innerWidth - PICKER_HEIGHT, pickerY, PICKER_HEIGHT,
                PICKER_HEIGHT, Component.literal(">"), button -> cycleTarget(1)).lipHeight(2));

        // Rows wrap into as many columns as the height requires
        int footerY = bodyBottom() - PADDING - FOOTER_HEIGHT;
        this.gridTop = pickerY + PICKER_HEIGHT + PICKER_GAP;
        this.rowsPerColumn = Math.max(1, (footerY - FOOTER_GAP - gridTop) / ROW_HEIGHT);
        int columns = Math.max(1, (collections.size() + rowsPerColumn - 1) / rowsPerColumn);
        this.columnWidth = Math.min(MAX_COLUMN_WIDTH, (innerWidth - (columns - 1) * COLUMN_GAP) / columns);
        this.gridX = dialogX + (dialogWidth - columns * columnWidth - (columns - 1) * COLUMN_GAP) / 2;

        int buttonOffset = (ROW_HEIGHT - 1 - ROW_BUTTON_HEIGHT) / 2;
        for (int i = 0; i < collections.size(); i++) {
            String collectionId = collections.get(i).getId();
            int buttonsX = rowX(i) + columnWidth - BUTTONS_WIDTH;
            this.addRenderableWidget(new StyledButton(buttonsX, rowY(i) + buttonOffset, LOCK_WIDTH, ROW_BUTTON_HEIGHT,
                    Component.literal("Lock"), button -> lock((StyledButton) button, collectionId)).lipHeight(2));
            this.addRenderableWidget(new StyledButton(buttonsX + LOCK_WIDTH + 4, rowY(i) + buttonOffset, UNLOCK_WIDTH,
                    ROW_BUTTON_HEIGHT, Component.literal("Unlock"), button -> {
                disarm();
                edit(AdminProgressionPacket.UNLOCK, collectionId);
            }).lipHeight(2));
        }

        int doneWidth = Math.min(120, innerWidth);
        this.addRenderableWidget(DialogFrame.button(dialogX + (dialogWidth - doneWidth) / 2, footerY, doneWidth,
                FOOTER_HEIGHT, Component.literal("Done"), button -> this.onClose()).tone(StyledButton.Tone.PRIMARY));
    }

    private int rowX(int index) {
        return gridX + (index / rowsPerColumn) * (columnWidth + COLUMN_GAP);
    }

    private int rowY(int index) {
        return gridTop + (index % rowsPerColumn) * ROW_HEIGHT;
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

    private void lock(StyledButton button, String collectionId) {
        if (!canEdit()) {
            return;
        }
        if (button != this.armedLock) {
            // First click only arms this row; arming another row disarms the previous one
            disarm();
            this.armedLock = button;
            this.armedAt = System.currentTimeMillis();
            button.setMessage(Component.literal("Sure?"));
            button.tone(StyledButton.Tone.DANGER);
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
            this.armedLock.tone(StyledButton.Tone.NEUTRAL);
            this.armedLock = null;
        }
    }

    @Override
    protected void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
        // The player's name between the picker's arrows
        int pickerY = bodyTop() + PADDING;
        int pickerRoom = dialogWidth - 2 * PADDING - 2 * PICKER_HEIGHT - 12;
        UiText.drawCentered(graphics, this.font, UiText.fit(this.font, header, pickerRoom), dialogX + dialogWidth / 2,
                pickerY + (PICKER_HEIGHT - 8) / 2, selected < 0 ? Surfaces.TEXT_DIM : Surfaces.TEXT);

        if (collections.isEmpty()) {
            UiText.drawCentered(graphics, this.font, "No collections to show", dialogX + dialogWidth / 2,
                    gridTop + (ROW_HEIGHT - 9) / 2, Surfaces.TEXT_MUTED);
            return;
        }

        int nameWidth = columnWidth - BUTTONS_WIDTH - COUNT_WIDTH - 8;
        for (int i = 0; i < collections.size(); i++) {
            FigureCollection collection = collections.get(i);
            int rowX = rowX(i);
            int rowY = rowY(i);
            // A hairline under each row, as the settings' rows
            graphics.fill(rowX, rowY + ROW_HEIGHT - 1, rowX + columnWidth, rowY + ROW_HEIGHT, Surfaces.ROW_LINE);
            int textY = rowY + (ROW_HEIGHT - 1 - 8) / 2;
            UiText.drawFitted(graphics, this.font, collection.getName(), rowX + 4, textY, nameWidth, Surfaces.TEXT);
            if (selected < 0) {
                continue;
            }
            int discovered = 0;
            // Same count as the collection screen: figures disabled in the roster are left out
            for (FigureDefinition figure : collection.getEnabledFigures()) {
                if (snapshot.getDiscovered().contains(collection.getId() + ":" + figure.getId())) {
                    discovered++;
                }
            }
            UiText.draw(graphics, this.font, discovered + "/" + collection.getEnabledFigures().size(),
                    rowX + columnWidth - BUTTONS_WIDTH - COUNT_WIDTH, textY, Surfaces.TEXT_DIM);
        }
    }
}
