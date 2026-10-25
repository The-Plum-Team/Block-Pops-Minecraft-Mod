package com.theplumteam.client.gui;

import com.theplumteam.client.ClientServerSettings;
import com.theplumteam.client.ClientHiddenCollections;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.creator.CreatorHomeScreen;
import com.theplumteam.client.gui.creator.ui.UiQuotaMeter;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.DialogScreen;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Stamp;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.StyledSlider;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.style.ToggleRow;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.widget.TabButton;
import com.theplumteam.client.remote.ClientCustomCollections;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.network.UnlockCollectionPacket;
import com.theplumteam.network.CustomCollectionRequestPacket;
import com.theplumteam.network.ReloadTokensPacket;
import com.theplumteam.network.SetCollectionHiddenPacket;
import com.theplumteam.network.UpdateGuaranteedResetHourPacket;
import com.theplumteam.worldcontent.WorldContentIds;
import dev.architectury.platform.Platform;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The machine's settings, as Quick Skin's settings dialog: a {@link DialogFrame} with the slate
 * header and the gear, the tabs in a sidebar on the left and the active tab's content beside them
 * as rows (a label on the left, its control on the right and a hairline under it), with the tab's
 * action in a footer under the rows. Content taller than the dialog scrolls a row at a time.
 */
public class SettingsScreen extends DialogScreen {
    // The sidebar and the content's face, in Quick Skin's settings colours
    private static final int SIDEBAR_BG = 0xFF3A3B3E;
    private static final int SIDEBAR_EDGE = 0xFF55565B;
    private static final int CONTENT_FACE = Surfaces.PANEL_FACE;
    private static final int SIDEBAR_MIN_WIDTH = 88;
    private static final int SIDEBAR_MAX_WIDTH = 112;
    private static final int INSET = 4;
    private static final int TAB_HEIGHT = 22;
    private static final int TAB_SPACING = 4;

    // The rows: room between the content and the dialog's edges, and the rows' sizes
    private static final int CONTENT_MARGIN = 14;
    private static final int ROW_OVERHANG = 6;
    private static final int WIDGET_HEIGHT = 20;
    private static final int WIDGET_ROW_HEIGHT = 28;
    private static final int GRID_ROW_HEIGHT = 24;
    private static final int HEADING_HEIGHT = 18;
    private static final int LINE_HEIGHT = 10;
    private static final int ROW_TEXT = 0xFFE0E0E0;
    private static final int STATUS_COLOR = 0xFFFFFF80;
    private static final int ACTION_WIDTH = 110;
    private static final int SCROLL_BAR = 0xFF6E6F73;

    // How long a collection toggle shows the player's choice while the server answers
    private static final int VISIBILITY_ANSWER_TICKS = 40;

    // The red of a rubber stamp's ink
    private static final int STAMP_INK = 0xFFC62828;

    // Tab system
    private enum Tab {
        SERVER("blockpops.settings.tab.server", PixelIcon.SERVER),
        DEVELOP("blockpops.settings.tab.develop", PixelIcon.WRENCH),
        CHEATS("blockpops.settings.tab.cheats", PixelIcon.KEY),
        COLLECTIONS("blockpops.settings.tab.collections", PixelIcon.LIST),
        CUSTOM("blockpops.settings.tab.custom", PixelIcon.STAR),
        CREATOR("blockpops.settings.tab.creator", PixelIcon.PALETTE);

        private final String nameKey;
        private final String[] icon;

        Tab(String nameKey, String[] icon) {
            this.nameKey = nameKey;
            this.icon = icon;
        }

        public String getDisplayName() {
            return Component.translatable(nameKey).getString();
        }
    }

    private Tab activeTab = Tab.SERVER;
    private final Map<Tab, TabButton> tabButtons = new EnumMap<>(Tab.class);
    // Each tab's rows, which own its widgets; only the active tab's widgets are on the screen
    private final Map<Tab, List<Row>> rows = new EnumMap<>(Tab.class);
    // The first row shown, and the last one it can be
    private int scroll;
    private int maxScroll;

    // Where the sidebar ends and the rows go
    private int sidebarWidth;
    private int contentX;
    private int contentRight;
    private int contentTop;

    // Show/hide toggle of each collection (Collections tab), and the requests still unanswered,
    // with the tick they were sent at, so a toggle keeps the player's choice until the answer
    private final Map<FigureCollection, ToggleRow> collectionToggles = new LinkedHashMap<>();
    private final Map<String, Integer> pendingVisibility = new HashMap<>();
    private int ticks;

    // Custom tab: the access code field and the server's last answer. The code survives a
    // rebuild of the screen.
    private EditBox customCodeField;
    private String pendingCustomCode = "";
    private Component customStatus;
    private int shownCustomRevision;

    // Creator tab: whether it was shown, to rebuild only when it comes or goes; its text
    // reads the World collections as it draws
    private boolean shownCreator;

    // Context-sensitive button in the footer (Change time / Reset Colors / ...)
    private StyledButton actionButton;
    private ToggleRow colorTransitionToggle;

    // Server settings
    private HourSlider resetHourSlider;
    private int loadedServerHourLocal; // The hour currently saved/loaded
    private int pendingServerHourLocal; // The hour currently selected on slider
    private int shownServerRevision; // The server answer the slider currently shows
    private boolean serverSettingsRequested;

    // Star color sliders (Develop tab)
    private ColorSlider starRedSlider;
    private ColorSlider starGreenSlider;
    private ColorSlider starBlueSlider;
    private OpacitySlider starOpacitySlider;

    // Background color sliders (Develop tab)
    private ColorSlider bgRedSlider;
    private ColorSlider bgGreenSlider;
    private ColorSlider bgBlueSlider;

    // Panel opacity slider (Develop tab)
    private OpacitySlider panelOpacitySlider;

    // The background colour the Develop tab turned black, given back when the tab or the
    // screen closes; null while the tab is not open
    private float[] backgroundBeforeDevelop;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("blockpops.settings.title"), parent);
    }

    @Override
    protected DialogFrame.Accent accent() {
        return DialogFrame.Accent.SETTINGS;
    }

    @Override
    protected String[] icon() {
        return PixelIcon.GEAR;
    }

    @Override
    protected int preferredWidth() {
        return Math.min(480, this.width - 40);
    }

    @Override
    protected int preferredHeight() {
        return Math.min(280, this.height - 20);
    }

    @Override
    protected void initDialog() {
        // Ask the server for its settings once per opened screen (init also runs on resize)
        if (!serverSettingsRequested) {
            serverSettingsRequested = true;
            ClientServerSettings.request();
        }

        // Clear widget lists to prevent duplication on resize
        rows.clear();
        tabButtons.clear();
        collectionToggles.clear();
        shownCustomRevision = ClientCustomCollections.revision();
        shownCreator = canOpenCreator();
        // A tab goes away when the player may no longer use it
        if (!isShown(activeTab)) {
            activeTab = Tab.SERVER;
        }

        sidebarWidth = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, dialogWidth / 4));
        contentX = dialogX + sidebarWidth + CONTENT_MARGIN;
        contentRight = dialogX + dialogWidth - CONTENT_MARGIN;
        contentTop = bodyTop() + 6;

        // The tabs in the sidebar, one under another, each shown only to who may use it
        int tabX = dialogX + 2 + INSET;
        int tabWidth = sidebarWidth - 2 - 2 * INSET;
        int tabY = bodyTop() + INSET + 2;
        for (Tab tab : Tab.values()) {
            if (!isShown(tab)) {
                continue;
            }
            TabButton button = new TabButton(tabX, tabY, tabWidth, TAB_HEIGHT,
                    Component.literal(tab.getDisplayName()), activeTab == tab, btn -> selectTab(tab))
                    .setIcon(tab.icon);
            tabButtons.put(tab, button);
            this.addRenderableWidget(button);
            tabY += TAB_HEIGHT + TAB_SPACING;
        }

        // The action button, at the right of the footer - text and behavior depend on tab
        this.actionButton = DialogFrame.button(contentRight - ACTION_WIDTH, bodyBottom() - 6 - WIDGET_HEIGHT,
                ACTION_WIDTH, WIDGET_HEIGHT, Component.translatable("blockpops.settings.action"), button -> handleActionClick());
        this.addRenderableWidget(this.actionButton);

        // Create settings for all tabs
        createServerSettings();

        if (isDevelopmentMode()) {
            createDevelopSettings();
        }

        if (canAccessCheats()) {
            createCheatsSettings();
        }

        if (canManageCollections()) {
            createCollectionsSettings();
        }

        if (canManageCustomCollections()) {
            createCustomSettings();
        }

        if (canOpenCreator()) {
            createCreatorSettings();
        }

        // Show initial tab and update action button state
        switchTab(activeTab);
    }

    /**
     * Whether a tab is shown to this player
     */
    private boolean isShown(Tab tab) {
        return switch (tab) {
            case SERVER -> true;
            // Develop: only in development mode
            case DEVELOP -> isDevelopmentMode();
            // Cheats: for admins and in development mode
            case CHEATS -> canAccessCheats();
            // Collections: for admins, on a server that syncs its hidden collections
            case COLLECTIONS -> canManageCollections();
            // Custom: for admins, on a server that offers Custom collections
            case CUSTOM -> canManageCustomCollections();
            // Creator: for operators, on a server that syncs its World collections
            case CREATOR -> canOpenCreator();
        };
    }

    /**
     * Handles the click of the context-sensitive action button
     */
    private void handleActionClick() {
        if (activeTab == Tab.SERVER) {
            // "Change Time" logic
            int utcValue = convertLocalToUtc(pendingServerHourLocal);

            // Send packet to server using cross-platform networking
            new UpdateGuaranteedResetHourPacket(utcValue).sendToServer();

            // Ask for the settings right behind it: the answer carries the hour that is
            // really in effect, whether or not the server accepted the change
            ClientServerSettings.request();

            // Update loaded value to current and refresh button state
            this.loadedServerHourLocal = pendingServerHourLocal;
            updateActionButtonState();

        } else if (activeTab == Tab.DEVELOP) {
            // "Reset Colors" logic
            ClientConfig config = ClientConfig.getInstance();
            config.resetColors();
            // Reset star color sliders
            this.starRedSlider.setValue(1.0);
            this.starGreenSlider.setValue(1.0);
            this.starBlueSlider.setValue(1.0);
            this.starOpacitySlider.setValue(0.20);
            // Reset background color sliders
            this.bgRedSlider.setValue(0.0);
            this.bgGreenSlider.setValue(0.0);
            this.bgBlueSlider.setValue(0.0);
            // Reset panel opacity slider
            this.panelOpacitySlider.setValue(0.90);
            // Reset color transition toggle
            this.colorTransitionToggle.setValue(config.enableColorTransition);
        } else if (activeTab == Tab.CHEATS) {
            // "Progression" opens the per-player collection editor
            this.minecraft.setScreen(new AdminProgressionScreen(this));
        } else if (activeTab == Tab.CUSTOM) {
            // "Add": the server looks the code up and answers with a status
            new CustomCollectionRequestPacket(true, pendingCustomCode.trim()).sendToServer();
            customStatus = Component.translatable("blockpops.custom_collections.looking_up");
        } else if (activeTab == Tab.CREATOR) {
            // The creator's overview, which returns here when it closes
            openCreator(this, machinePosOf(this.parent));
        }
    }

    /**
     * Updates the text and active state of the action button based on current tab
     */
    private void updateActionButtonState() {
        StyledButton.Tone tone = StyledButton.Tone.PRIMARY;
        if (activeTab == Tab.SERVER) {
            this.actionButton.setMessage(Component.translatable("blockpops.settings.change_time"));
            // Locked until slider is moved to a different value
            this.actionButton.active = canChangeResetHour() && (pendingServerHourLocal != loadedServerHourLocal);
            this.actionButton.visible = true;
        } else if (activeTab == Tab.DEVELOP) {
            this.actionButton.setMessage(Component.literal("Reset Colors"));
            this.actionButton.active = true;
            this.actionButton.visible = true;
            tone = StyledButton.Tone.NEUTRAL;
        } else if (activeTab == Tab.CHEATS) {
            // Cheats tab: open the per-player progression editor
            this.actionButton.setMessage(Component.translatable("blockpops.settings.progression"));
            this.actionButton.active = true;
            this.actionButton.visible = true;
            tone = StyledButton.Tone.NEUTRAL;
        } else if (activeTab == Tab.CUSTOM) {
            this.actionButton.setMessage(Component.translatable("blockpops.settings.add"));
            this.actionButton.active = !pendingCustomCode.isBlank();
            this.actionButton.visible = true;
        } else if (activeTab == Tab.CREATOR) {
            this.actionButton.setMessage(Component.translatable("blockpops.settings.open_creator"));
            this.actionButton.active = true;
            this.actionButton.visible = true;
        } else {
            // No action on the other tabs (Collections)
            this.actionButton.active = false;
            this.actionButton.visible = false;
        }
        this.actionButton.tone(tone);
        // The footer comes and goes with the action, and the rows take its room
        layoutRows();
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        // Follow the server's answers and permission changes while the screen is open
        refreshServerSettings();
        // A new Custom list, or a server that starts or stops offering them, rebuilds the tabs
        if (shownCustomRevision != ClientCustomCollections.revision()) {
            this.rebuildWidgets();
        }
        // The Creator tab comes and goes with operator status and a server that syncs World
        // collections; a sync alone changes nothing built here, and rebuilding would reset
        // the unsaved reset-hour slider
        if (shownCreator != canOpenCreator()) {
            this.rebuildWidgets();
        }
        refreshCollectionToggles();
        // Text such as the Custom status may have changed its height
        layoutRows();
    }

    @Override
    protected void renderBody(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The sidebar behind the tabs and the darker face of the content, from under the header
        // to the lip
        int top = bodyTop();
        int bottom = bodyBottom();
        graphics.fill(dialogX + 2, top, dialogX + sidebarWidth, bottom, SIDEBAR_BG);
        graphics.fill(dialogX + sidebarWidth, top, dialogX + sidebarWidth + 1, bottom, SIDEBAR_EDGE);
        graphics.fill(dialogX + sidebarWidth + 1, top, dialogX + dialogWidth - 2, bottom, CONTENT_FACE);

        for (Row row : rowsOf(activeTab)) {
            if (!row.shown) {
                continue;
            }
            if (row.line) {
                int lineY = row.top + row.height - 1;
                graphics.fill(contentX - ROW_OVERHANG, lineY, contentRight + ROW_OVERHANG, lineY + 1, Surfaces.ROW_LINE);
            }
            if (row.swatch != null) {
                // A colour preview at the right end of a heading
                int size = 10;
                int x = contentRight - size;
                int y = row.top + (row.height - size) / 2;
                graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, Surfaces.BORDER);
                graphics.fill(x, y, x + size, y + size, row.swatch.getAsInt());
            }
        }

        // A scroll bar in the right margin while the rows do not all fit
        if (maxScroll > 0) {
            int trackTop = contentTop;
            int trackHeight = contentBottom() - trackTop;
            List<Row> tabRows = rowsOf(activeTab);
            int barHeight = Math.max(12, trackHeight * (tabRows.size() - maxScroll) / tabRows.size());
            int barTop = trackTop + (trackHeight - barHeight) * scroll / maxScroll;
            int barX = contentRight + ROW_OVERHANG + 2;
            graphics.fill(barX, barTop, barX + 2, barTop + barHeight, SCROLL_BAR);
        }
    }

    @Override
    protected void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The rows' labels and text are drawn in the same pass as the widgets, after them:
        // before 1.21.6 text drawn under the widgets' fills could end up behind them
        int room = contentRight - contentX;
        for (Row row : rowsOf(activeTab)) {
            if (!row.shown) {
                continue;
            }
            if (row.heading != null) {
                UiText.draw(graphics, this.font, row.heading, contentX, row.top + (row.height - 8) / 2 + 1, Surfaces.TEXT_DIM);
            }
            for (int i = 0; i < row.lines.size(); i++) {
                UiText.drawFitted(graphics, this.font, row.lines.get(i), contentX, row.top + 5 + i * LINE_HEIGHT,
                        room, row.color);
            }
            if (row.label != null && !row.widgets.isEmpty()) {
                // The label in the room left of the row's first control, centred on it
                AbstractWidget control = row.widgets.get(0);
                int textY = control.getY() + (control.getHeight() - 8) / 2;
                UiText.drawFitted(graphics, this.font, row.label.get(), contentX, textY,
                        control.getX() - 8 - contentX, ROW_TEXT);
            }
        }
        // Custom collections are not finished: a stamp across the tab says so
        if (activeTab == Tab.CUSTOM) {
            Stamp.draw(graphics, new String[] {Component.translatable("blockpops.settings.stamp.in_development").getString(),
                            Component.translatable("blockpops.settings.stamp.next_update").getString()},
                    new float[] {3, 1},
                    (contentX + contentRight) / 2.0f, (contentTop + contentBottom()) / 2.0f, STAMP_INK, 12);
        }
    }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        // Click outside the dialog - close the modal
        if (isOutsideDialog(bpEvent.x(), bpEvent.y())) {
            this.onClose();
            return true;
        }
        // Click inside the dialog - handle normally
        return super.mouseClicked(bpEvent, bpDouble);
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click outside the dialog - close the modal
        if (isOutsideDialog(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        // Click inside the dialog - handle normally
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //? }

    private boolean isOutsideDialog(double mouseX, double mouseY) {
        return mouseX < dialogX || mouseX > dialogX + dialogWidth
                || mouseY < dialogY || mouseY > dialogY + dialogHeight;
    }

    @Override
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
    //? }
        // The rows scroll one at a time while the mouse is over them
        if (maxScroll > 0 && amount != 0
                && mouseX >= contentX - ROW_OVERHANG && mouseX < dialogX + dialogWidth
                && mouseY >= contentTop && mouseY < contentBottom()) {
            scroll += amount > 0 ? -1 : 1;
            layoutRows();
            return true;
        }
        //? if >=1.21 {
        /*return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        *///? } else {
        return super.mouseScrolled(mouseX, mouseY, amount);
        //? }
    }

    @Override
    public void onClose() {
        ClientCustomCollections.setStatusListener(null);
        // Return to parent screen
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        // Whatever replaces this screen, the background leaves the Develop tab's black behind
        restoreBackground();
    }

    /**
     * The Develop tab tunes the background colour from black. The colour it replaces is kept,
     * once, so a rebuild of the screen with the tab still open does not keep the black instead.
     */
    private void blackenBackgroundForDevelop() {
        if (backgroundBeforeDevelop != null) {
            return;
        }
        ClientConfig config = ClientConfig.getInstance();
        backgroundBeforeDevelop = new float[] {config.backgroundColorR, config.backgroundColorG, config.backgroundColorB};
        config.backgroundColorR = 0.0f;
        config.backgroundColorG = 0.0f;
        config.backgroundColorB = 0.0f;
        this.bgRedSlider.setValue(0.0);
        this.bgGreenSlider.setValue(0.0);
        this.bgBlueSlider.setValue(0.0);
    }

    private void restoreBackground() {
        if (backgroundBeforeDevelop == null) {
            return;
        }
        ClientConfig config = ClientConfig.getInstance();
        config.backgroundColorR = backgroundBeforeDevelop[0];
        config.backgroundColorG = backgroundBeforeDevelop[1];
        config.backgroundColorB = backgroundBeforeDevelop[2];
        backgroundBeforeDevelop = null;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    /**
     * Check if running in development mode using Architectury Platform API
     */
    private static boolean isDevelopmentMode() {
        return Platform.isDevelopmentEnvironment();
    }

    /**
     * Check if the current player can access cheats.
     * Returns true if in development mode OR if player is an admin (permission level 2+)
     */
    private boolean canAccessCheats() {
        return isDevelopmentMode() || isOperator();
    }

    /**
     * Check if the current player has admin permissions (level 2, same as /blockpops getbox command)
     */
    private boolean isOperator() {
        if (this.minecraft != null && this.minecraft.player != null) {
            return com.theplumteam.util.ServerLevels.hasCommandLevel(this.minecraft.player, 2);
        }
        return false;
    }

    /**
     * Check if the current player can change the reset hour.
     * Only admins can, and only once the server has reported the hour it uses.
     */
    private boolean canChangeResetHour() {
        return isOperator() && ClientServerSettings.getGuaranteedResetHour() != null;
    }

    /**
     * Show the hour the server last reported and lock the controls the player cannot use
     */
    private void refreshServerSettings() {
        Integer utcHour = ClientServerSettings.getGuaranteedResetHour();
        if (utcHour == null) {
            this.resetHourSlider.showUnknown();
        } else if (ClientServerSettings.getRevision() != this.shownServerRevision) {
            // A new answer replaces whatever the slider showed
            this.shownServerRevision = ClientServerSettings.getRevision();
            this.loadedServerHourLocal = convertUtcToLocal(utcHour);
            this.pendingServerHourLocal = this.loadedServerHourLocal;
            this.resetHourSlider.setValue(this.loadedServerHourLocal);
        }
        this.resetHourSlider.active = canChangeResetHour();
        updateActionButtonState();
    }

    /**
     * Check if the current player can hide collections for the whole server.
     * Same access as cheats, and the server has to be one that syncs its hidden collections.
     */
    private boolean canManageCollections() {
        return canAccessCheats() && ClientHiddenCollections.isSynced();
    }

    /**
     * Check if the current player can enable Custom collections for this world.
     * Same access as cheats, and the server has to be one that offers Custom collections.
     */
    private boolean canManageCustomCollections() {
        return canAccessCheats() && ClientCustomCollections.isSynced();
    }

    /**
     * Whether the current player can make World collections for this world. Every way into
     * the creator asks this one check, and its screens close once it turns false.
     * Operators only: unlike the Cheats tab, development mode alone is not enough, because
     * the server refuses every change from a player below level 2. The server also has to
     * be one that syncs its World collections. The creator opens only in development mode
     * while it is still being worked on; the commands stay available.
     */
    public static boolean canOpenCreator() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return isDevelopmentMode() && minecraft.player != null
                && com.theplumteam.util.ServerLevels.hasCommandLevel(minecraft.player, 2)
                && ClientWorldCollections.isSynced();
    }

    /**
     * Opens the creator's overview of this world's World collections over a screen it goes back
     * to when it closes, if {@link #canOpenCreator()} allows it.
     *
     * @param parent     the screen to go back to, or null to go back to the game
     * @param machinePos the claw or gacha machine the creator was opened from, which its cards
     *                   offer to put a collection in; null when there is none
     */
    public static void openCreator(@Nullable Screen parent, @Nullable BlockPos machinePos) {
        if (canOpenCreator()) {
            net.minecraft.client.Minecraft.getInstance().setScreen(new CreatorHomeScreen(parent, machinePos));
        }
    }

    // The machine whose screen opened these settings, if they were opened from one
    @Nullable
    private static BlockPos machinePosOf(@Nullable Screen screen) {
        return screen instanceof CollectionSelectionScreen machine ? machine.machinePos() : null;
    }

    /**
     * Create the Custom tab: the access code field and a Remove button per enabled collection
     */
    private void createCustomSettings() {
        ClientCustomCollections.setStatusListener(status -> customStatus = ClientCustomCollections.message(status));
        List<Row> tabRows = rowsFor(Tab.CUSTOM);
        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.custom.help").getString(),
                Surfaces.TEXT_DIM));

        customCodeField = new EditBox(this.font, contentX, 0, contentRight - contentX, WIDGET_HEIGHT,
                Component.translatable("blockpops.settings.custom.code"));
        customCodeField.setMaxLength(64);
        customCodeField.setHint(Component.translatable("blockpops.settings.custom.code"));
        customCodeField.setValue(pendingCustomCode);
        customCodeField.setResponder(value -> {
            pendingCustomCode = value;
            updateActionButtonState();
        });
        tabRows.add(widgetRow(null, customCodeField));
        // The server's answer to the last Add or Remove, when there is one
        tabRows.add(paragraph(() -> customStatus == null ? "" : customStatus.getString(), STATUS_COLOR));

        List<FigureCollection> collections = ClientCustomCollections.collections();
        if (collections.isEmpty()) {
            tabRows.add(paragraph(() -> Component.translatable("blockpops.custom_collections.none").getString(),
                    Surfaces.TEXT_DIM));
        }
        int removeWidth = 70;
        for (FigureCollection collection : collections) {
            StyledButton remove = new StyledButton(contentRight - removeWidth, 0, removeWidth, WIDGET_HEIGHT,
                    Component.translatable("blockpops.settings.custom.remove"),
                    button -> new CustomCollectionRequestPacket(false, collection.getId()).sendToServer())
                    .tone(StyledButton.Tone.DANGER)
                    .icon(PixelIcon.BIN);
            tabRows.add(widgetRow(collection::getName, remove));
        }
    }

    /**
     * Create the Creator tab: what the creator makes and what this world stores
     */
    private void createCreatorSettings() {
        List<Row> tabRows = rowsFor(Tab.CREATOR);
        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.creator.help").getString(),
                Surfaces.TEXT_DIM));
        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.creator.stored",
                ClientWorldCollections.records().size(),
                UiQuotaMeter.text(ClientWorldCollections.quotaUsedKiB(), ClientWorldCollections.quotaMaxKiB()))
                .getString(), ROW_TEXT));
    }

    /**
     * Create server settings widgets
     */
    private void createServerSettings() {
        List<Row> tabRows = rowsFor(Tab.SERVER);

        // Get local timezone
        ZoneId localZone = ZoneId.systemDefault();
        String timezoneName = localZone.getDisplayName(TextStyle.SHORT, Locale.getDefault());

        // The hour is the server's: refreshServerSettings() below fills in its last answer
        int localHour = 0;
        this.shownServerRevision = -1;

        // Initialize tracking variables
        this.loadedServerHourLocal = localHour;
        this.pendingServerHourLocal = localHour;

        // Reset hour slider (0-23 in local time), across the row
        this.resetHourSlider = new HourSlider(
                contentX, 0,
                contentRight - contentX, WIDGET_HEIGHT,
                Component.translatable("blockpops.settings.reset_hour", timezoneName),
                localHour,
                localValue -> {
                    // Update pending value
                    this.pendingServerHourLocal = localValue;
                    // Update button state (check if changed)
                    updateActionButtonState();
                }
        );
        tabRows.add(widgetRow(null, this.resetHourSlider));
        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.reset_hour.help").getString(),
                Surfaces.TEXT_DIM));
        tabRows.add(paragraph(() -> Component.translatable(!isOperator() ? "blockpops.settings.reset_hour.operators_only"
                : canChangeResetHour() ? "blockpops.settings.reset_hour.choose"
                : "blockpops.settings.reset_hour.unknown").getString(), Surfaces.TEXT_DIM));
        refreshServerSettings();

        // World Players roster (admins only, like the Cheats tab; the server checks again)
        if (canAccessCheats()) {
            int manageWidth = 80;
            StyledButton manage = new StyledButton(contentRight - manageWidth, 0, manageWidth, WIDGET_HEIGHT,
                    Component.translatable("blockpops.settings.world_players.manage"),
                    button -> this.minecraft.setScreen(new WorldPlayerRosterScreen(this)))
                    .icon(PixelIcon.PLAYER);
            tabRows.add(widgetRow(() -> "World Players", manage));
        }
    }

    /**
     * Convert UTC hour to local timezone hour; the machine screen explains the reset with it too
     */
    static int convertUtcToLocal(int utcHour) {
        ZonedDateTime utcTime = ZonedDateTime.now(ZoneId.of("UTC"))
                .withHour(utcHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        ZonedDateTime localTime = utcTime.withZoneSameInstant(ZoneId.systemDefault());
        return localTime.getHour();
    }

    /**
     * Convert local timezone hour to UTC hour
     */
    private static int convertLocalToUtc(int localHour) {
        ZonedDateTime localTime = ZonedDateTime.now(ZoneId.systemDefault())
                .withHour(localHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        ZonedDateTime utcTime = localTime.withZoneSameInstant(ZoneId.of("UTC"));
        return utcTime.getHour();
    }

    /**
     * Create development settings widgets (all current background color settings)
     */
    private void createDevelopSettings() {
        ClientConfig config = ClientConfig.getInstance();
        List<Row> tabRows = rowsFor(Tab.DEVELOP);

        // Two sliders to a row
        int leftWidth = halfWidth();
        int rightX = contentX + leftWidth + 6;
        int rightWidth = contentRight - rightX;

        // === STAR COLOR ===
        tabRows.add(heading("Star Color", () -> opaque(config.starColorR, config.starColorG, config.starColorB)));
        this.starRedSlider = new ColorSlider(contentX, 0, leftWidth, WIDGET_HEIGHT,
                Component.literal("Red: "), config.starColorR, value -> config.starColorR = value.floatValue());
        this.starGreenSlider = new ColorSlider(rightX, 0, rightWidth, WIDGET_HEIGHT,
                Component.literal("Green: "), config.starColorG, value -> config.starColorG = value.floatValue());
        tabRows.add(gridRow(this.starRedSlider, this.starGreenSlider));
        this.starBlueSlider = new ColorSlider(contentX, 0, leftWidth, WIDGET_HEIGHT,
                Component.literal("Blue: "), config.starColorB, value -> config.starColorB = value.floatValue());
        this.starOpacitySlider = new OpacitySlider(rightX, 0, rightWidth, WIDGET_HEIGHT,
                Component.literal("Opacity: "), config.starOpacity, value -> config.starOpacity = value.floatValue());
        tabRows.add(gridRow(this.starBlueSlider, this.starOpacitySlider));

        // === BACKGROUND COLOR ===
        tabRows.add(heading("Background Color",
                () -> opaque(config.backgroundColorR, config.backgroundColorG, config.backgroundColorB)));
        this.bgRedSlider = new ColorSlider(contentX, 0, leftWidth, WIDGET_HEIGHT,
                Component.literal("Red: "), config.backgroundColorR, value -> config.backgroundColorR = value.floatValue());
        this.bgGreenSlider = new ColorSlider(rightX, 0, rightWidth, WIDGET_HEIGHT,
                Component.literal("Green: "), config.backgroundColorG, value -> config.backgroundColorG = value.floatValue());
        tabRows.add(gridRow(this.bgRedSlider, this.bgGreenSlider));
        this.bgBlueSlider = new ColorSlider(contentX, 0, leftWidth, WIDGET_HEIGHT,
                Component.literal("Blue: "), config.backgroundColorB, value -> config.backgroundColorB = value.floatValue());
        tabRows.add(gridRow(this.bgBlueSlider));

        // === PANEL & ANIMATION ===
        tabRows.add(heading("Panel & Animation", null));
        this.panelOpacitySlider = new OpacitySlider(contentX, 0, contentRight - contentX, WIDGET_HEIGHT,
                Component.literal("Panel Opacity: "), config.panelOpacity, value -> config.panelOpacity = value.floatValue());
        tabRows.add(gridRow(this.panelOpacitySlider));
        this.colorTransitionToggle = new ToggleRow(contentX - ROW_OVERHANG, 0, contentRight - contentX + 2 * ROW_OVERHANG,
                Component.literal("Color transition"), config.enableColorTransition,
                value -> config.enableColorTransition = value);
        tabRows.add(toggleRow(this.colorTransitionToggle));
    }

    /**
     * Create cheats settings widgets (token reloads and collection unlock buttons)
     */
    private void createCheatsSettings() {
        List<Row> tabRows = rowsFor(Tab.CHEATS);
        int leftWidth = halfWidth();
        int rightX = contentX + leftWidth + 6;
        int rightWidth = contentRight - rightX;

        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.cheats.help").getString(),
                Surfaces.TEXT_DIM));

        // Reload Regular Tokens button
        StyledButton reloadRegularButton = new StyledButton(contentX, 0, leftWidth, WIDGET_HEIGHT,
                Component.translatable("blockpops.settings.cheats.reload_regular"),
                button -> {
                    // Send packet to server to reload regular tokens
                    ReloadTokensPacket packet = new ReloadTokensPacket(true, false);
                    packet.sendToServer();

                    // Provide visual feedback, re-enabled after a short delay
                    showBusy(button, Component.translatable("blockpops.settings.cheats.reloading").getString(),
                            Component.translatable("blockpops.settings.cheats.reload_regular").getString(), 500);
                });

        // Reload Guaranteed Token button
        StyledButton reloadGuaranteedButton = new StyledButton(rightX, 0, rightWidth, WIDGET_HEIGHT,
                Component.translatable("blockpops.settings.cheats.reload_guaranteed"),
                button -> {
                    // Send packet to server to reload guaranteed token
                    ReloadTokensPacket packet = new ReloadTokensPacket(false, true);
                    packet.sendToServer();

                    // Provide visual feedback, re-enabled after a short delay
                    showBusy(button, Component.translatable("blockpops.settings.cheats.reloading").getString(),
                            Component.translatable("blockpops.settings.cheats.reload_guaranteed").getString(), 500);
                });
        tabRows.add(gridRow(reloadRegularButton, reloadGuaranteedButton));

        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.cheats.unlock_help").getString(),
                Surfaces.TEXT_DIM));

        // Get all collections
        java.util.Collection<FigureCollection> collections = CollectionRegistry.getAllCollections();

        // Filter out the default collection if it exists, and World collections, which the
        // Creator tab unlocks one by one
        java.util.List<FigureCollection> filteredCollections = collections.stream()
                .filter(collection -> !collection.getId().equals("default"))
                .filter(collection -> !WorldContentIds.isWorldCollectionId(collection.getId()))
                .collect(java.util.stream.Collectors.toList());

        // Two unlock buttons to a row
        List<AbstractWidget> pair = new ArrayList<>();
        for (FigureCollection collection : filteredCollections) {
            boolean left = pair.isEmpty();
            String label = Component.translatable("blockpops.settings.cheats.unlock", collection.getName()).getString();
            StyledButton unlockButton = new StyledButton(left ? contentX : rightX, 0, left ? leftWidth : rightWidth,
                    WIDGET_HEIGHT, Component.literal(label),
                    button -> {
                        // Send packet to server to unlock this collection
                        UnlockCollectionPacket packet = new UnlockCollectionPacket(collection.getId());
                        packet.sendToServer();

                        // Provide visual feedback, re-enabled after a short delay
                        showBusy(button, Component.translatable("blockpops.settings.cheats.unlocking").getString(), label, 1000);
                    });
            pair.add(unlockButton);
            if (pair.size() == 2) {
                tabRows.add(gridRow(pair.toArray(new AbstractWidget[0])));
                pair.clear();
            }
        }
        if (!pair.isEmpty()) {
            tabRows.add(gridRow(pair.toArray(new AbstractWidget[0])));
        }
    }

    /**
     * Shows a button as busy for a moment after it sent its request, then puts its label back
     */
    private void showBusy(Button button, String busyLabel, String label, long millis) {
        button.setMessage(Component.literal(busyLabel));
        button.active = false;

        // Re-enable button after a short delay
        new Thread(() -> {
            try {
                Thread.sleep(millis);
                this.minecraft.execute(() -> {
                    button.setMessage(Component.literal(label));
                    button.active = true;
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Create collections settings widgets (one show/hide toggle per collection)
     */
    private void createCollectionsSettings() {
        List<Row> tabRows = rowsFor(Tab.COLLECTIONS);
        tabRows.add(paragraph(() -> Component.translatable("blockpops.settings.collections.help").getString(),
                Surfaces.TEXT_DIM));

        for (FigureCollection collection : CollectionRegistry.getAllCollections()) {
            // The default collection is never listed, so there is nothing to hide. A World
            // collection belongs to this world alone; Archive in the Creator hides it instead.
            if (collection.getId().equals("default") || WorldContentIds.isWorldCollectionId(collection.getId())) {
                continue;
            }

            ToggleRow toggle = new ToggleRow(contentX - ROW_OVERHANG, 0, contentRight - contentX + 2 * ROW_OVERHANG,
                    Component.literal(collection.getName()),
                    !ClientHiddenCollections.isHidden(collection.getId()),
                    shown -> {
                        // Send packet to server to hide or show this collection for every player
                        pendingVisibility.put(collection.getId(), ticks);
                        new SetCollectionHiddenPacket(collection.getId(), !shown).sendToServer();
                    });
            collectionToggles.put(collection, toggle);
            tabRows.add(toggleRow(toggle));
        }
    }

    /**
     * Shows each collection's toggle as the server's list has it. A toggle the player just
     * flipped keeps their choice until the server's list agrees, or gives up after a moment.
     */
    private void refreshCollectionToggles() {
        collectionToggles.forEach((collection, toggle) -> {
            boolean shown = !ClientHiddenCollections.isHidden(collection.getId());
            Integer sent = pendingVisibility.get(collection.getId());
            if (sent != null) {
                if (shown != toggle.value() && ticks - sent <= VISIBILITY_ANSWER_TICKS) {
                    return;
                }
                pendingVisibility.remove(collection.getId());
            }
            toggle.setValue(shown);
        });
    }

    /**
     * Opens a tab from its button, at the top of its rows
     */
    private void selectTab(Tab tab) {
        scroll = 0;
        switchTab(tab);
    }

    /**
     * Switch to a different tab
     */
    private void switchTab(Tab tab) {
        activeTab = tab;
        if (tab == Tab.DEVELOP) {
            blackenBackgroundForDevelop();
        } else {
            restoreBackground();
        }

        // Remove all setting widgets, then add the active tab's
        for (List<Row> tabRows : rows.values()) {
            for (Row row : tabRows) {
                row.widgets.forEach(this::removeWidget);
            }
        }
        for (Row row : rowsOf(tab)) {
            row.widgets.forEach(this::addRenderableWidget);
        }

        // Update tab button selected states (visual feedback)
        tabButtons.forEach((buttonTab, button) -> button.setSelected(buttonTab == tab));

        // Update action button text/state/visibility based on new tab, which lays the rows out
        updateActionButtonState();
    }

    private List<Row> rowsFor(Tab tab) {
        return rows.computeIfAbsent(tab, key -> new ArrayList<>());
    }

    private List<Row> rowsOf(Tab tab) {
        return rows.getOrDefault(tab, List.of());
    }

    /** The bottom of the rows: above the footer while the tab has an action. */
    private int contentBottom() {
        return actionButton != null && actionButton.visible ? actionButton.getY() - 6 : bodyBottom() - 6;
    }

    /** The width of the left one of two controls side by side. */
    private int halfWidth() {
        return (contentRight - contentX - 6) / 2;
    }

    /**
     * Places the active tab's rows from the first one shown, and shows only the widgets of the
     * rows that fit whole above the footer.
     */
    private void layoutRows() {
        List<Row> tabRows = rowsOf(activeTab);
        int bottom = contentBottom();
        for (Row row : tabRows) {
            measure(row);
        }

        // The scroll ends where the last rows fill the room
        int room = bottom - contentTop;
        int first = tabRows.size();
        int used = 0;
        while (first > 0 && used + tabRows.get(first - 1).height <= room) {
            used += tabRows.get(first - 1).height;
            first--;
        }
        maxScroll = Math.max(0, Math.min(first, tabRows.size() - 1));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int y = contentTop;
        for (int i = 0; i < tabRows.size(); i++) {
            Row row = tabRows.get(i);
            row.top = y;
            row.shown = i >= scroll && y + row.height <= bottom;
            if (i >= scroll) {
                y += row.height;
            }
            for (int w = 0; w < row.widgets.size(); w++) {
                AbstractWidget widget = row.widgets.get(w);
                widget.setY(row.top + row.offsets.get(w));
                widget.visible = row.shown;
            }
        }
    }

    /** Sets a row's height, wrapping its paragraph to the content's width. */
    private void measure(Row row) {
        if (row.paragraph == null) {
            return;
        }
        row.lines = UiText.wrap(this.font, row.paragraph.get(), contentRight - contentX);
        row.height = row.lines.isEmpty() ? 0 : row.lines.size() * LINE_HEIGHT + 8;
    }

    private static int opaque(float red, float green, float blue) {
        return 0xFF000000 | (int) (red * 255) << 16 | (int) (green * 255) << 8 | (int) (blue * 255);
    }

    /** A row of controls, with an optional label on its left and a hairline under it. */
    private static Row widgetRow(@Nullable Supplier<String> label, AbstractWidget... widgets) {
        Row row = new Row(WIDGET_ROW_HEIGHT);
        row.label = label;
        row.line = true;
        for (AbstractWidget widget : widgets) {
            row.add(widget, (WIDGET_ROW_HEIGHT - 1 - WIDGET_HEIGHT) / 2);
        }
        return row;
    }

    /** A row of a grid of controls, which leaves out the hairline. */
    private static Row gridRow(AbstractWidget... widgets) {
        Row row = new Row(GRID_ROW_HEIGHT);
        for (AbstractWidget widget : widgets) {
            row.add(widget, (GRID_ROW_HEIGHT - WIDGET_HEIGHT) / 2);
        }
        return row;
    }

    /** A row that is a toggle, which draws its own label and hairline. */
    private static Row toggleRow(ToggleRow toggle) {
        Row row = new Row(toggle.getHeight());
        row.add(toggle, 0);
        return row;
    }

    /** Wrapped text across the content, as tall as its lines. */
    private static Row paragraph(Supplier<String> text, int color) {
        Row row = new Row(0);
        row.paragraph = text;
        row.color = color;
        return row;
    }

    /** The title of a group of rows, with a colour preview at its right end when given one. */
    private static Row heading(String text, @Nullable IntSupplier swatch) {
        Row row = new Row(HEADING_HEIGHT);
        row.heading = text;
        row.swatch = swatch;
        return row;
    }

    /**
     * A row of a tab's content: controls with an optional label on their left, wrapped text, or a
     * heading. Its widgets keep their x; the layout gives them their y below the row's top.
     */
    private static final class Row {
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private final List<Integer> offsets = new ArrayList<>();
        @Nullable
        private Supplier<String> label;
        @Nullable
        private Supplier<String> paragraph;
        @Nullable
        private String heading;
        @Nullable
        private IntSupplier swatch;
        private int color = ROW_TEXT;
        private boolean line;

        // Laid out by layoutRows()
        private List<String> lines = List.of();
        private int height;
        private int top;
        private boolean shown;

        private Row(int height) {
            this.height = height;
        }

        private void add(AbstractWidget widget, int offset) {
            widgets.add(widget);
            offsets.add(offset);
        }
    }

    /**
     * Custom slider for color values (0.0 - 1.0)
     */
    private static class ColorSlider extends StyledSlider {
        private final Component prefix;
        private final java.util.function.Consumer<Double> onValueChange;

        public ColorSlider(int x, int y, int width, int height, Component prefix,
                           double initialValue, java.util.function.Consumer<Double> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int intValue = (int)(this.value * 255);
            this.setMessage(Component.literal(prefix.getString() + intValue));
        }

        @Override
        protected void applyValue() {
            onValueChange.accept(this.value);
        }

        public void setValue(double newValue) {
            this.value = newValue;
            this.updateMessage();
        }
    }

    /**
     * Custom slider for opacity values (0.0 - 1.0)
     */
    private static class OpacitySlider extends StyledSlider {
        private final Component prefix;
        private final java.util.function.Consumer<Double> onValueChange;

        public OpacitySlider(int x, int y, int width, int height, Component prefix,
                             double initialValue, java.util.function.Consumer<Double> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int percentage = (int)(this.value * 100);
            this.setMessage(Component.literal(prefix.getString() + percentage + "%"));
        }

        @Override
        protected void applyValue() {
            onValueChange.accept(this.value);
        }

        public void setValue(double newValue) {
            this.value = newValue;
            this.updateMessage();
        }
    }

    /**
     * Custom slider for hour values (0-23)
     */
    private static class HourSlider extends StyledSlider {
        private final Component prefix;
        private final java.util.function.Consumer<Integer> onValueChange;

        public HourSlider(int x, int y, int width, int height, Component prefix,
                          int initialValue, java.util.function.Consumer<Integer> onValueChange) {
            super(x, y, width, height, Component.empty(), initialValue / 23.0);
            this.prefix = prefix;
            this.onValueChange = onValueChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int hour = (int) Math.round(this.value * 23);
            this.setMessage(Component.literal(prefix.getString() + String.format("%02d:00", hour)));
        }

        @Override
        protected void applyValue() {
            int hour = (int) Math.round(this.value * 23);
            onValueChange.accept(hour);
        }

        public void setValue(int newValue) {
            this.value = newValue / 23.0;
            this.updateMessage();
        }

        /**
         * Keep the label but show no hour, for a server that has not reported one
         */
        public void showUnknown() {
            this.setMessage(Component.literal(prefix.getString() + "--:--"));
        }
    }
}
