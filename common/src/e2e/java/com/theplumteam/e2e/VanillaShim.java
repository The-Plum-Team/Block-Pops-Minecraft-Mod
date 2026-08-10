package com.theplumteam.e2e;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
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
            Object target = minecraft.getMainRenderTarget();
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
            button.onPress();
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
            E2ELog.warn("real block interaction failed: " + failure);
            return false;
        }
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
