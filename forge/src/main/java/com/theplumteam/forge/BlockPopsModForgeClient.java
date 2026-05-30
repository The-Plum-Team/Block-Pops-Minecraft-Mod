package com.theplumteam.forge;

import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = "blockpops", value = Dist.CLIENT)
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

    // DO NOT add a client-side resource reload listener for collections here!
    // In Singleplayer (and with Kilt on Fabric), the Client and Server share the same static CollectionRegistry.
    // A client reload listener would wipe the server's data because it looks in assets/ (empty)
    // instead of data/ where the JSONs are located.
    // The client receives collections via SyncDynamicCollectionsPacket from the server on join.
}
