package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.BoxWidgetRenderer;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Custom button widget that displays a colored box item and allows the player to select it.
 * Uses oversized_in_gui: true in item models to allow rendering beyond the 16x16 box.
 */
public class ColorSelectionButton extends Button {
    private final PopBlockColor color;
    private final FavoriteColorSelectionScreen parentScreen;
    private ItemStack boxItem;
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

        rebuildBoxItem();
    }

    /**
     * Rebuilds the box item with current transformation values.
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

        // Store custom transformations (scale is now applied internally with oversized_in_gui: true)
        blockEntityTag.putFloat("CustomRotationX", rotationX);
        blockEntityTag.putFloat("CustomRotationY", rotationY);
        blockEntityTag.putFloat("CustomRotationZ", rotationZ);
        blockEntityTag.putFloat("CustomScale", scale);
        blockEntityTag.putFloat("CustomOffsetX", offsetX);
        blockEntityTag.putFloat("CustomOffsetY", offsetY);
        blockEntityTag.putFloat("CustomOffsetZ", offsetZ);

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

        rebuildBoxItem();
    }

    public void setShowFigure(boolean showFigure) {
        this.showFigure = showFigure;
        rebuildBoxItem();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        // 1. Draw Background & Border
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

        graphics.fill(getX(), getY(), getX() + width, getY() + height, backgroundColor);

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

        // 2. Direct Entity Rendering (Bypass ItemRenderer)

        // Get the cached renderer and entity for this specific color
        // This uses your existing caching infrastructure in BoxWidgetRenderer
        BoxBlockEntity renderEntity = BoxWidgetRenderer.getOrCreateRenderEntity(color, showFigure);
        BoxBlockRenderer renderer = BoxWidgetRenderer.getRenderer(color, showFigure);

        if (renderEntity != null && renderer != null) {
            // Apply transformations stored in the button
            // Note: We don't read from NBT here, we use the fields directly from this class

            PoseStack pose = graphics.pose();
            pose.pushPose();

            // Translate to center of button
            pose.translate(getX() + width / 2.0, getY() + height / 2.0, 150.0);

            // Setup Lighting for UI Entity rendering
            // 1.21.6+ equivalent of setupForFlatItems/setupFor3DItems
            graphics.flush(); // Ensure background draws before changing lighting
            Lighting.setupForEntityInUi();

            // Apply Position Offsets (X/Y inverted for screen coords if needed, usually Z is depth)
            // Standard block scale in UI is often ~16, so 30.0f makes it "oversized"
            float uiScale = this.scale * 16.0f;

            // Flip Y to match block coordinate system (Up is positive in Blockbench, Down is positive in GUI)
            pose.scale(uiScale, -uiScale, uiScale);

            // Apply rotations
            pose.mulPose(new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rotationX),
                (float) Math.toRadians(rotationY),
                (float) Math.toRadians(rotationZ)
            ));

            // Apply offsets (Scaled by the UI scale)
            pose.translate(offsetX, offsetY, offsetZ);

            // Get buffers
            MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

            // Render via GeckoLib
            renderer.render(
                renderEntity,
                partialTick,
                pose,
                bufferSource,
                15728880, // Full bright (packedLight)
                OverlayTexture.NO_OVERLAY,
                Vec3.ZERO
            );

            // Force draw to flush the geometry immediately
            bufferSource.endBatch();

            // Restore Lighting
            Lighting.setupFor3DItems(); // Reset to standard item lighting for other UI elements

            pose.popPose();
        }
    }
}
