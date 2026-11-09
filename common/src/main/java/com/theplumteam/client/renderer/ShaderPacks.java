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
    @Nullable
    private static MethodHandle shadowPass;
    private static boolean looked;

    private ShaderPacks() {
    }

    static boolean inUse() {
        look();
        if (inUse == null) {
            return false;
        }
        try {
            return (boolean) inUse.invokeExact();
        } catch (Throwable e) {
            inUse = null;
            shadowPass = null;
            BlockPopsMod.LOGGER.warn("Could not ask Iris whether a shader pack is in use: {}", e.toString());
            return false;
        }
    }

    /** Whether the shader pack is drawing its shadow map now, which needs no glint. */
    static boolean renderingShadowPass() {
        look();
        if (shadowPass == null) {
            return false;
        }
        try {
            return (boolean) shadowPass.invokeExact();
        } catch (Throwable e) {
            shadowPass = null;
            return false;
        }
    }

    private static void look() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> api = Class.forName(API, false, ShaderPacks.class.getClassLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            MethodType returnsBoolean = MethodType.methodType(boolean.class);
            inUse = MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", returnsBoolean)
                    .bindTo(instance);
            shadowPass = MethodHandles.publicLookup().findVirtual(api, "isRenderingShadowPass", returnsBoolean)
                    .bindTo(instance);
        } catch (ClassNotFoundException e) {
            // Neither Iris nor Oculus is installed
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            BlockPopsMod.LOGGER.warn("Iris is present but its API could not be reached: {}", e.toString());
        }
    }
}
