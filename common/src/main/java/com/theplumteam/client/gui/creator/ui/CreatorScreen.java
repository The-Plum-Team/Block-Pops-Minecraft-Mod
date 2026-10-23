package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.SettingsScreen;
import com.theplumteam.client.gui.style.MenuGuiScale;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.worldcontent.ClientWorldCollections;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The base of every creator screen. It draws the look the mod's menus share with Quick Skin (the
 * machine screen's colour and scrolling stars, dimmed, under a raised charcoal panel in the
 * menus' grey border and black outline) and hosts the screen's toolkit nodes: it draws them,
 * hands them input, and draws the menu, toast, dialog and tooltip above them. Drawn under another
 * screen, as the overview is under the new-collection wizard, it leaves those overlays to the
 * screen on top.
 *
 * <p>Like the other menus, the creator is drawn at a GUI scale of its own, chosen from the window
 * size ({@link MenuGuiScale}), so its canvas is between about 400x300 and 860x520 GUI units
 * whatever GUI Scale the player picked; the player's option is never written. Every screen lays
 * itself out from {@code width} and {@code height} within that canvas.
 *
 * <p>This is the only creator class that overrides Minecraft's input hooks, whose shapes differ
 * before 1.21, from 1.21 to 1.21.8, and from 1.21.9 (26.x here). Each override turns its event
 * into a {@link UiInput} record and passes it on in a fixed order: the open dialog, then the open
 * menu, the toast, the node that has the keyboard, the other nodes from the top down, and last
 * the screen's vanilla widgets.
 *
 * <p>A screen follows the World collections the server syncs: a new sync calls {@link #onSync}
 * and builds the screen again, keeping the focus in the text field that had it. It goes back to
 * the screen it came from once {@link SettingsScreen#canOpenCreator()} turns false. The server
 * checks every request again; these screens only ask.
 */
public abstract class CreatorScreen extends Screen implements MenuGuiScale.Scaled {
    public static final String KEY = "blockpops.world_creator.";
    // Below this canvas the panel leaves a narrow margin, so the smallest canvas keeps its room
    private static final int ROOMY_WIDTH = 520;
    private static final int ROOMY_HEIGHT = 340;
    // Below 1.21.9 the screen finds double clicks itself, with vanilla's limits
    private static final long DOUBLE_CLICK_MS = 250;
    private static final double DOUBLE_CLICK_DISTANCE = 4.0;
    // How far forward the menu, toast, dialog and tooltip are drawn before 1.21.6
    private static final float OVERLAY_DEPTH = 800.0f;

    // The screen to go back to; null for the overview opened by the creator's command, which
    // goes back to the game
    @Nullable
    protected final Screen parent;
    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;
    /** Where the pointer last moved, which tells a dropped file the slot under it. */
    protected double lastMouseX = -1;
    protected double lastMouseY = -1;

    private final List<UiNode> nodes = new ArrayList<>();
    // Text fields by name, so a rebuild can give the focus back to the same field
    private final Map<String, EditBox> textFields = new LinkedHashMap<>();
    // The screen's own widgets the open dialog switched off, to switch back on when it closes
    private final List<AbstractWidget> disabledByModal = new ArrayList<>();
    @Nullable
    private UiModal modal;
    @Nullable
    private GuiEventListener focusBeforeModal;
    @Nullable
    private UiMenu menu;
    @Nullable
    private UiNode focusedNode;
    // The node that took the last press, which gets its drags and its release
    @Nullable
    private UiNode pressedNode;
    private long lastClickTime;
    private double lastClickX;
    private double lastClickY;
    private int lastClickButton = -1;
    private int shownRevision;
    private Component status = Component.empty();
    private int statusColour = Theme.MUTED;

    protected CreatorScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
        this.shownRevision = ClientWorldCollections.revision();
        if (!(parent instanceof CreatorScreen)) {
            // A message left from an earlier visit, such as one cut short by a disconnect
            UiToast.clear();
        }
    }

    /** A line of the creator screens, under the {@code blockpops.world_creator.} keys. */
    public static Component tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    // Building

    @Override
    protected void init() {
        MenuGuiScale.enter(this.minecraft, this);
        super.init();
        if (drawsParentBelow() && this.parent != null
                && (this.parent.width != this.width || this.parent.height != this.height)) {
            // Only the screen shown is told the window changed size, and the parent is drawn under this one
            //? if >=26 {
            /*this.parent.resize(this.width, this.height);
            *///? } else {
            this.parent.resize(this.minecraft, this.width, this.height);
            //? }
        }
        int margin = panelMargin();
        this.panelX = margin;
        this.panelY = margin;
        this.panelWidth = Math.max(0, this.width - 2 * margin);
        this.panelHeight = Math.max(0, this.height - 2 * margin);
        this.nodes.clear();
        this.textFields.clear();
        this.disabledByModal.clear();
        this.focusedNode = null;
        this.pressedNode = null;
        // A menu belongs to a node of the old layout
        this.menu = null;
        build();
        if (this.modal != null) {
            attachModal(this.modal);
        }
    }

    /** The gap between the screen's edge and the panel: narrow on a small canvas. */
    protected int panelMargin() {
        return this.width < ROOMY_WIDTH || this.height < ROOMY_HEIGHT ? Theme.SPACE_S : Theme.SPACE_XL;
    }

    /**
     * Whether this screen draws its parent under itself, as the new-collection wizard draws the
     * overview; the parent is then laid out again whenever this screen's size changes.
     */
    protected boolean drawsParentBelow() {
        return false;
    }

    /** Adds this screen's nodes and widgets; runs on every rebuild, so it reads the screen's own state. */
    protected abstract void build();

    /** Called before a rebuild when the server synced a new state of the World collections. */
    protected abstract void onSync();

    /** Draws this screen's own labels, above its vanilla widgets and below its nodes. */
    protected void drawContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Adds a node above those added before it. */
    protected final <T extends UiNode> T addNode(T node) {
        this.nodes.add(node);
        return node;
    }

    protected final void removeNode(UiNode node) {
        this.nodes.remove(node);
        if (this.focusedNode == node) {
            this.focusedNode = null;
        }
        if (this.pressedNode == node) {
            this.pressedNode = null;
        }
    }

    /**
     * Adds a text field. Every field sets its maximum length, because the vanilla default is 32,
     * and gets its value before its responder, which would otherwise see the value as an edit.
     * Fields are never given a filter, which 26.x removed: the responder checks what is typed.
     *
     * @param name a name unique on this screen, which keeps the field's focus across rebuilds
     */
    protected final EditBox textField(String name, int x, int y, int width, int maxLength, Component hint,
                                      String value, Consumer<String> responder) {
        EditBox field = new UiField(this.font, x, y, width, UiField.HEIGHT, hint);
        field.setMaxLength(maxLength);
        field.setHint(hint);
        field.setValue(value);
        field.setResponder(responder);
        this.addRenderableWidget(field);
        this.textFields.put(name, field);
        return field;
    }

    /**
     * Builds the screen again, keeping the focus in the text field that had it. A node that
     * {@link #build} adds again as the same object keeps the keyboard and any press it was
     * taking, so a slider or number field whose change rebuilds the screen goes on working.
     */
    protected final void rebuild() {
        UiNode keyboardNode = this.focusedNode;
        UiNode pressed = this.pressedNode;
        GuiEventListener focused = getFocused();
        String focusedName = null;
        for (Map.Entry<String, EditBox> field : this.textFields.entrySet()) {
            if (focused == field.getValue()) {
                focusedName = field.getKey();
            }
        }
        boolean modalField = this.modal != null && focused instanceof EditBox && this.modal.fields().contains(focused);
        rebuildWidgets();
        if (modalField) {
            setFocused(focused);
        } else if (focusedName != null && this.textFields.get(focusedName) != null) {
            setFocused(this.textFields.get(focusedName));
        }
        // init() forgets both; only a node still on the screen gets them back
        if (keyboardNode != null && this.nodes.contains(keyboardNode) && getFocused() == null) {
            this.focusedNode = keyboardNode;
        }
        if (pressed != null && this.nodes.contains(pressed)) {
            this.pressedNode = pressed;
        }
    }

    /** Gives the keyboard to a node, or to none, taking it from any text field. */
    public final void focusNode(@Nullable UiNode node) {
        this.focusedNode = node;
        if (node != null) {
            setFocused(null);
        }
    }

    @Nullable
    public final UiNode focusedNode() {
        return this.focusedNode;
    }

    /**
     * Whether a node has the keyboard on the creator screen now shown: it is the focused node,
     * or the focused node passes the keyboard down to it through {@link UiNode#focusedChild}.
     * A node with its own text field uses this to show the field's cursor only while it types.
     */
    public static boolean hasKeyboard(UiNode node) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreatorScreen screen) || screen.modal != null) {
            return false;
        }
        UiNode at = screen.focusedNode;
        // Bounded, so a node that names itself as its own child cannot hang the game
        for (int depth = 0; at != null && depth < 16; depth++) {
            if (at == node) {
                return true;
            }
            at = at.focusedChild();
        }
        return false;
    }

    /** Takes the keyboard from a node that has it, as Enter in a number field does. */
    public static void releaseKeyboard(UiNode node) {
        Minecraft minecraft = Minecraft.getInstance();
        if (hasKeyboard(node) && minecraft.screen instanceof CreatorScreen screen) {
            screen.focusedNode = null;
        }
    }

    // Overlays

    /** Opens a dialog over the screen, closing any dialog or menu already open. */
    public final void openModal(UiModal dialog) {
        if (this.modal != null) {
            closeModal(this.modal);
        }
        closeMenu();
        this.focusBeforeModal = getFocused();
        this.focusedNode = null;
        this.modal = dialog;
        dialog.attach(this);
        attachModal(dialog);
        List<EditBox> fields = dialog.fields();
        setFocused(fields.isEmpty() ? null : fields.get(0));
    }

    private void attachModal(UiModal dialog) {
        dialog.place(this.width, this.height);
        List<EditBox> fields = dialog.fields();
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget && !fields.contains(child) && widget.active) {
                widget.active = false;
                this.disabledByModal.add(widget);
            }
        }
        for (EditBox field : fields) {
            if (!children().contains(field)) {
                addWidget(field);
            }
        }
    }

    /** Closes a dialog if it is the open one; {@link UiModal#close()} calls this. */
    final void closeModal(UiModal dialog) {
        if (this.modal != dialog) {
            return;
        }
        for (EditBox field : dialog.fields()) {
            removeWidget(field);
        }
        for (AbstractWidget widget : this.disabledByModal) {
            widget.active = true;
        }
        this.disabledByModal.clear();
        dialog.attach(null);
        this.modal = null;
        GuiEventListener back = this.focusBeforeModal;
        this.focusBeforeModal = null;
        setFocused(back != null && children().contains(back) ? back : null);
    }

    public final boolean isModalOpen() {
        return this.modal != null;
    }

    /** Opens a menu under an anchor, closing any menu already open. */
    public final void openMenu(UiMenu popup, UiNode.Rect anchor) {
        popup.place(anchor, this.width, this.height);
        this.menu = popup;
    }

    public final void closeMenu() {
        this.menu = null;
    }

    /** Shows a message in the creator's toast; see {@link UiToast}. */
    public final void toast(Component text) {
        UiToast.show(text);
    }

    public final void toast(Component text, Component action, Runnable onAction) {
        UiToast.show(text, action, onAction);
    }

    /** Sets the status line; an empty line hides it. */
    public final void status(Component text) {
        status(text, Theme.MUTED);
    }

    public final void status(Component text, int colour) {
        this.status = text;
        this.statusColour = colour;
    }

    /** Whether the status line shows a message now. */
    protected final boolean hasStatus() {
        return !this.status.getString().isEmpty();
    }

    /** The height of the status line, by default just above the panel's bottom row of buttons. */
    protected int statusY() {
        return this.panelY + this.panelHeight - 40;
    }

    /** The height of the toast's bottom edge. */
    protected int toastBottom() {
        return this.panelY + this.panelHeight - 34;
    }

    // Ticking

    @Override
    public void tick() {
        super.tick();
        if (!SettingsScreen.canOpenCreator()) {
            // Not an operator any more, or the server stopped sending World collections
            leave();
            return;
        }
        if (MenuGuiScale.reapply(this.minecraft, this)) {
            // Another mod put the player's scale back on the window after init()
            rebuild();
        }
        if (ClientWorldCollections.revision() != this.shownRevision) {
            this.shownRevision = ClientWorldCollections.revision();
            onSync();
            if (this.minecraft.screen != this) {
                return;
            }
            rebuild();
        }
        for (UiNode node : List.copyOf(this.nodes)) {
            node.tick();
        }
        if (this.modal != null) {
            this.modal.tick();
        }
    }

    // Drawing

    //? if >=1.21 && <1.21.6 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws its own background and panel before Screen.render, which on 1.21 to
        // 1.21.5 calls this vanilla pass and would blur the panel on top of it.
    }
    *///? }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing under the dialog, the menu or the toast reacts to the pointer over them
        UiToast toastShown = UiToast.current();
        boolean covered = this.modal != null
                || (this.menu != null && this.menu.isMouseOver(mouseX, mouseY))
                || (toastShown != null && toastShown.covers(this.width, toastBottom(), mouseX, mouseY));
        int nodeX = covered ? -1 : mouseX;
        int nodeY = covered ? -1 : mouseY;
        UiTooltip.clear();

        drawBackground(graphics, partialTick);
        drawPanel(graphics);
        super.render(graphics, nodeX, nodeY, partialTick);
        // Labels and nodes are drawn after the widgets: before 1.21.6 anything drawn earlier
        // ends up under the screen background
        drawContents(graphics, nodeX, nodeY, partialTick);
        for (UiNode node : this.nodes) {
            if (node.visible()) {
                node.draw(graphics, nodeX, nodeY, partialTick);
            }
        }
        if (!this.status.getString().isEmpty()) {
            Draw.centred(graphics, this.font, this.status.getString(), this.panelX + this.panelWidth / 2, statusY(),
                    this.panelWidth - 16, this.statusColour);
        }
        if (covered) {
            // A node's tooltip under an overlay would cover the overlay
            UiTooltip.clear();
        }
        if (this.minecraft.screen != this) {
            // Drawn under another screen, such as the new-collection wizard's card, which
            // draws the menu, the toast and the tooltips of its own
            return;
        }

        // Overlays above models and items: a later stratum from 1.21.6, depth before it, in
        // front of the badges and buttons a node lifts 400 over its own models
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, OVERLAY_DEPTH);
        int overlayX = this.modal != null ? -1 : mouseX;
        int overlayY = this.modal != null ? -1 : mouseY;
        if (this.menu != null) {
            this.menu.draw(graphics, overlayX, overlayY, partialTick);
        }
        if (toastShown != null) {
            toastShown.draw(graphics, this.width, toastBottom(), overlayX, overlayY);
        }
        if (this.modal != null) {
            // Its own layer, so the text of the menu and toast does not show through its dimming
            GuiPose.flush(graphics);
            GuiPose.nextStratum(graphics);
            this.modal.draw(graphics, mouseX, mouseY, partialTick);
        }
        // Still lifted, so before 1.21.6 an item on the dialog cannot cover the tooltip
        UiTooltip.draw(graphics, this.font, mouseX, mouseY);
        GuiPose.pop(graphics);
    }

    /**
     * The background of the machine screen the creator opens from, dimmed as SettingsScreen
     * dims it: its colour and scrolling stars from the client config.
     */
    protected void drawBackground(GuiGraphics graphics, float partialTick) {
        ClientConfig config = ClientConfig.getInstance();
        int background = 0xFF000000 | ((int) (config.backgroundColorR * 255) << 16)
                | ((int) (config.backgroundColorG * 255) << 8) | (int) (config.backgroundColorB * 255);
        graphics.fill(0, 0, this.width, this.height, background);
        //? if <1.21.5 {
        // Finish buffered base fills before the immediate textured quad draws.
        GuiPose.flush(graphics);
        //? }
        CollectionSelectionScreen.drawStarPattern(graphics, this.width, this.height, partialTick);
        graphics.fill(0, 0, this.width, this.height, Theme.DIM);
        // Close the background layer so the panel and its text draw above it
        GuiPose.nextStratum(graphics);
    }

    /** The panel: a dialog's charcoal face in the menus' border and outline, on its lip. */
    protected void drawPanel(GuiGraphics graphics) {
        Draw.raised(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, Surfaces.BORDER,
                Theme.PANEL);
    }

    // Input

    //? if <26 {
    private UiInput.Click legacyClick(double mouseX, double mouseY, int button) {
        long now = Util.getMillis();
        boolean doubleClick = button == this.lastClickButton && now - this.lastClickTime <= DOUBLE_CLICK_MS
                && Math.abs(mouseX - this.lastClickX) <= DOUBLE_CLICK_DISTANCE
                && Math.abs(mouseY - this.lastClickY) <= DOUBLE_CLICK_DISTANCE;
        // A third click starts a new pair rather than making a second double click
        this.lastClickTime = doubleClick ? 0 : now;
        this.lastClickX = mouseX;
        this.lastClickY = mouseY;
        this.lastClickButton = button;
        return new UiInput.Click(mouseX, mouseY, button, Screen.hasControlDown(), Screen.hasShiftDown(), doubleClick,
                null);
    }
    //? }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        UiInput.Click click = new UiInput.Click(bpEvent.x(), bpEvent.y(), bpEvent.button(), bpEvent.hasControlDown(),
                bpEvent.hasShiftDown(), bpDouble, bpEvent);
        return handleClick(click, () -> super.mouseClicked(bpEvent, bpDouble));
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleClick(legacyClick(mouseX, mouseY, button), () -> super.mouseClicked(mouseX, mouseY, button));
    }
    //? }

    private boolean handleClick(UiInput.Click click, BooleanSupplier vanilla) {
        this.lastMouseX = click.x();
        this.lastMouseY = click.y();
        this.pressedNode = null;
        if (this.modal != null) {
            UiModal dialog = this.modal;
            for (EditBox field : dialog.fields()) {
                if (field.isMouseOver(click.x(), click.y())) {
                    setFocused(field);
                    if (click.left()) {
                        // As vanilla's own click would, so a drag selects text in the field
                        setDragging(true);
                    }
                    UiInput.forwardClick(field, click);
                    return true;
                }
            }
            return dialog.click(click);
        }
        if (this.menu != null) {
            UiMenu popup = this.menu;
            if (!popup.isMouseOver(click.x(), click.y())) {
                // A click outside a menu only closes it
                closeMenu();
                return true;
            }
            Runnable action = popup.actionAt(click.x(), click.y());
            if (action != null) {
                closeMenu();
                action.run();
            }
            return true;
        }
        UiToast toastShown = UiToast.current();
        if (toastShown != null && toastShown.click(click, this.width, toastBottom())) {
            return true;
        }
        UiNode focused = this.focusedNode;
        if (focused != null && focused.visible() && this.nodes.contains(focused) && focused.click(click)) {
            this.pressedNode = focused;
            return true;
        }
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            UiNode node = this.nodes.get(i);
            if (node != focused && node.visible() && node.click(click)) {
                this.pressedNode = node;
                // The click may have rebuilt the screen, leaving the node off it, or opened a
                // dialog, whose first text field keeps the keyboard it was given
                if (this.modal == null) {
                    focusNode(this.nodes.contains(node) ? node : null);
                }
                return true;
            }
        }
        if (vanilla.getAsBoolean()) {
            this.focusedNode = null;
            return true;
        }
        // A click on nothing takes the keyboard from whatever had it
        this.focusedNode = null;
        setFocused(null);
        return false;
    }

    @Override
    //? if >=26 {
    /*public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent bpEvent) {
        UiInput.Click click = new UiInput.Click(bpEvent.x(), bpEvent.y(), bpEvent.button(), bpEvent.hasControlDown(),
                bpEvent.hasShiftDown(), false, bpEvent);
        return handleRelease(click, () -> super.mouseReleased(bpEvent));
    }
    *///? } else {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        UiInput.Click click = new UiInput.Click(mouseX, mouseY, button, Screen.hasControlDown(), Screen.hasShiftDown(),
                false, null);
        return handleRelease(click, () -> super.mouseReleased(mouseX, mouseY, button));
    }
    //? }

    private boolean handleRelease(UiInput.Click click, BooleanSupplier vanilla) {
        UiNode pressed = this.pressedNode;
        this.pressedNode = null;
        boolean used = this.modal != null && this.modal.release(click);
        // A node whose press opened a dialog still hears its release, so it ends that press
        if (pressed != null && pressed.release(click)) {
            used = true;
        }
        // Vanilla always hears the release, so a text field ends its selection drag
        return vanilla.getAsBoolean() || used;
    }

    @Override
    //? if >=26 {
    /*public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent bpEvent, double dragX, double dragY) {
        UiInput.Click click = new UiInput.Click(bpEvent.x(), bpEvent.y(), bpEvent.button(), bpEvent.hasControlDown(),
                bpEvent.hasShiftDown(), false, bpEvent);
        return handleDrag(click, dragX, dragY, () -> super.mouseDragged(bpEvent, dragX, dragY));
    }
    *///? } else {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        UiInput.Click click = new UiInput.Click(mouseX, mouseY, button, Screen.hasControlDown(), Screen.hasShiftDown(),
                false, null);
        return handleDrag(click, dragX, dragY, () -> super.mouseDragged(mouseX, mouseY, button, dragX, dragY));
    }
    //? }

    private boolean handleDrag(UiInput.Click click, double dragX, double dragY, BooleanSupplier vanilla) {
        this.lastMouseX = click.x();
        this.lastMouseY = click.y();
        UiNode pressed = this.modal != null ? this.modal : this.pressedNode;
        if (pressed != null && pressed.drag(click, dragX, dragY)) {
            return true;
        }
        return vanilla.getAsBoolean();
    }

    @Override
    //? if >=1.21 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double amount) {
        return handleScroll(mouseX, mouseY, amount, () -> super.mouseScrolled(mouseX, mouseY, horizontal, amount));
    }
    *///? } else {
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return handleScroll(mouseX, mouseY, amount, () -> super.mouseScrolled(mouseX, mouseY, amount));
    }
    //? }

    private boolean handleScroll(double mouseX, double mouseY, double amount, BooleanSupplier vanilla) {
        if (this.modal != null) {
            return true;
        }
        if (this.menu != null) {
            // A menu longer than the screen scrolls; nothing under an open menu does
            this.menu.scroll(mouseX, mouseY, amount);
            return true;
        }
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            UiNode node = this.nodes.get(i);
            if (node.isMouseOver(mouseX, mouseY) && node.scroll(mouseX, mouseY, amount)) {
                return true;
            }
        }
        return vanilla.getAsBoolean();
    }

    /**
     * Routes files dropped on the window: to the drop zone under the pointer when it takes
     * them, else to the only zone that does, else a menu at the pointer asks which. With no
     * zone to take them, the zone under the pointer says why it cannot; with none there,
     * {@link #filesDroppedNowhere} does.
     */
    @Override
    public void onFilesDrop(List<Path> paths) {
        if (this.modal != null || paths.isEmpty()) {
            return;
        }
        closeMenu();
        List<UiDropZone> zones = new ArrayList<>();
        UiDropZone under = null;
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            UiNode node = this.nodes.get(i);
            if (!node.visible()) {
                continue;
            }
            UiDropZone found = node.dropZones(this.lastMouseX, this.lastMouseY, zones);
            if (under == null) {
                under = found;
            }
        }
        if (under != null && under.accepts(paths)) {
            under.receive(paths);
            return;
        }
        List<UiDropZone> accepting = new ArrayList<>();
        for (UiDropZone zone : zones) {
            if (zone.accepts(paths)) {
                accepting.add(zone);
            }
        }
        if (accepting.size() == 1) {
            accepting.get(0).receive(paths);
        } else if (accepting.size() > 1) {
            UiMenu which = new UiMenu().heading(tr("drop.which_slot"));
            for (UiDropZone zone : accepting) {
                zone.flash();
                which.item(zone.label(), () -> zone.receive(paths));
            }
            openMenu(which, new UiNode.Rect((int) this.lastMouseX, (int) this.lastMouseY, 0, 0));
        } else if (under != null) {
            under.receive(paths);
        } else {
            filesDroppedNowhere(paths);
        }
    }

    /** Files were dropped where no zone takes them; by default the status line says so. */
    protected void filesDroppedNowhere(List<Path> paths) {
        status(tr("drop.nowhere"), Theme.RED);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    //? if >=26 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent bpEvent) {
        UiInput.Key key = new UiInput.Key(bpEvent.key(), bpEvent.scancode(), bpEvent.modifiers(),
                bpEvent.hasControlDown(), bpEvent.hasShiftDown(), bpEvent.isEscape(), bpEvent.isCopy(),
                bpEvent.isPaste(), bpEvent.isSelectAll(), bpEvent);
        return handleKey(key, () -> super.keyPressed(bpEvent));
    }
    *///? } else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        UiInput.Key key = new UiInput.Key(keyCode, scanCode, modifiers, Screen.hasControlDown(), Screen.hasShiftDown(),
                keyCode == InputConstants.KEY_ESCAPE, Screen.isCopy(keyCode), Screen.isPaste(keyCode),
                Screen.isSelectAll(keyCode), null);
        return handleKey(key, () -> super.keyPressed(keyCode, scanCode, modifiers));
    }
    //? }

    private boolean handleKey(UiInput.Key key, BooleanSupplier vanilla) {
        if (this.modal != null) {
            if (this.modal.key(key)) {
                return true;
            }
            GuiEventListener focused = getFocused();
            // Only the dialog's own fields type while it is open
            return focused instanceof EditBox && this.modal.fields().contains(focused) ? vanilla.getAsBoolean() : true;
        }
        if (this.menu != null) {
            UiMenu popup = this.menu;
            if (key.escape()) {
                closeMenu();
            } else if (key.enter()) {
                Runnable action = popup.highlightedAction();
                if (action != null) {
                    closeMenu();
                    action.run();
                }
            } else {
                popup.key(key);
            }
            return true;
        }
        UiNode focused = this.focusedNode;
        if (focused != null && focused.visible() && this.nodes.contains(focused) && focused.key(key)) {
            return true;
        }
        if (getFocused() != null) {
            // A text field or other vanilla widget has the keyboard; a Ctrl shortcut it leaves
            // unused, such as Ctrl+S, still reaches the screen, since no Ctrl key types text
            return vanilla.getAsBoolean() || key.ctrl() && shortcut(key);
        }
        if (shortcut(key)) {
            return true;
        }
        for (int i = this.nodes.size() - 1; i >= 0; i--) {
            UiNode node = this.nodes.get(i);
            if (node != focused && node.visible() && node.key(key)) {
                return true;
            }
        }
        return vanilla.getAsBoolean();
    }

    /**
     * A screen's keyboard shortcuts, such as Ctrl+N; asked after the focused node and any text
     * field, so typing never triggers one. A text field passes on the Ctrl keys it does not use.
     */
    protected boolean shortcut(UiInput.Key key) {
        return false;
    }

    @Override
    //? if >=26 {
    /*public boolean charTyped(net.minecraft.client.input.CharacterEvent bpEvent) {
        return handleTyped(bpEvent.codepointAsString(), () -> super.charTyped(bpEvent));
    }
    *///? } else {
    public boolean charTyped(char character, int modifiers) {
        return handleTyped(String.valueOf(character), () -> super.charTyped(character, modifiers));
    }
    //? }

    private boolean handleTyped(String text, BooleanSupplier vanilla) {
        if (this.modal != null) {
            return vanilla.getAsBoolean();
        }
        if (this.menu != null) {
            return true;
        }
        UiNode focused = this.focusedNode;
        if (focused != null && focused.visible() && this.nodes.contains(focused) && focused.typed(text)) {
            return true;
        }
        return vanilla.getAsBoolean();
    }

    // Closing

    /** Goes back to the screen this one came from; leaving the creator also drops its toast. */
    protected final void leave() {
        if (!(this.parent instanceof CreatorScreen)) {
            UiToast.clear();
        }
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        leave();
    }

    @Override
    public void removed() {
        super.removed();
        // A creator screen opened next takes the menus' scale again in its own init()
        MenuGuiScale.restore(this.minecraft);
    }

    @Override
    public boolean isPauseScreen() {
        // A paused integrated server stops ticking, and with it syncs, file sends and upload sweeps
        return false;
    }
}
