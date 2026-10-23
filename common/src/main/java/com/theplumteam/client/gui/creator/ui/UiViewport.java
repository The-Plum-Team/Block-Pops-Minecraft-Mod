package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModItems;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The part the creator's live previews share: a dark area that draws a scene of models, which
 * the player turns by dragging across it, with a "Rotate" button that keeps it turning on its
 * own and a "Light" button that switches between day and night, both of which a small view may
 * hide. Its own controls sit on top of the scene, which subclasses draw in {@link #drawScene}.
 *
 * <p>Night is a translucent blue wash over the models rather than real lighting, because from
 * 1.21.6 an item in a screen is always lit the same way. The wash and the controls are drawn on a
 * layer above the models: a later stratum from 1.21.6, depth before it.
 *
 * <p>The models turn about their upright centre line: a figure through its entry's preview turn,
 * a box through the turn {@link BoxBlockItemRenderer#setView} writes into its stack, so the same
 * code turns them on every version. A view may sit in a {@link UiScrollPane}.
 */
public abstract class UiViewport extends UiNode.Box {
    /** The wash over the models at night. */
    public static final int NIGHT = 0x80101840;
    protected static final int BACKGROUND = Surfaces.WELL;
    protected static final int OUTLINE = Surfaces.OUTLINE;
    /** The gap between the view's edge and its controls. */
    protected static final int INSET = 6;
    // How far a drag turns the models, in degrees per GUI unit
    private static final float DEGREES_PER_UNIT = 1.0f;
    // How fast "Rotate" turns them, in degrees per second
    private static final float SPIN_DEGREES_PER_SECOND = 45.0f;
    // A frame later than this after the last one turns the models no further, as after a pause
    private static final long MAX_FRAME_MS = 100;
    // The angles of the box views below 1.21.4, as screen rotations about X and Y in degrees.
    // From 1.21.4 the creator's box item models bake the same angles (see BoxBlockItemRenderer).
    private static final float FRONT_PITCH = 342.3f;
    private static final float FRONT_YAW = 335.9f;
    private static final float BLOCK_PITCH = -30.0f;
    private static final float BLOCK_YAW = 45.0f;
    // How deep below 1.21.4 an item is drawn in a screen, in front of what was drawn before it
    private static final float ITEM_Z = 150.0f;
    // A block item's size in a slot against the slot: vanilla's block GUI scale
    private static final float BLOCK_GUI_SCALE = 0.625f;
    // The box renderer lifts a box in a screen by a pixel of the block, which the world view takes back
    private static final float BOX_LIFT = 1.0f / 16.0f;
    // The grass block of the world view, at the vanilla block GUI angle but baked at four times
    // its scale, as the creator's box models are, so that from 1.21.6 it is drawn at full size
    //? if >=1.21.4 {
    /*private static final net.minecraft.resources.ResourceLocation CREATOR_GRASS_MODEL =
            com.theplumteam.util.ResourceLocations.of(com.theplumteam.BlockPopsMod.MOD_ID, "creator_grass_block");
    *///? }

    protected final List<UiNode> controls = new ArrayList<>();
    private final UiButton spinButton;
    private final UiButton lightButton;
    private boolean buttonsShown = true;
    private float yaw;
    private boolean spinning;
    private boolean night;
    private boolean dragging;
    private long lastFrame;
    @Nullable
    private UiNode pressedControl;

    protected UiViewport() {
        this.spinButton = new UiButton(CreatorScreen.tr("preview.rotate"), UiButton.Style.SECONDARY,
                () -> setSpinning(!this.spinning));
        this.lightButton = new UiButton(CreatorScreen.tr("preview.light_day"), UiButton.Style.SECONDARY,
                () -> setNight(!this.night));
        this.controls.add(this.spinButton);
        this.controls.add(this.lightButton);
    }

    // State

    /** How far the models are turned from their usual angle, in degrees from 0 to 360. */
    public float yaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = Float.isFinite(yaw) ? ((yaw % 360.0f) + 360.0f) % 360.0f : 0.0f;
    }

    public boolean isSpinning() {
        return spinning;
    }

    /** Keeps the models turning on their own, as the "Rotate" button does. */
    public void setSpinning(boolean spinning) {
        this.spinning = spinning;
        this.spinButton.setStyle(spinning ? UiButton.Style.PRIMARY : UiButton.Style.SECONDARY);
    }

    public boolean isNight() {
        return night;
    }

    public void setNight(boolean night) {
        this.night = night;
        this.lightButton.setLabel(CreatorScreen.tr(night ? "preview.light_night" : "preview.light_day"));
    }

    /**
     * Shows or hides the "Rotate" and "Light" buttons, for a view too small to give them a row;
     * dragging still turns the models.
     */
    public void setButtons(boolean shown) {
        this.buttonsShown = shown;
        if (!shown) {
            setSpinning(false);
            setNight(false);
        }
    }

    /** Whether the scene now shown can be turned and lit; the replica of the machine screen cannot. */
    protected boolean turnable() {
        return true;
    }

    // Drawing

    @Override
    public final void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        advanceSpin();
        layout();
        boolean overControl = controlAt(mouseX, mouseY) != null;
        int sceneX = overControl ? -1 : mouseX;
        int sceneY = overControl ? -1 : mouseY;
        UiScrollPane.clip(graphics, x, y, x + width, y + height);
        try {
            drawScene(graphics, sceneX, sceneY, partialTick);
            // The wash and the controls above the models and items of the scene
            GuiPose.flush(graphics);
            GuiPose.nextStratum(graphics);
            GuiPose.push(graphics);
            GuiPose.lift(graphics, 400.0f);
            try {
                if (night && turnable()) {
                    graphics.fill(x, y, x + width, y + height, NIGHT);
                }
                drawOverlay(graphics, sceneX, sceneY, partialTick);
                for (UiNode control : controls) {
                    if (control.visible()) {
                        control.draw(graphics, mouseX, mouseY, partialTick);
                    }
                }
            } finally {
                GuiPose.pop(graphics);
            }
        } finally {
            graphics.disableScissor();
        }
        // Framed as the menus' wells are: a grey border inside a black outline
        Draw.outline(graphics, x, y, width, height, OUTLINE);
        Draw.outline(graphics, x + 1, y + 1, width - 2, height - 2, Surfaces.BORDER);
    }

    /** Draws the background and the models; the pointer is off the view while over a control. */
    protected abstract void drawScene(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /** Draws labels above the models and the night wash, below the controls. */
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * Places the controls for the view's bounds now; runs before every draw and click. This puts
     * "Rotate" and "Light" in the bottom right corner and shows them only while the scene turns.
     */
    protected void layout() {
        Font font = Minecraft.getInstance().font;
        boolean shown = turnable() && buttonsShown;
        int lightWidth = Math.max(UiButton.widthFor(font, CreatorScreen.tr("preview.light_day"), false),
                UiButton.widthFor(font, CreatorScreen.tr("preview.light_night"), false));
        int spinWidth = UiButton.widthFor(font, CreatorScreen.tr("preview.rotate"), false);
        int buttonY = y + height - INSET - UiButton.SMALL_HEIGHT;
        int lightX = x + width - INSET - lightWidth;
        lightButton.at(lightX, buttonY, lightWidth, UiButton.SMALL_HEIGHT);
        spinButton.at(lightX - 4 - spinWidth, buttonY, spinWidth, UiButton.SMALL_HEIGHT);
        lightButton.setVisible(shown);
        spinButton.setVisible(shown);
    }

    /** The left edge of the "Rotate" button, for a label that has to stay clear of it. */
    protected int buttonsLeft() {
        return spinButton.visible() ? spinButton.bounds().x() : x + width;
    }

    /** The top of the "Rotate" and "Light" buttons, or the view's inner bottom while they are hidden. */
    protected int buttonsTop() {
        return spinButton.visible() ? spinButton.bounds().y() : y + height - INSET;
    }

    // The models turn on their own while "Rotate" is on, except while dragged
    private void advanceSpin() {
        long now = Util.getMillis();
        long elapsed = lastFrame == 0 ? 0 : Math.min(MAX_FRAME_MS, Math.max(0, now - lastFrame));
        lastFrame = now;
        if (spinning && !dragging && turnable()) {
            setYaw(yaw + SPIN_DEGREES_PER_SECOND * elapsed / 1000.0f);
        }
    }

    @Nullable
    private UiNode controlAt(double mouseX, double mouseY) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            UiNode control = controls.get(i);
            if (control.visible() && control.isMouseOver(mouseX, mouseY)) {
                return control;
            }
        }
        return null;
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        if (!visible() || !bounds().contains(click.x(), click.y())) {
            return false;
        }
        layout();
        pressedControl = null;
        for (int i = controls.size() - 1; i >= 0; i--) {
            UiNode control = controls.get(i);
            if (control.visible() && control.click(click)) {
                pressedControl = control;
                return true;
            }
        }
        if (clickScene(click)) {
            return true;
        }
        if (!click.left() || !turnable()) {
            return false;
        }
        if (click.doubleClick()) {
            // Back to the usual angle
            setYaw(0);
        }
        dragging = true;
        return true;
    }

    /** A click on the scene rather than on a control; true when the scene used it. */
    protected boolean clickScene(UiInput.Click click) {
        return false;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        if (pressedControl != null) {
            return pressedControl.drag(click, dragX, dragY);
        }
        if (dragging) {
            setYaw(yaw + (float) dragX * DEGREES_PER_UNIT);
            return true;
        }
        return dragScene(click, dragX, dragY);
    }

    protected boolean dragScene(UiInput.Click click, double dragX, double dragY) {
        return false;
    }

    @Override
    public boolean release(UiInput.Click click) {
        UiNode control = pressedControl;
        pressedControl = null;
        if (control != null) {
            return control.release(click);
        }
        if (dragging) {
            dragging = false;
            return true;
        }
        return releaseScene(click);
    }

    protected boolean releaseScene(UiInput.Click click) {
        return false;
    }

    // Shared drawing helpers

    /** The collection's background colour, opaque, or a fallback for one without. */
    protected static int collectionColour(@Nullable FigureCollection collection, int fallback) {
        int[] rgb = collection != null ? collection.getBackgroundColor() : null;
        if (rgb == null || rgb.length != 3) {
            return fallback;
        }
        return 0xFF000000 | (rgb[0] & 0xFF) << 16 | (rgb[1] & 0xFF) << 8 | (rgb[2] & 0xFF);
    }

    /** A colour mixed toward black, keeping a share of it from 0 to 1. */
    protected static int darken(int colour, float keep) {
        int red = (int) (((colour >> 16) & 0xFF) * keep);
        int green = (int) (((colour >> 8) & 0xFF) * keep);
        int blue = (int) ((colour & 0xFF) * keep);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * Draws one figure through an entry, in a square cell whose top left corner is given. The
     * entry clips the figure to the cell itself, and before 1.21.4 such a clip ignores moved
     * drawing, so inside a scrolled pane the figure is drawn with the scroll taken back.
     */
    public static void drawFigure(GuiGraphics graphics, FigureEntry entry, FigureDefinition figure, int cellX,
                                     int cellY, int size, float partialTick, boolean greyed) {
        //? if <1.21.4 {
        int shift = UiScrollPane.drawingShift();
        if (shift != 0) {
            GuiPose.push(graphics);
            GuiPose.translate(graphics, 0, shift);
            entry.renderPreview(graphics, figure, cellX, cellY - shift, size, partialTick, greyed);
            GuiPose.pop(graphics);
            return;
        }
        //? }
        entry.renderPreview(graphics, figure, cellX, cellY, size, partialTick, greyed);
    }

    /**
     * A box of a collection, closed, holding a figure when one is named, as the machine hands it
     * out. From 1.21.4 it is drawn through the creator's own box model, which leaves room for any
     * turn; {@code world} picks the model at the vanilla block angle over the one seen from the front.
     */
    public static ItemStack boxStack(String collectionId, @Nullable String figureId, boolean world) {
        return boxStack(collectionId, figureId, world, 0);
    }

    /**
     * As above, with the figure wearing one of its skins: 0 its own, 1 onwards its variants, as
     * a placed box keeps the one it was turned to.
     */
    public static ItemStack boxStack(String collectionId, @Nullable String figureId, boolean world, int skin) {
        ItemStack stack = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
        //? if >=1.21.4 {
        /*stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                world ? BoxBlockItemRenderer.CREATOR_BOX_WORLD_MODEL : BoxBlockItemRenderer.CREATOR_BOX_MODEL);
        *///? }
        CompoundTag tag = new CompoundTag();
        tag.putString("CollectionId", collectionId);
        if (figureId != null) {
            tag.putString("FigureId", figureId);
        }
        tag.putBoolean("IsFigureExtracted", false);
        if (skin > 0) {
            tag.putInt("AlternativeSkinIndex", skin);
        }
        BlockEntityItemData.write(stack, tag, "blockpops:box_block");
        return stack;
    }

    /** The grass block the world view stands a box on. */
    protected static ItemStack grassStack() {
        ItemStack stack = new ItemStack(Items.GRASS_BLOCK);
        //? if >=1.21.4 {
        /*stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, CREATOR_GRASS_MODEL);
        *///? }
        return stack;
    }

    /**
     * Draws a box from {@link #boxStack} seen from the front and a little above, as the favorite
     * colour screen shows boxes, turned by a yaw and centred on a point, as big as an item drawn
     * at {@code size}.
     */
    public static void drawBoxFront(GuiGraphics graphics, ItemStack box, float yaw, int pose, float centerX,
                                       float centerY, float size) {
        BoxBlockItemRenderer.setView(box, yaw, pose);
        //? if >=1.21.4 {
        /*// The model bakes the angle and four times the size, so the deferred interface draws
        // it at full resolution; the size is taken back here
        drawItemCentred(graphics, box, centerX, centerY, size / 4.0f);
        *///? } else {
        drawItemTurned(graphics, box, centerX, centerY, ITEM_Z, size, FRONT_PITCH, FRONT_YAW);
        //? }
    }

    /**
     * Draws a box from {@link #boxStack} at the vanilla block angle and size, standing on a block
     * item drawn at the same size whose centre is given, as a box placed on the ground looks.
     *
     * @return the top of the drawing, for a label above it
     */
    protected static float drawBoxOnBlock(GuiGraphics graphics, ItemStack box, ItemStack block, float yaw, int pose,
                                          float centerX, float blockCenterY, float size) {
        // One block up, seen at the vanilla 30 degrees from above, less the box's own lift
        float unit = size * BLOCK_GUI_SCALE;
        float boxCenterY = blockCenterY - unit * (float) Math.cos(Math.toRadians(30.0)) * (1.0f - BOX_LIFT);
        BoxBlockItemRenderer.setView(box, yaw, pose);
        //? if >=1.21.4 {
        /*drawItemCentred(graphics, block, centerX, blockCenterY, size / 4.0f);
        drawItemCentred(graphics, box, centerX, boxCenterY, size / 4.0f);
        *///? } else {
        drawItemCentred(graphics, block, centerX, blockCenterY, size);
        // The box is drawn wholly in front of the block's depth, as it stands on top of it
        drawItemTurned(graphics, box, centerX, boxCenterY, ITEM_Z + size, size * BLOCK_GUI_SCALE, BLOCK_PITCH,
                BLOCK_YAW);
        //? }
        // A block at that angle reaches a little over half its size above its centre
        return boxCenterY - unit * 0.79f;
    }

    // An item as big as one drawn at that size, centred on a point
    private static void drawItemCentred(GuiGraphics graphics, ItemStack stack, float centerX, float centerY,
                                        float size) {
        GuiPose.push(graphics);
        GuiPose.translate(graphics, centerX, centerY);
        float scale = size / 16.0f;
        GuiPose.scale(graphics, scale, scale);
        GuiPose.translate(graphics, -8, -8);
        graphics.renderItem(stack, 0, 0);
        GuiPose.pop(graphics);
    }

    //? if <1.21.4 {
    // Below 1.21.4 the screen's pose is three-dimensional, so an item is simply turned before it
    // is drawn: about X then Y, in degrees, as the item's own display angles would turn it. The
    // item is drawn 8 units right and down and ITEM_Z deep from where it is placed, which is
    // taken back first, so it turns about its own centre and sits at that depth.
    private static void drawItemTurned(GuiGraphics graphics, ItemStack stack, float centerX, float centerY,
                                       float depth, float size, float pitch, float yaw) {
        com.mojang.blaze3d.vertex.PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, depth);
        pose.mulPose(new org.joml.Quaternionf().rotationXYZ((float) Math.toRadians(pitch),
                (float) Math.toRadians(yaw), 0.0f));
        float scale = size / 16.0f;
        pose.scale(scale, scale, scale);
        pose.translate(-8.0f, -8.0f, -ITEM_Z);
        graphics.renderItem(stack, 0, 0);
        pose.popPose();
    }
    //? }

    /** A line of text with a dark backing, readable over any scene. */
    protected static void label(GuiGraphics graphics, Font font, Component text, int x, int y, int colour) {
        int width = font.width(text);
        graphics.fill(x - 2, y - 2, x + width + 2, y + 9, 0xA0000000);
        Draw.string(graphics, font, text, x, y, colour);
    }
}
