package com.theplumteam.client.renderer;

import com.theplumteam.BlockPopsMod;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether an Iris (Fabric) or Oculus (Forge, NeoForge) shader pack is in use, asked through
 * their public API by reflection, so neither mod is needed to build or run. Without either, or
 * if the API cannot be reached, no shader pack is in use.
 */
final class ShaderPacks {
    private static final String API = "net.irisshaders.iris.api.v0.IrisApi";

    @Nullable
    private static MethodHandle inUse;
    private static boolean looked;

    private ShaderPacks() {
    }

    static boolean inUse() {
        if (!looked) {
            looked = true;
            inUse = find();
        }
        if (inUse == null) {
            return false;
        }
        try {
            return (boolean) inUse.invokeExact();
        } catch (Throwable e) {
            inUse = null;
            BlockPopsMod.LOGGER.warn("Could not ask Iris whether a shader pack is in use: {}", e.toString());
            return false;
        }
    }

    @Nullable
    private static MethodHandle find() {
        try {
            Class<?> api = Class.forName(API, false, ShaderPacks.class.getClassLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup()
                    .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Iris is present but its API could not be reached: {}", e.toString());
            return null;
        }
    }
}
