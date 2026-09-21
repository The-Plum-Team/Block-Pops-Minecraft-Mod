package com.theplumteam.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
//? if >=1.21.4 {
/*import net.minecraft.client.renderer.special.SpecialModelRenderers;
*///? } else {
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
//? }

/**
 * Fabric client entry point for BlockPops
 * This class is only loaded on Fabric clients (not dedicated servers)
 */
public class BlockPopsFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockPopsMod.logDebug("BlockPops client initialization on Fabric");

        // Initialize client-side networking
        ModNetworking.initClient();

        // Register disconnect event to clear render cache (prevents memory leaks)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FigureWidgetRenderer.clearCache();
        });

        // Register block entity renderers
        BlockEntityRendererRegistry.register(ModBlockEntities.BOX_BLOCK.get(), context ->
                //? if >=26 {
                /*new BoxBlockRenderer(context)
                *///? } else {
                new BoxBlockRenderer()
                //? }
        );
        BlockEntityRendererRegistry.register(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), context ->
                //? if >=26 {
                /*new ClawMachineBlockRenderer(context)
                *///? } else {
                new ClawMachineBlockRenderer()
                //? }
        );
        BlockEntityRendererRegistry.register(ModBlockEntities.FIGURE_BLOCK.get(), context ->
                //? if >=26 {
                /*new FigureBlockRenderer(context)
                *///? } else {
                new FigureBlockRenderer()
                //? }
        );

        // Register item renderers for GeckoLib blocks
        registerItemRenderers();

        // DO NOT add a client-side resource reload listener for collections here!
        // In Singleplayer (and with Kilt), the Client and Server share the same static CollectionRegistry.
        // A client reload listener would wipe the server's data because it looks in assets/ (empty)
        // instead of data/ where the JSONs are located.
        // The client receives collections via SyncDynamicCollectionsPacket from the server on join.

        BlockPopsMod.logDebug("BlockPops Fabric client initialization complete");
    }

    //? if >=1.21.4 {
    /*// Fabric API dropped BuiltinItemRendererRegistry in 1.21.4. Special renderers are
    // now bound to item models through the vanilla id mapper instead.
    private void registerItemRenderers() {
        SpecialModelRenderers.ID_MAPPER.put(BoxBlockItemRenderer.ID, BoxBlockItemRenderer.Unbaked.MAP_CODEC);
        SpecialModelRenderers.ID_MAPPER.put(FigureBlockItemRenderer.ID, FigureBlockItemRenderer.Unbaked.MAP_CODEC);
        SpecialModelRenderers.ID_MAPPER.put(ClawMachineBlockItemRenderer.ID, ClawMachineBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderers for GeckoLib blocks");
    }
    *///? } else {
    private void registerItemRenderers() {
        // Create lazy-initialized renderers (created on first use to avoid early Minecraft access)
        BoxBlockItemRenderer boxRenderer = null;
        FigureBlockItemRenderer figureRenderer = null;
        ClawMachineBlockItemRenderer clawRenderer = null;

        // Register box block item renderers for all color variants
        for (PopBlockColor color : PopBlockColor.values()) {
            var itemSupplier = ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color);
            if (itemSupplier != null) {
                final BoxBlockItemRenderer finalBoxRenderer = boxRenderer != null ? boxRenderer : (boxRenderer = new BoxBlockItemRenderer());
                BuiltinItemRendererRegistry.INSTANCE.register(itemSupplier.get(), finalBoxRenderer::renderByItem);
            }
        }

        // Register box block item renderers for all collection variants
        for (var entry : ModItems.BOX_BLOCK_ITEMS.entrySet()) {
            var itemSupplier = entry.getValue();
            if (itemSupplier != null) {
                final BoxBlockItemRenderer finalBoxRenderer = boxRenderer != null ? boxRenderer : (boxRenderer = new BoxBlockItemRenderer());
                BuiltinItemRendererRegistry.INSTANCE.register(itemSupplier.get(), finalBoxRenderer::renderByItem);
            }
        }

        // Register figure block item renderer
        if (ModItems.FIGURE_BLOCK_ITEM != null) {
            figureRenderer = new FigureBlockItemRenderer();
            BuiltinItemRendererRegistry.INSTANCE.register(ModItems.FIGURE_BLOCK_ITEM.get(), figureRenderer::renderByItem);
        }

        // Register claw machine block item renderer
        if (ModItems.CLAW_MACHINE_BLOCK_ITEM != null) {
            clawRenderer = new ClawMachineBlockItemRenderer();
            BuiltinItemRendererRegistry.INSTANCE.register(ModItems.CLAW_MACHINE_BLOCK_ITEM.get(), clawRenderer::renderByItem);
        }

        BlockPopsMod.logDebug("Registered item renderers for GeckoLib blocks");
    }
    //? }
}
