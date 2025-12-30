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
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Fabric client entry point for BlockPops
 * This class is only loaded on Fabric clients (not dedicated servers)
 */
public class BlockPopsFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockPopsMod.LOGGER.info("BlockPops client initialization on Fabric");

        // Initialize client-side networking
        ModNetworking.initClient();

        // Register disconnect event to clear render cache (prevents memory leaks)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FigureWidgetRenderer.clearCache();
        });

        // Register block entity renderers
        BlockEntityRendererRegistry.register(ModBlockEntities.BOX_BLOCK.get(), context -> new BoxBlockRenderer());
        BlockEntityRendererRegistry.register(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), context -> new ClawMachineBlockRenderer());
        BlockEntityRendererRegistry.register(ModBlockEntities.FIGURE_BLOCK.get(), context -> new FigureBlockRenderer());

        // Register item renderers for GeckoLib blocks
        registerItemRenderers();

        // Register resource reload listener for collections
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public ResourceLocation getFabricId() {
                    return ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "collection_loader");
                }

                @Override
                public void onResourceManagerReload(ResourceManager resourceManager) {
                    CollectionRegistry.loadCollections(resourceManager);
                }
            }
        );

        BlockPopsMod.LOGGER.info("BlockPops Fabric client initialization complete");
    }

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

        BlockPopsMod.LOGGER.info("Registered item renderers for GeckoLib blocks");
    }
}
