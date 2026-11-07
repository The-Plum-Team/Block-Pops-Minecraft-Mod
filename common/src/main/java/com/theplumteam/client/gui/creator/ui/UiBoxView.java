package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.PixelFont;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The live preview of a collection's box and look, as the identity page shows it, in one of
 * three views a switch at the top picks:
 * <ul>
 *   <li>{@link Mode#BOX}: the box holding the collection's first figure, seen from the front and
 *       a little above, as the favorite colour screen shows boxes;</li>
 *   <li>{@link Mode#WORLD}: the box standing on a grass block under the sky, both at the angle
 *       Minecraft shows blocks at in a slot, as it looks placed in the world;</li>
 *   <li>{@link Mode#SCREEN}: the claw machine's screen showing the collection, through a
 *       {@link UiMachineReplica}.</li>
 * </ul>
 * The box shows the collection's box art, colour or plain box, as a box of a World collection
 * does. Dragging turns the box (see {@link UiViewport}); the machine screen does not turn.
 *
 * <p>The collection is looked up by id on every frame through the collection registry, so the
 * view follows the creator's preview of a collection (see {@link CreatorPreview}).
 *
 * <p>A host that picks the view itself, or shows only the box, as the new-collection form does,
 * can hide the switch ({@link #modeSwitch}) and the line that says the view turns
 * ({@link #dragHint}); the box then takes their room. Such a host may also stand the box in the
 * collection's colour, as the home's showcase does ({@link #stageBackground}).
 *
 * <p>A host with its own way to the views, as the look's "View ⌄" menu, puts small controls in
 * the corners instead of the switch and the buttons ({@link #corners}): its view button at the
 * top, and two lit icons at the bottom right that spin the box and switch the night light. It may
 * size the box by its share of the view, centred ({@link #boxFill}); mark a box whose image is
 * still missing ({@link #ghost}); and show the front of the box flat, to place the logo on, in
 * place of the box ({@link #frontEditor}), during which the corner controls and turning are off.
 *
 * <p>Unlike the other views, this one must not sit in a {@link UiScrollPane} or be scaled, since
 * its machine screen view holds a replica whose grid clips itself in screen units.
 */
public class UiBoxView extends UiViewport {
    /** What the view shows the box in. */
    public enum Mode {
        BOX, WORLD, SCREEN
    }

    private static final int BOX_TOP = 0xFF34353A;
    private static final int BOX_BOTTOM = 0xFF151617;
    private static final int SKY_TOP = 0xFF8FB6FF;
    private static final int SKY_BOTTOM = 0xFFB9D2FF;
    // A box on a block at the slot angle, against the size they are drawn at, and how far the
    // block reaches below its centre
    private static final float WORLD_HEIGHT = 1.5f;
    private static final float BLOCK_BELOW_CENTRE = 0.5f;
    // A box drawn at a size is this much of it wide and high, which a ghost's outline follows
    private static final float BOX_WIDTH = 0.87f;
    private static final float BOX_HEIGHT = 1.08f;
    // The room left at the view's sides by a box sized from the view's width
    private static final int FILL_SIDES = 20;
    // The scene faded under a box with no image, inside the view's frame
    private static final int GHOST_WASH = 0xB01E1F21;
    // The words of a missing image beside a narrow view's box take at most this many lines
    private static final int GHOST_TEXT_LINES = 3;
    private static final int ICON_STEP = UiIconButton.SIZE + 3;

    private final UiSegmented modes;
    private final UiMachineReplica replica = new UiMachineReplica();
    @Nullable
    private String collectionId;
    private Mode mode = Mode.BOX;
    private boolean switchShown = true;
    private boolean hintShown = true;
    private boolean stageShown;
    private ItemStack box = ItemStack.EMPTY;
    private ItemStack worldBox = ItemStack.EMPTY;
    private final ItemStack grass = grassStack();
    @Nullable
    private String boxKey;
    // The host's corner controls and box size, the mark of a missing image, and the front's editor
    private float boxFill;
    @Nullable
    private UiViewButton viewButton;
    private boolean viewOnLeft;
    @Nullable
    private UiIconButton spinIcon;
    @Nullable
    private UiIconButton lightIcon;
    @Nullable
    private BooleanSupplier ghostShown;
    private float ghostScale = 4.0f;
    @Nullable
    private Component ghostText;
    @Nullable
    private UiBoxFrontEditor frontEditor;

    public UiBoxView() {
        this.modes = new UiSegmented(0, index -> setMode(Mode.values()[index]),
                CreatorScreen.tr("preview.box"), CreatorScreen.tr("preview.world"),
                CreatorScreen.tr("preview.screen"));
        this.controls.add(this.modes);
        this.replica.setVisible(false);
        // The replica takes its clicks before the view could start a turn
        this.controls.add(0, this.replica);
    }

    /** Shows a collection by id: a creator preview's id or any registered collection's; null shows none. */
    public void setCollection(@Nullable String collectionId) {
        this.collectionId = collectionId;
        this.replica.setCollection(collectionId);
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.modes.select(mode.ordinal());
    }

    /**
     * Shows or hides the switch between the three views at the top, for a host that picks the
     * view itself; hidden, the scene starts at the view's top.
     */
    public UiBoxView modeSwitch(boolean shown) {
        this.switchShown = shown;
        return this;
    }

    /**
     * Shows or hides the line over the scene that says it is live and turns when dragged, for a
     * view too small to hold it whole; the host then says so where it has room, such as in a
     * tooltip. Dragging turns the box either way.
     */
    public UiBoxView dragHint(boolean shown) {
        this.hintShown = shown;
        return this;
    }

    /**
     * Paints the box view's background as the home's showcase paints a collection's stage: the
     * collection's colour, darkened, fading into the dark well over three quarters of the
     * height, so a preview looks as the collection will on the home. Off, and for a collection
     * with no colour, the background is a neutral grey.
     */
    public UiBoxView stageBackground(boolean shown) {
        this.stageShown = shown;
        return this;
    }

    /**
     * Sizes the box from the view rather than from the room between the switch and the buttons,
     * and centres it: as large as a share of the view's height, less its edges, allows, and no
     * wider than the view. 0 goes back to the usual size.
     *
     * @param heightShare the box's size against the view's height inside its edges
     */
    public UiBoxView boxFill(float heightShare) {
        this.boxFill = Math.max(0.0f, heightShare);
        return this;
    }

    /** The size the box is drawn at in the box view, as an item drawn that big. */
    public float boxSize() {
        if (this.boxFill > 0) {
            float byWidth = (width - FILL_SIDES) / BOX_WIDTH;
            float byHeight = (height - 2 * INSET) * this.boxFill;
            return Math.max(8.0f, Math.min(byWidth, byHeight));
        }
        return Math.max(8.0f, Math.min(width * 0.6f, (sceneBottom() - sceneTop()) / UiFigureView.BOX_HEIGHT));
    }

    // Where the box's middle is: the view's, when it fills it, else the room's between the controls
    private float boxCenterY() {
        return this.boxFill > 0 ? y + height / 2.0f : (sceneTop() + sceneBottom()) / 2.0f;
    }

    /**
     * Puts small controls in the view's corners in place of the switch, the "Rotate" and "Light"
     * buttons and the drag hint: the host's view button {@value #INSET} units in from the top, on
     * the left or the right, and at the bottom right two lit icons that spin the box and switch
     * the night light. Called again, it only moves the view button to the side given.
     */
    public UiBoxView corners(UiViewButton button, boolean onLeft) {
        if (this.viewButton == null) {
            modeSwitch(false).dragHint(false);
            setButtons(false);
            this.spinIcon = new UiIconButton(() -> PixelIcon.ROTATE, this::isSpinning,
                    () -> CreatorScreen.tr("preview.spin"), () -> setSpinning(!isSpinning()));
            this.lightIcon = new UiIconButton(() -> isNight() ? PixelIcon.MOON : PixelIcon.SUN, this::isNight,
                    () -> CreatorScreen.tr(isNight() ? "preview.light_night" : "preview.light_day"),
                    () -> setNight(!isNight()));
            this.controls.add(button);
            this.controls.add(this.spinIcon);
            this.controls.add(this.lightIcon);
        } else if (this.viewButton != button) {
            this.controls.remove(this.viewButton);
            this.controls.add(button);
        }
        this.viewButton = button;
        this.viewOnLeft = onLeft;
        return this;
    }

    /**
     * Marks the box as missing its image while {@code shown} says so, in the box view: the scene
     * fades, an amber dashed outline stands where the box is, with an amber "?" in it, and the
     * text, when given, is written in amber on the box's left, where a narrow form has no other
     * place to say it. Null takes the mark away.
     *
     * @param questionScale the pixel font's size of the "?"
     */
    public UiBoxView ghost(@Nullable BooleanSupplier shown, float questionScale, @Nullable Component text) {
        this.ghostShown = shown;
        this.ghostScale = questionScale;
        this.ghostText = text;
        return this;
    }

    /**
     * Shows the front of the box flat, with the logo to drag, in place of the box while the editor
     * is open ({@link UiBoxFrontEditor#isOpen}); its buttons join the view's controls. The view
     * gives its keys to it meanwhile, and does not turn.
     */
    public UiBoxView frontEditor(@Nullable UiBoxFrontEditor editor) {
        if (this.frontEditor != null) {
            this.controls.removeAll(this.frontEditor.controls());
        }
        this.frontEditor = editor;
        if (editor != null) {
            this.controls.addAll(editor.controls());
        }
        return this;
    }

    // Whether the front of the box shows, with the logo to drag
    private boolean editing() {
        return this.frontEditor != null && this.frontEditor.isOpen();
    }

    /** The machine screen the {@link Mode#SCREEN} view shows, for a host that sets who sees it. */
    public UiMachineReplica replica() {
        return replica;
    }

    /**
     * In the machine screen view, which figures count as discovered, asked with
     * "collection:figure"; null for the client's own record.
     */
    public void setDiscovered(@Nullable Predicate<String> discovered) {
        replica.setDiscovered(discovered);
    }

    /** Ends a test draw on the machine screen view; the host screen calls this as it closes. */
    public void close() {
        replica.close();
    }

    @Override
    protected boolean turnable() {
        return mode != Mode.SCREEN && !editing();
    }

    @Override
    public void tick() {
        replica.tick();
    }

    @Override
    public boolean scroll(double mouseX, double mouseY, double amount) {
        return replica.visible() && replica.scroll(mouseX, mouseY, amount);
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (editing()) {
            return this.frontEditor.key(key);
        }
        return replica.visible() && replica.key(key);
    }

    // The boxes hold the collection's first figure, as a box the machine hands out does
    private void updateBoxes(FigureCollection collection) {
        List<FigureDefinition> figures = collection.getRegularFigures();
        String figureId = figures.isEmpty() ? null : figures.get(0).getId();
        String key = collection.getId() + ":" + figureId;
        if (!key.equals(boxKey)) {
            box = boxStack(collection.getId(), figureId, false);
            worldBox = boxStack(collection.getId(), figureId, true);
            boxKey = key;
        }
    }

    // Layout

    @Override
    protected void layout() {
        super.layout();
        modes.at(x + INSET, y + INSET, Math.min(modes.naturalWidth(), Math.max(0, width - 2 * INSET)),
                UiSegmented.HEIGHT);
        modes.setVisible(switchShown);
        int top = switchShown ? y + INSET + UiSegmented.HEIGHT + 4 : y + INSET;
        replica.at(x + INSET, top, width - 2 * INSET, Math.max(0, y + height - INSET - top));
        replica.setVisible(mode == Mode.SCREEN && !editing());
        UiViewButton button = this.viewButton;
        if (button != null && this.spinIcon != null && this.lightIcon != null) {
            int buttonWidth = button.naturalWidth();
            int buttonX = this.viewOnLeft ? x + INSET : x + width - INSET - buttonWidth;
            button.at(buttonX, y + INSET, buttonWidth, UiButton.SMALL_HEIGHT);
            button.setVisible(!editing());
            int iconY = y + height - INSET - UiIconButton.SIZE;
            this.lightIcon.at(x + width - INSET - UiIconButton.SIZE, iconY, UiIconButton.SIZE, UiIconButton.SIZE);
            this.spinIcon.at(x + width - INSET - UiIconButton.SIZE - ICON_STEP, iconY, UiIconButton.SIZE,
                    UiIconButton.SIZE);
            boolean icons = turnable();
            this.lightIcon.setVisible(icons);
            this.spinIcon.setVisible(icons);
        }
        if (this.frontEditor != null) {
            this.frontEditor.at(x, y, width, height);
            this.frontEditor.layout();
        }
    }

    // The room between the switch at the top and the buttons and hint at the bottom, or the
    // view's edges where they are hidden
    private int sceneTop() {
        return switchShown ? modes.bounds().bottom() + 4 : y + INSET;
    }

    private int sceneBottom() {
        return (hintShown ? Math.min(buttonsTop(), hintY() - 2) : buttonsTop()) - 4;
    }

    // The hint goes beside the buttons where it fits, otherwise above them
    private int hintY() {
        Font font = Minecraft.getInstance().font;
        int hintRight = x + INSET + 2 + font.width(CreatorScreen.tr("preview.drag_hint")) + 6;
        return hintRight <= buttonsLeft() ? y + height - INSET - 11 : buttonsTop() - 13;
    }

    // Drawing

    @Override
    protected void drawScene(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        FigureCollection collection = collectionId != null
                ? CollectionRegistry.getCollection(collectionId).orElse(null) : null;
        if (editing()) {
            drawStage(graphics, collection);
            this.frontEditor.drawFace(graphics, mouseX, mouseY);
            return;
        }
        switch (mode) {
            case BOX -> {
                drawStage(graphics, collection);
                if (collection != null) {
                    updateBoxes(collection);
                    drawBoxFront(graphics, box, yaw(), 0, x + width / 2.0f, boxCenterY(), boxSize());
                }
            }
            case WORLD -> {
                graphics.fillGradient(x, y, x + width, y + height, SKY_TOP, SKY_BOTTOM);
                if (collection != null) {
                    updateBoxes(collection);
                    // Two blocks stacked at the slot angle are about one and a half sizes tall
                    float size = Math.max(8.0f, Math.min(width * 0.45f, (sceneBottom() - sceneTop()) / WORLD_HEIGHT));
                    float blockCenterY = sceneBottom() - size * BLOCK_BELOW_CENTRE;
                    drawBoxOnBlock(graphics, worldBox, grass, yaw(), 0, x + width / 2.0f, blockCenterY, size);
                }
            }
            case SCREEN -> graphics.fill(x, y, x + width, y + height, BACKGROUND);
        }
    }

    // The box view's background: the collection's stage, or a neutral grey
    private void drawStage(GuiGraphics graphics, @Nullable FigureCollection collection) {
        int stage = stageShown ? collectionColour(collection, 0) : 0;
        if (stage != 0) {
            graphics.fill(x, y, x + width, y + height, BACKGROUND);
            graphics.fillGradient(x, y, x + width, y + height * 3 / 4, darken(stage, 0.5f), BACKGROUND);
        } else {
            graphics.fillGradient(x, y, x + width, y + height, BOX_TOP, BOX_BOTTOM);
        }
    }

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (editing()) {
            this.frontEditor.drawText(graphics, mouseX, mouseY);
            return;
        }
        BooleanSupplier ghost = this.ghostShown;
        if (mode == Mode.BOX && ghost != null && ghost.getAsBoolean()) {
            drawGhost(graphics);
        }
        if (turnable() && hintShown) {
            Font font = Minecraft.getInstance().font;
            label(graphics, font, CreatorScreen.tr("preview.drag_hint"), x + INSET + 2, hintY(),
                    Math.max(0, width - 2 * INSET - 4), Theme.MUTED);
        }
    }

    // No image yet: the box fades, and a "?" stands where its picture goes
    private void drawGhost(GuiGraphics graphics) {
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, GHOST_WASH);
        float size = boxSize();
        int boxWidth = Math.round(size * BOX_WIDTH);
        int boxHeight = Math.round(size * BOX_HEIGHT);
        int centerX = x + width / 2;
        int centerY = Math.round(boxCenterY());
        Draw.dashed(graphics, centerX - boxWidth / 2, centerY - boxHeight / 2, boxWidth, boxHeight, Theme.AMBER, 3);
        PixelFont.drawCentered(graphics, Component.literal("?"), centerX, centerY, Math.max(1, boxWidth - 8),
                this.ghostScale, Theme.AMBER, true);
        Component text = this.ghostText;
        if (text != null) {
            // Beside a narrow view's box, in up to three lines centred on it, so no word of it is cut
            Font font = Minecraft.getInstance().font;
            int left = x + INSET + 2;
            int room = Math.max(0, centerX - boxWidth / 2 - 8 - left);
            int lines = Math.max(1, Draw.fit(font, text.getString(), room, GHOST_TEXT_LINES).size());
            Draw.lines(graphics, font, text.getString(), left, centerY - lines * Theme.LINE_HEIGHT / 2, room,
                    GHOST_TEXT_LINES, Theme.AMBER);
        }
    }

    // While the front of the box shows, a press on the view is the editor's, near the face or not

    @Override
    protected boolean clickScene(UiInput.Click click) {
        return editing() && this.frontEditor.press(click);
    }

    @Override
    protected boolean dragScene(UiInput.Click click, double dragX, double dragY) {
        return editing() && this.frontEditor.drag(click);
    }

    @Override
    protected boolean releaseScene(UiInput.Click click) {
        return this.frontEditor != null && this.frontEditor.release();
    }
}
