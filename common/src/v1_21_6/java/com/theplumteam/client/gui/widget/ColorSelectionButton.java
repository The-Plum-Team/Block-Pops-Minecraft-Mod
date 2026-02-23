package com.theplumteam.client.gui.widget;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.renderer.BoxWidgetRenderer;
import com.theplumteam.client.renderer.ItemPipRenderState;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Custom button widget that displays a colored box item and allows the player to select it.
 * Uses the PiP (Picture-in-Picture) deferred rendering system for crisp 3D rendering.
 * Each color gets its own GPU texture via ItemPipRenderer's per-color texture pool.
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
    private float translateYRatio = 0.0f;
    private float camRotX = 0.0f;

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
        this.translateYRatio = translateYRatio;
        this.camRotX = camRotX;

        rebuildBoxItem();
    }

    public void setShowFigure(boolean showFigure) {
        this.showFigure = showFigure;
        rebuildBoxItem();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        // 2. Submit to PiP deferred rendering system (crisp 3D, per-color textures)
        BoxBlockEntity renderEntity = BoxWidgetRenderer.getOrCreateRenderEntity(color, showFigure);
        if (renderEntity != null) {
            // Enable scissor to clip rendering to the button area
            graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);

            // Get the current scissor area for bounds computation
            ScreenRectangle scissorArea = peekScissorArea(graphics);

            // Scale: width * scale maps model units to GUI pixels
            float pipScale = width * scale;

            // Create PiP render state with entity, rotations, bounds, and scale
            ItemPipRenderState renderState = new ItemPipRenderState(
                renderEntity, color, showFigure,
                rotationX, rotationY, rotationZ,
                offsetX, offsetY, camRotX,
                getX(), getY(), getX() + width, getY() + height,
                pipScale, translateYRatio, scissorArea
            );

            // Submit to the deferred PiP rendering system
            submitPipState(graphics, renderState);

            graphics.disableScissor();
        }
    }

    // Cached reflection fields for accessing GuiGraphics internals
    private static java.lang.reflect.Field guiRenderStateField;
    private static java.lang.reflect.Field scissorStackField;
    private static java.lang.reflect.Method scissorPeekMethod;
    private static boolean reflectionInitialized = false;

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            guiRenderStateField = GuiGraphics.class.getDeclaredField("guiRenderState");
            guiRenderStateField.setAccessible(true);
            scissorStackField = GuiGraphics.class.getDeclaredField("scissorStack");
            scissorStackField.setAccessible(true);
        } catch (Exception e) {
            com.theplumteam.BlockPopsMod.LOGGER.warn("ColorSelectionButton: Failed to initialize reflection for PiP rendering", e);
        }
    }

    private static ScreenRectangle peekScissorArea(GuiGraphics graphics) {
        initReflection();
        try {
            if (scissorStackField != null) {
                Object scissorStack = scissorStackField.get(graphics);
                if (scissorPeekMethod == null) {
                    scissorPeekMethod = scissorStack.getClass().getDeclaredMethod("peek");
                    scissorPeekMethod.setAccessible(true);
                }
                ScreenRectangle result = (ScreenRectangle) scissorPeekMethod.invoke(scissorStack);
                if (result != null) return result;
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return ScreenRectangle.empty();
    }

    private static void submitPipState(GuiGraphics graphics, PictureInPictureRenderState state) {
        initReflection();
        try {
            if (guiRenderStateField != null) {
                GuiRenderState guiRenderState = (GuiRenderState) guiRenderStateField.get(graphics);
                guiRenderState.submitPicturesInPictureState(state);
            }
        } catch (Exception e) {
            com.theplumteam.BlockPopsMod.LOGGER.error("ColorSelectionButton: Failed to submit PiP state", e);
        }
    }
}
