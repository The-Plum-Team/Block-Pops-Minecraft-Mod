package com.theplumteam.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class BlockPopsModForgeClient {

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        // Initialize client-side networking
        ModNetworking.initClient();
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOX_BLOCK.get(), context -> new BoxBlockRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), context -> new ClawMachineBlockRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.FIGURE_BLOCK.get(), context -> new FigureBlockRenderer());
    }

    @SubscribeEvent
    public static void registerResourceListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                CollectionRegistry.loadCollections(resourceManager);
            }
        });
    }
}
