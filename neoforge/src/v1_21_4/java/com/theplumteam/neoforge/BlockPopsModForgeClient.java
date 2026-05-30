package com.theplumteam.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;

/**
 * NeoForge client-side initialization for 1.21.4+
 * Uses the new RegisterSpecialModelRendererEvent instead of IClientItemExtensions
 */
@EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class BlockPopsModForgeClient {

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // Initialize client-side networking
        ModNetworking.initClient();

        // Load locally hidden collections from disk
        com.theplumteam.client.config.ClientServerConfig.loadLocalHiddenCollections();
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOX_BLOCK.get(), context -> new BoxBlockRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), context -> new ClawMachineBlockRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.FIGURE_BLOCK.get(), context -> new FigureBlockRenderer());
    }

    /**
     * Register special model renderers for GeckoLib block items.
     * In 1.21.4+, this replaces IClientItemExtensions.getCustomRenderer() with BlockEntityWithoutLevelRenderer.
     * The renderers are referenced in client item model JSON files at assets/<namespace>/items/<path>.json
     */
    @SubscribeEvent
    public static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        // Register box block item renderer
        event.register(BoxBlockItemRenderer.ID, BoxBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", BoxBlockItemRenderer.ID);

        // Register figure block item renderer
        event.register(FigureBlockItemRenderer.ID, FigureBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", FigureBlockItemRenderer.ID);

        // Register claw machine block item renderer
        event.register(ClawMachineBlockItemRenderer.ID, ClawMachineBlockItemRenderer.Unbaked.MAP_CODEC);
        BlockPopsMod.logDebug("Registered special model renderer: {}", ClawMachineBlockItemRenderer.ID);
    }

    @SubscribeEvent
    public static void registerResourceListeners(AddClientReloadListenersEvent event) {
        // In NeoForge 1.21.4+, AddClientReloadListenersEvent requires a ResourceLocation for the listener
        event.addListener(
            ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "collection_loader"),
            new SimplePreparableReloadListener<Void>() {
                @Override
                protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                    return null;
                }

                @Override
                protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                    CollectionRegistry.loadCollections(resourceManager);
                }
            }
        );
    }
}
