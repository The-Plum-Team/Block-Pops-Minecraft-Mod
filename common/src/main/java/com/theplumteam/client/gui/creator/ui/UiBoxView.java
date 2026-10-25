package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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

    private final UiSegmented modes;
    private final UiMachineReplica replica = new UiMachineReplica();
    @Nullable
    private String collectionId;
    private Mode mode = Mode.BOX;
    private ItemStack box = ItemStack.EMPTY;
    private ItemStack worldBox = ItemStack.EMPTY;
    private final ItemStack grass = grassStack();
    @Nullable
    private String boxKey;

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
        return mode != Mode.SCREEN;
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
        int top = y + INSET + UiSegmented.HEIGHT + 4;
        replica.at(x + INSET, top, width - 2 * INSET, Math.max(0, y + height - INSET - top));
        replica.setVisible(mode == Mode.SCREEN);
    }

    // The room between the switch at the top and the buttons and hint at the bottom
    private int sceneTop() {
        return modes.bounds().bottom() + 4;
    }

    private int sceneBottom() {
        return Math.min(buttonsTop(), hintY() - 2) - 4;
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
        switch (mode) {
            case BOX -> {
                graphics.fillGradient(x, y, x + width, y + height, BOX_TOP, BOX_BOTTOM);
                if (collection != null) {
                    updateBoxes(collection);
                    float size = Math.max(8.0f, Math.min(width * 0.6f, (sceneBottom() - sceneTop())
                            / UiFigureView.BOX_HEIGHT));
                    drawBoxFront(graphics, box, yaw(), 0, x + width / 2.0f, (sceneTop() + sceneBottom()) / 2.0f, size);
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

    @Override
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (turnable()) {
            Font font = Minecraft.getInstance().font;
            label(graphics, font, CreatorScreen.tr("preview.drag_hint"), x + INSET + 2, hintY(),
                    Math.max(0, width - 2 * INSET - 4), Theme.MUTED);
        }
    }
}
