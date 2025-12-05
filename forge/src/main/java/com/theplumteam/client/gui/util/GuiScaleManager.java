package com.theplumteam.client.gui.util;

import com.theplumteam.BlockPopsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

/**
 * Manages GUI scaling for BlockPops menus.
 * When shaders are active, uses inverse scale transformation instead of resizeDisplay() to avoid freezing.
 */
public class GuiScaleManager {
    private static Integer originalGuiScale = null;
    private static boolean scaleChanged = false;
    private static boolean usingInverseScale = false;

    // Target GUI scale for BlockPops menus
    private static final int TARGET_GUI_SCALE = 2;

    /**
     * Force set the GUI scale for the BlockPops menus.
     * If shaders are detected, this will use inverse scaling instead of resizeDisplay().
     * @param targetScale The desired GUI scale (1-4, where 0 = Auto)
     * @return true if a screen resize was triggered, false otherwise.
     */
    public static boolean setMenuGuiScale(int targetScale) {
        try {
            // Check if shaders are active - if so, use inverse scaling approach
            if (areShadersActive()) {
                BlockPopsMod.LOGGER.info("Shaders detected, using inverse scale transformation");
                usingInverseScale = true;
                return false; // No resize triggered, will use inverse scale in render
            }

            usingInverseScale = false;
            Minecraft mc = Minecraft.getInstance();
            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();

            // Store the original scale if we haven't already
            if (originalGuiScale == null) {
                originalGuiScale = guiScaleOption.get();
            }

            // Only change if different from current
            int currentScale = guiScaleOption.get();
            if (currentScale != targetScale) {
                guiScaleOption.set(targetScale);
                scaleChanged = true;

                // Force window to recalculate scaled dimensions
                mc.resizeDisplay();

                return true;
            }
        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to set menu GUI scale", e);
        }
        return false;
    }

    /**
     * Restore the original GUI scale
     */
    public static void restoreOriginalGuiScale() {
        try {
            usingInverseScale = false;

            if (originalGuiScale == null || !scaleChanged) {
                return; // Nothing to restore
            }

            // Skip resize if shaders are active (we didn't change scale anyway)
            if (areShadersActive()) {
                originalGuiScale = null;
                scaleChanged = false;
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();
            int currentScale = guiScaleOption.get();

            if (currentScale != originalGuiScale) {
                guiScaleOption.set(originalGuiScale);
                mc.resizeDisplay();
            }

            // Reset tracking variables
            originalGuiScale = null;
            scaleChanged = false;

        } catch (Exception e) {
            BlockPopsMod.LOGGER.error("Failed to restore original GUI scale", e);
        }
    }

    /**
     * Check if we're using inverse scale mode (shaders are active)
     */
    public static boolean isUsingInverseScale() {
        return usingInverseScale;
    }

    /**
     * Get the render scale factor to apply in pose.scale().
     * This transforms virtual coordinates (as if GUI scale was TARGET_GUI_SCALE)
     * to actual GUI coordinates (current GUI scale).
     *
     * Formula: targetScale / currentScale
     * - At GUI scale 4: 2/4 = 0.5 (shrink to fit smaller coordinate space)
     * - At GUI scale 1: 2/1 = 2.0 (expand to fit larger coordinate space)
     */
    public static float getRenderScaleFactor() {
        Minecraft mc = Minecraft.getInstance();
        double currentGuiScale = mc.getWindow().getGuiScale();
        if (currentGuiScale <= 0) currentGuiScale = 1;
        return (float) (TARGET_GUI_SCALE / currentGuiScale);
    }

    /**
     * Get the virtual width when using inverse scale.
     * This is what the screen width would be if GUI scale was TARGET_GUI_SCALE.
     */
    public static int getVirtualWidth() {
        Minecraft mc = Minecraft.getInstance();
        return (int) (mc.getWindow().getWidth() / TARGET_GUI_SCALE);
    }

    /**
     * Get the virtual height when using inverse scale.
     * This is what the screen height would be if GUI scale was TARGET_GUI_SCALE.
     */
    public static int getVirtualHeight() {
        Minecraft mc = Minecraft.getInstance();
        return (int) (mc.getWindow().getHeight() / TARGET_GUI_SCALE);
    }

    /**
     * Transform mouse X coordinate for inverse scale mode.
     * Converts from current GUI-scaled coordinates to virtual coordinates.
     *
     * Formula: mouseX * (currentScale / targetScale)
     * - At GUI scale 4: center click (x=240) -> 240 * (4/2) = 480 (virtual center)
     * - At GUI scale 1: center click (x=960) -> 960 * (1/2) = 480 (virtual center)
     */
    public static double transformMouseX(double mouseX) {
        if (!usingInverseScale) return mouseX;
        Minecraft mc = Minecraft.getInstance();
        double currentGuiScale = mc.getWindow().getGuiScale();
        if (currentGuiScale <= 0) currentGuiScale = 1;
        return mouseX * (currentGuiScale / TARGET_GUI_SCALE);
    }

    /**
     * Transform mouse Y coordinate for inverse scale mode.
     * Converts from current GUI-scaled coordinates to virtual coordinates.
     */
    public static double transformMouseY(double mouseY) {
        if (!usingInverseScale) return mouseY;
        Minecraft mc = Minecraft.getInstance();
        double currentGuiScale = mc.getWindow().getGuiScale();
        if (currentGuiScale <= 0) currentGuiScale = 1;
        return mouseY * (currentGuiScale / TARGET_GUI_SCALE);
    }

    /**
     * Get mouse scale factor for drag operations.
     */
    public static float getMouseScaleFactor() {
        Minecraft mc = Minecraft.getInstance();
        double currentGuiScale = mc.getWindow().getGuiScale();
        if (currentGuiScale <= 0) currentGuiScale = 1;
        return (float) (currentGuiScale / TARGET_GUI_SCALE);
    }

    /**
     * Get the optimal GUI scale for the BlockPops menu.
     */
    public static int getOptimalMenuScale() {
        return TARGET_GUI_SCALE;
    }

    /**
     * Check if shaders are currently active (Optifine or Iris).
     */
    public static boolean areShadersActive() {
        try {
            if (isOptifineShaderActive()) {
                return true;
            }
            if (isIrisShaderActive()) {
                return true;
            }
        } catch (Exception e) {
            BlockPopsMod.LOGGER.debug("Error checking for shaders: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check if Optifine shaders are active
     */
    private static boolean isOptifineShaderActive() {
        try {
            Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
            java.lang.reflect.Field shaderPackLoadedField = shadersClass.getDeclaredField("shaderPackLoaded");
            shaderPackLoadedField.setAccessible(true);
            return shaderPackLoadedField.getBoolean(null);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            try {
                Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
                java.lang.reflect.Method isShaderPackLoaded = shadersClass.getMethod("isShaderPackLoaded");
                return (Boolean) isShaderPackLoaded.invoke(null);
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /**
     * Check if Iris shaders are active
     */
    private static boolean isIrisShaderActive() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            java.lang.reflect.Method getInstance = irisApiClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            java.lang.reflect.Method isShaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");
            return (Boolean) isShaderPackInUse.invoke(instance);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}