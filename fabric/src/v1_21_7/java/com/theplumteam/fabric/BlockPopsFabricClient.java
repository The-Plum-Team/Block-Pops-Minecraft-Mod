package com.theplumteam.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.client.renderer.BoxWidgetRenderer;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
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
        BlockPopsMod.logDebug("BlockPops client initialization on Fabric");

        // Initialize client-side networking
        ModNetworking.initClient();

        // Load locally hidden collections from disk
        com.theplumteam.client.config.ClientServerConfig.loadLocalHiddenCollections();

        // Register disconnect event to clear render cache (prevents memory leaks)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FigureWidgetRenderer.clearCache();
            BoxWidgetRenderer.clearCache();
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

        BlockPopsMod.logDebug("BlockPops Fabric client initialization complete");
    }

    private void registerItemRenderers() {
        // In 1.21.4+, custom item rendering uses SpecialModelRenderer system
        // Fabric API provides a transitive access widener to SpecialModelRenderers.ID_MAPPER
        // Register our special model types so they can be referenced in item model JSON files

        // Register box block item renderer
        SpecialModelRenderers.ID_MAPPER.put(BoxBlockItemRenderer.ID, BoxBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", BoxBlockItemRenderer.ID);

        // Register figure block item renderer
        SpecialModelRenderers.ID_MAPPER.put(FigureBlockItemRenderer.ID, FigureBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", FigureBlockItemRenderer.ID);

        // Register claw machine block item renderer
        SpecialModelRenderers.ID_MAPPER.put(ClawMachineBlockItemRenderer.ID, ClawMachineBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", ClawMachineBlockItemRenderer.ID);
    }
}
