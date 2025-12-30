package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.joml.Quaternionf;

/**
 * Custom button widget that displays a colored box item and allows the player to select it.
 * Used in the FavoriteColorSelectionScreen to present the 16 color options in a grid.
 */
public class ColorSelectionButton extends Button {
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
        // Add component data to hide the logo in the color selection screen and set the color
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putBoolean("HideLogo", true);
        blockEntityTag.putString("Color", color.name());
        this.boxItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));
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

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        // Determine colors based on state
        int backgroundColor;
        int borderColor;

        if (isSelected) {
            // Bright highlight for selected
            backgroundColor = 0x80FFFFFF;
            borderColor = 0xFFFFFFFF;
        } else if (isHoveredOrFocused()) {
            // Subtle highlight for hover
            backgroundColor = 0x60FFFFFF;
            borderColor = 0x80FFFFFF;
        } else {
            // Normal state
            backgroundColor = 0x40FFFFFF;
            borderColor = 0x60FFFFFF;
        }

        // Draw background
        graphics.fill(getX(), getY(), getX() + width, getY() + height, backgroundColor);

        // Draw border (thicker for selected)
        if (isSelected) {
            // Double border for selected
            graphics.fill(getX(), getY(), getX() + width, getY() + 2, borderColor);
            graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, borderColor);
            graphics.fill(getX(), getY() + 2, getX() + 2, getY() + height - 2, borderColor);
            graphics.fill(getX() + width - 2, getY() + 2, getX() + width, getY() + height - 2, borderColor);
        } else {
            // Single border for normal/hovered
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, borderColor);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, borderColor);
            graphics.fill(getX(), getY() + 1, getX() + 1, getY() + height - 1, borderColor);
            graphics.fill(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1, borderColor);
        }

        // Draw the box item in the center with transformations
        int itemSize = (int) (width * 0.6f); // Item is 60% of button size
        int itemX = getX() + (width - itemSize) / 2;
        int itemY = getY() + (height - itemSize) / 2;

        // Apply transformations and render the item
        PoseStack pose = graphics.pose();
        pose.pushPose();

        // 1. Translate to the button center position
        pose.translate(itemX + itemSize / 2f, itemY + itemSize / 2f, 100);

        // 2. Apply position offsets (moves the rotation pivot point)
        pose.translate(offsetX, offsetY, offsetZ);

        // 3. Apply rotations (these rotate around the current position - the item's center)
        // Note: We apply rotations in the order that makes sense for 3D
        pose.mulPose(new Quaternionf().rotationXYZ(
            (float) Math.toRadians(rotationX),
            (float) Math.toRadians(rotationY),
            (float) Math.toRadians(rotationZ)
        ));

        // 4. Apply scale (scales from the current center point)
        float finalScale = (itemSize / 16f) * this.scale;
        pose.scale(finalScale, finalScale, finalScale);

        // 5. Translate to center the 16x16 item for rendering
        pose.translate(-8, -8, 0);

        // 6. Render the item
        graphics.renderItem(boxItem, 0, 0);
        pose.popPose();
    }
}
