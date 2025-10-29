package com.theplumteam.forge;

import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = "blockpops", value = Dist.CLIENT)
public class BlockPopsModForgeClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOX_BLOCK.get(), context -> new BoxBlockRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), context -> new ClawMachineBlockRenderer());
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
