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
 * Uses direct item rendering (1.21.5 approach) instead of PiP to avoid GPU texture conflicts.
 */
public class ColorSelectionButton extends Button {
    private final PopBlockColor color;
    private final FavoriteColorSelectionScreen parentScreen;
    private ItemStack boxItem; // Not final - needs to be recreated when transforms change
    private boolean isSelected = false;
    private boolean showFigure = true;

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

        // Create the initial box item
        rebuildBoxItem();

        // Configure component data to show the player inside the box
        CompoundTag blockEntityTag = new CompoundTag();

        blockEntityTag.putBoolean("HideLogo", true);
        blockEntityTag.putString("Color", color.name());

        // Set the figure to be the current player
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

        // Store custom rotation/scale for the item renderer
        blockEntityTag.putFloat("CustomRotationX", rotationX);
        blockEntityTag.putFloat("CustomRotationY", rotationY);
        blockEntityTag.putFloat("CustomRotationZ", rotationZ);
        blockEntityTag.putFloat("CustomScale", scale);

        this.boxItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));
    }

    /**
     * Rebuilds the box item with current transformation values
     */
    private void rebuildBoxItem() {
        this.boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());

        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putBoolean("HideLogo", true);
        blockEntityTag.putString("Color", color.name());

        if (showFigure) {
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
            blockEntityTag.putString("CollectionId", "");
            blockEntityTag.putString("FigureId", "");
        }

        // Store custom transformations
        blockEntityTag.putFloat("CustomRotationX", rotationX);
        blockEntityTag.putFloat("CustomRotationY", rotationY);
        blockEntityTag.putFloat("CustomRotationZ", rotationZ);
        blockEntityTag.putFloat("CustomScale", scale);

        this.boxItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));
    }

    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }

    public PopBlockColor getColor() {
        return color;
    }

    public void setTransforms(float rotX, float rotY, float rotZ, float scale, float offX, float offY, float offZ, float translateYRatio, float camRotX) {
        this.rotationX = rotX;
        this.rotationY = rotY;
        this.rotationZ = rotZ;
        this.scale = scale;
        this.offsetX = offX;
        this.offsetY = offY;
        this.offsetZ = offZ;

        // Rebuild the item with new transformations
        rebuildBoxItem();
    }

    public void setShowFigure(boolean showFigure) {
        this.showFigure = showFigure;
        // Rebuild the item with new figure visibility
        rebuildBoxItem();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        // Determine colors based on state
        int backgroundColor;
        int borderColor;

        if (isSelected) {
            backgroundColor = 0x80FFFFFF;
            borderColor = 0xFFFFFFFF;
        } else if (isHoveredOrFocused()) {
            backgroundColor = 0x60FFFFFF;
            borderColor = 0x80FFFFFF;
        } else {
            backgroundColor = 0x40FFFFFF;
            borderColor = 0x60FFFFFF;
        }

        // Draw background
        graphics.fill(getX(), getY(), getX() + width, getY() + height, backgroundColor);

        // Draw border (thicker for selected)
        if (isSelected) {
            graphics.fill(getX(), getY(), getX() + width, getY() + 2, borderColor);
            graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, borderColor);
            graphics.fill(getX(), getY() + 2, getX() + 2, getY() + height - 2, borderColor);
            graphics.fill(getX() + width - 2, getY() + 2, getX() + width, getY() + height - 2, borderColor);
        } else {
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, borderColor);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, borderColor);
            graphics.fill(getX(), getY() + 1, getX() + 1, getY() + height - 1, borderColor);
            graphics.fill(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1, borderColor);
        }

        // Don't use scissor - it clips the scaled items
        // Calculate item position in button center
        int itemX = getX() + (width - 16) / 2;
        int itemY = getY() + (height - 16) / 2;

        // Render the item - BoxBlockItemRenderer reads CustomRotationX/Y/Z and CustomScale from NBT
        graphics.renderItem(boxItem, itemX, itemY);
    }
}
