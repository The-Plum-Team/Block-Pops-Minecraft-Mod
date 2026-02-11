package com.theplumteam.client.gui.widget;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.renderer.BoxWidgetRenderer;
import com.theplumteam.client.renderer.ItemPipRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.network.chat.Component;

/**
 * Custom button widget that displays a colored box using the PiP (Picture-in-Picture)
 * deferred rendering system. Each color gets its own off-screen GPU texture rendered
 * at native resolution, avoiding both the 16x16 clipping and pixelation issues.
 */
public class ColorSelectionButton extends Button {
    private final PopBlockColor color;
    private final FavoriteColorSelectionScreen parentScreen;
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
    private float translateYRatio = 0.74f;
    private float camRotX = 18.5f;

    // Reflection for PiP state submission (shared across all instances)
    private static java.lang.reflect.Field guiRenderStateField;
    private static java.lang.reflect.Field scissorStackField;
    private static java.lang.reflect.Method scissorPeekMethod;
    private static boolean reflectionInitialized = false;

    public ColorSelectionButton(int x, int y, int size, PopBlockColor color, FavoriteColorSelectionScreen parentScreen) {
        super(x, y, size, size, Component.empty(), button -> {
            if (parentScreen != null) {
                parentScreen.setSelectedColor(color);
            }
        }, DEFAULT_NARRATION);

        this.color = color;
        this.parentScreen = parentScreen;

        // Ensure the per-color render entity is created
        BoxWidgetRenderer.getOrCreateRenderEntity(color, showFigure);
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
    }

    public void setShowFigure(boolean showFigure) {
        this.showFigure = showFigure;
        // Re-create render entity with updated figure visibility
        BoxWidgetRenderer.getOrCreateRenderEntity(color, showFigure);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        // Render the box using the PiP deferred rendering system (native resolution)
        renderBoxPip(graphics);
    }

    /**
     * Submits an ItemPipRenderState to the PiP deferred rendering system.
     * Each color gets its own off-screen GPU texture, rendered at native resolution.
     */
    private void renderBoxPip(GuiGraphics graphics) {
        BoxBlockEntity renderEntity = BoxWidgetRenderer.getOrCreateRenderEntity(color, showFigure);
        if (renderEntity == null) return;

        int x0 = getX();
        int y0 = getY();
        int x1 = getX() + width;
        int y1 = getY() + height;

        // Enable scissor to clip to button bounds
        graphics.enableScissor(x0, y0, x1, y1);

        ScreenRectangle scissorArea = peekScissorArea(graphics);

        // PiP scale: button size * scale factor maps model units to GUI pixels
        float pipScale = Math.min(width, height) * scale;

        ItemPipRenderState renderState = new ItemPipRenderState(
            renderEntity,
            color,
            showFigure,
            rotationX,
            rotationY,
            rotationZ,
            offsetX,
            offsetY,
            camRotX,
            x0, y0, x1, y1,
            pipScale,
            translateYRatio,
            scissorArea
        );

        submitPipState(graphics, renderState);

        graphics.disableScissor();
    }

    // --- Reflection helpers (same pattern as FigureEntry) ---

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
