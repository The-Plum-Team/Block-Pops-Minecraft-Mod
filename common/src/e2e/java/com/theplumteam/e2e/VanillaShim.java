package com.theplumteam.e2e;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.function.Consumer;

public final class VanillaShim {
    private VanillaShim() {}

    public static Screen currentScreen(Minecraft minecraft) {
        return minecraft.screen;
    }

    /**
     * Whether a loading overlay is still drawn over everything else.
     *
     * The vanilla overlay keeps fading for a moment after the world is ready and
     * composites over the whole frame, so a capture taken then measures the splash
     * instead of the screen. 26.1 removed the accessor along with the overlay it
     * reported, and there is nothing left to wait for.
     */
    public static boolean overlayPresent(Minecraft minecraft) {
        //? if >=26 {
        /*return false;
        *///? } else {
        return minecraft.getOverlay() != null;
        //? }
    }

    /**
     * Drop every visible and queued toast before a capture settles.
     *
     * Joining the offline test server raises vanilla's insecure-chat and social
     * interactions toasts at timing-dependent moments, so they would otherwise land
     * in a different place in every run and keep captures from ever being
     * byte-identical to their reference. 1.21.2 renamed the toast component and
     * 26.2 moved it under the in-game GUI, as Quick Skin's harness also found.
     */
    public static void clearToasts(Minecraft minecraft) {
        //? if >=26.2 {
        /*minecraft.gui.toastManager().clear();
        *///? } elif >=1.21.2 {
        /*minecraft.getToastManager().clear();
        *///? } else {
        minecraft.getToasts().clear();
        //? }
    }

    public static boolean isWarningOrErrorScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("loadingerror")
                || name.contains("errorscreen")
                || name.contains("warning");
    }

    public static boolean screenshot(Minecraft minecraft, String basename) {
        try {
            Class<?> screenshot = loadNamedClass("net.minecraft.client.Screenshot");
            File gameDirectory = new File(System.getProperty("user.dir"));
            //? if >=26.2 {
            /*// 26.2 moved the main render target onto the game renderer. Screenshot
            // still takes that target, so only where it comes from changed.
            Object target = minecraft.gameRenderer.mainRenderTarget();
            *///? } else {
            Object target = minecraft.getMainRenderTarget();
            //? }
            Consumer<Object> noMessage = ignored -> {};
            for (Method method : screenshot.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (parameters.length == 4
                        && parameters[0] == File.class
                        && parameters[1] == String.class
                        && parameters[2].isInstance(target)
                        && parameters[3] == Consumer.class) {
                    method.setAccessible(true);
                    method.invoke(null, gameDirectory, basename, target, noMessage);
                    return true;
                }
                if (parameters.length == 5
                        && parameters[0] == File.class
                        && parameters[1] == String.class
                        && parameters[2].isInstance(target)
                        && parameters[3] == int.class
                        && parameters[4] == Consumer.class) {
                    method.setAccessible(true);
                    method.invoke(null, gameDirectory, basename, target, 1, noMessage);
                    return true;
                }
            }
            E2ELog.warn("no supported Screenshot.grab method found");
        } catch (Throwable failure) {
            E2ELog.warn("screenshot failed: " + failure);
        }
        return false;
    }

    public static boolean press(Object widget) {
        if (widget instanceof Button button) {
            //? if >=26 {
            /*// 26.1 tells a button which input pressed it; the harness presses with a
            // plain left click and no modifiers.
            button.onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0));
            *///? } else {
            button.onPress();
            //? }
            return true;
        }
        return false;
    }

    public static boolean useBlock(Minecraft minecraft, BlockPos position) {
        if (minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }
        try {
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(position), Direction.NORTH, position, false);
            minecraft.gameMode.useItemOn(
                    minecraft.player, InteractionHand.MAIN_HAND, hit);
            return true;
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            E2ELog.warn("real block interaction failed: " + trace);
            return false;
        }
    }

    /**
     * Put a stack into a hotbar slot the way the creative inventory does. The server accepts
     * the packet because the test world forces creative mode.
     */
    public static void creativeSetHotbar(Minecraft minecraft, int slot, ItemStack stack) {
        minecraft.player.getInventory().setItem(slot, stack.copy());
        // Slots 36-44 of the player's inventory menu are the hotbar.
        minecraft.gameMode.handleCreativeModeItemAdd(stack.copy(), 36 + slot);
    }

    /** Place the held block on top of {@code floor}, through the packet a right click sends. */
    public static boolean placeOnTop(Minecraft minecraft, BlockPos floor) {
        if (minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(
                new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5),
                Direction.UP, floor, false);
        return minecraft.gameMode.useItemOn(
                minecraft.player, InteractionHand.MAIN_HAND, hit).consumesAction();
    }

    /**
     * Hold the player at an exact position and view. Flying keeps gravity from moving it
     * between the pose and the capture; the server sees ordinary movement packets.
     */
    public static void pose(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
        LocalPlayer player = minecraft.player;
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    public static void cameraType(Minecraft minecraft, CameraType type) {
        minecraft.options.setCameraType(type);
    }

    /** Hide or show the HUD and the held item, as F1 does. */
    public static void hideGui(Minecraft minecraft, boolean hidden) {
        //? if >=26.2 {
        /*// 26.2 moved the F1 toggle from the options onto the HUD.
        if (minecraft.gui.hud.isHidden() != hidden) {
            minecraft.gui.hud.toggle();
        }
        *///? } else {
        minecraft.options.hideGui = hidden;
        //? }
    }

    private static Class<?> loadNamedClass(String named) throws ClassNotFoundException {
        try {
            return Class.forName(named);
        } catch (ClassNotFoundException missingNamed) {
            if ("net.minecraft.client.Screenshot".equals(named)) {
                try {
                    return Class.forName("net.minecraft.class_318");
                } catch (ClassNotFoundException missingIntermediary) {
                    missingNamed.addSuppressed(missingIntermediary);
                }
            }
            try {
                Class<?> loaderType = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object loader = loaderType.getMethod("getInstance").invoke(null);
                Object resolver = loaderType.getMethod("getMappingResolver").invoke(loader);
                Class<?> resolverType = Class.forName("net.fabricmc.loader.api.MappingResolver");
                String runtimeName = (String) resolverType
                        .getMethod("mapClassName", String.class, String.class)
                        .invoke(resolver, "named", named);
                return Class.forName(runtimeName);
            } catch (ReflectiveOperationException noResolver) {
                missingNamed.addSuppressed(noResolver);
                throw missingNamed;
            }
        }
    }
}
