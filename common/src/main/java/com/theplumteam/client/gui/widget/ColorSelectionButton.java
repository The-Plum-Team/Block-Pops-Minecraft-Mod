package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModItems;
import com.theplumteam.registry.ModSounds;
//? if >=1.21.4 {
/*import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import net.minecraft.core.component.DataComponents;
*///? }
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * Custom button widget that displays a colored box item and allows the player to select it.
 * Used in the FavoriteColorSelectionScreen to present the 16 color options in a grid, as tiles
 * in the menus' raised style ({@link Surfaces}).
 */
public class ColorSelectionButton extends Button {
    /** The selected tile's face, tinted with the blue of its border. */
    private static final int SELECTED_TINT = 0x50308CC0;

    private final PopBlockColor color;
    private final FavoriteColorSelectionScreen parentScreen;
    private final ItemStack boxItem;
    private boolean isSelected = false;

    // Transformation values
    private float rotationX = 30.0f;
    private float rotationY = 45.0f;
    private float rotationZ = 0.0f;
    private float scale = 1.0f;
    private float offsetX = 0.0f;
    private float offsetY = 0.0f;
    private float offsetZ = 0.0f;

    public ColorSelectionButton(int x, int y, int size, PopBlockColor color, FavoriteColorSelectionScreen parentScreen) {
        super(x, y, size, size, Component.empty(), button -> {
            if (parentScreen != null) {
                parentScreen.setSelectedColor(color);
            }
        }, DEFAULT_NARRATION);

        this.color = color;
        this.parentScreen = parentScreen;

        // Create the box item for this color
        this.boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
        //? if >=1.21.4 {
        /*this.boxItem.set(DataComponents.ITEM_MODEL, BoxBlockItemRenderer.FAVORITE_COLOR_MODEL);
        *///? }

        // Configure NBT to show the player inside the box
        CompoundTag blockEntityTag = BlockEntityItemData.read(this.boxItem);
        if (blockEntityTag == null) {
            blockEntityTag = new CompoundTag();
        }

        // 1. Existing settings: Set Color and Hide Logo
        blockEntityTag.putBoolean("HideLogo", true);
        blockEntityTag.putString("Color", color.name());

        // 2. NEW settings: Set the figure to be the current player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Set collection to World Players
            blockEntityTag.putString("CollectionId", "world_players");

            // Set FigureId to the player's UUID
            blockEntityTag.putString("FigureId", mc.player.getUUID().toString());

            // Ensure the figure is marked as inside the box (not extracted)
            blockEntityTag.putBoolean("IsFigureExtracted", false);

            // Optional: Reset offsets to ensure it centers correctly inside the item
            blockEntityTag.putDouble("FigureOffsetX", -0.53);
            blockEntityTag.putDouble("FigureOffsetY", 0.01);
            blockEntityTag.putDouble("FigureOffsetZ", -0.55);
            blockEntityTag.putDouble("FigureScale", 1.0);
        }
        BlockEntityItemData.write(this.boxItem, blockEntityTag, "blockpops:box_block");
    }

    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }

    public PopBlockColor getColor() {
        return color;
    }

    /**
     * Set the transformation values for rendering the box item
     */
    public void setTransforms(float rotX, float rotY, float rotZ, float scale, float offX, float offY, float offZ) {
        this.rotationX = rotX;
        this.rotationY = rotY;
        this.rotationZ = rotZ;
        this.scale = scale;
        this.offsetX = offX;
        this.offsetY = offY;
        this.offsetZ = offZ;
    }

    /**
     * Toggle whether the player figure is shown inside the box
     */
    public void setShowFigure(boolean showFigure) {
        CompoundTag blockEntityTag = BlockEntityItemData.read(this.boxItem);
        if (blockEntityTag == null) {
            blockEntityTag = new CompoundTag();
        }

        if (showFigure) {
            // Add figure-related NBT tags
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                blockEntityTag.putString("CollectionId", "world_players");
                blockEntityTag.putString("FigureId", mc.player.getUUID().toString());
                blockEntityTag.putBoolean("IsFigureExtracted", false);
                blockEntityTag.putDouble("FigureOffsetX", -0.53);
                blockEntityTag.putDouble("FigureOffsetY", 0.01);
                blockEntityTag.putDouble("FigureOffsetZ", -0.55);
                blockEntityTag.putDouble("FigureScale", 1.0);
            }
        } else {
            // Set empty values to hide the figure
            // (BoxBlockEntity.load() only updates fields if tag.contains() is true,
            // so we must set empty values rather than removing tags)
            blockEntityTag.putString("CollectionId", "");
            blockEntityTag.putString("FigureId", "");
        }
        BlockEntityItemData.write(this.boxItem, blockEntityTag, "blockpops:box_block");
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        UiSounds.play(soundManager, ModSounds.CLICK);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // A raised tile, as Quick Skin's skin tiles: lighter under the mouse, and the selected
        // one framed by a double blue border over a blue tint
        boolean hovered = isHoveredOrFocused();
        int left = getX();
        int top = getY();
        int right = left + width;
        int bottom = top + height;
        int border = isSelected ? Surfaces.SELECTED : hovered ? Surfaces.BORDER_HOVER : Surfaces.BORDER;
        int face = hovered ? Surfaces.FACE_HOVER : Surfaces.FACE;
        int faceBottom = Surfaces.raised(graphics, left, top, right, bottom, border, face,
                Surfaces.LIP, Surfaces.LIP_HEIGHT);
        if (isSelected) {
            Surfaces.outline(graphics, left + 2, top + 2, width - 4, faceBottom - 1 - (top + 2), Surfaces.SELECTED);
            graphics.fill(left + 3, top + 3, right - 3, faceBottom - 2, SELECTED_TINT);
        }

        // Draw the box item in the middle of the face, above the lip
        int itemSize = (int) (width * 0.6f); // Item is 60% of button size
        int itemX = left + (width - itemSize) / 2;
        int itemY = (top + 2 + faceBottom - 1 - itemSize) / 2;

        // Apply transformations and render the item.
        float finalScale = (itemSize / 16f) * this.scale;
        //? if >=1.21.4 {
        /*// The dedicated preview model owns the canonical camera. Legacy offsets
        // compensate the old GUI depth transform and do not apply to modern models.
        // Its baked scale allocates a full-resolution target in the deferred GUI;
        // cancel that scale here instead of enlarging a tiny atlas thumbnail.
        float screenScale = finalScale / 4f;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, itemX + itemSize / 2f, itemY + itemSize / 2f);
        GuiPose.scale(graphics, screenScale, screenScale);
        GuiPose.translate(graphics, -8, -8);
        graphics.renderItem(boxItem, 0, 0);
        GuiPose.pop(graphics);
        *///? } else {
        PoseStack pose = graphics.pose();
        pose.pushPose();

        // 1. Translate to the button center position
        pose.translate(itemX + itemSize / 2f, itemY + itemSize / 2f, 100);

        // 2. Apply position offsets (moves the rotation pivot point)
        pose.translate(offsetX, offsetY, offsetZ);

        // 3. Apply rotations (these rotate around the current position - the item's center)
        pose.mulPose(new Quaternionf().rotationXYZ(
            (float) Math.toRadians(rotationX),
            (float) Math.toRadians(rotationY),
            (float) Math.toRadians(rotationZ)
        ));

        // 4. Apply scale (scales from the current center point)
        pose.scale(finalScale, finalScale, finalScale);

        // 5. Translate to center the 16x16 item for rendering
        pose.translate(-8, -8, 0);

        // 6. Render the item
        // The BoxBlockItemRenderer will read the NBT we set above and render the player inside
        graphics.renderItem(boxItem, 0, 0);
        pose.popPose();
        //? }
    }
}
