package com.theplumteam.client.renderer;

/**
 * Holds the block entity renderer context for renderers built outside registration.
 *
 * From 26.1 GeoBlockRenderer needs the context vanilla hands to a registered block
 * entity renderer, but this mod also builds renderers for the collection screen and
 * for items, where no context is offered. The registered renderers capture it, and
 * every later construction reads it back; all of them run after client registration.
 */
public final class GeoRendererContext {
    //? if >=26 {
    /*private static volatile net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context;

    public static void capture(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context value) {
        if (value != null) {
            context = value;
        }
    }

    public static net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context get() {
        if (context == null) {
            throw new IllegalStateException("Block entity renderers have not been registered yet");
        }
        return context;
    }
    *///? }

    private GeoRendererContext() {
    }
}
