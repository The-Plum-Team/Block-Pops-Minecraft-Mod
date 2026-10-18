package com.theplumteam.forge;

import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.client.renderer.GachaMachineBlockRenderer;
import com.theplumteam.client.renderer.HolographicRenderTypes;
import com.theplumteam.client.renderer.PlushBlockRenderer;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = "blockpops", value = Dist.CLIENT)
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
        event.registerBlockEntityRenderer(ModBlockEntities.GACHA_MACHINE_BLOCK.get(), context -> new GachaMachineBlockRenderer());
        ModBlockEntities.PLUSH_BLOCKS.forEach((plushId, type) ->
                event.registerBlockEntityRenderer(type.get(), context -> new PlushBlockRenderer(plushId)));
        event.registerBlockEntityRenderer(ModBlockEntities.FIGURE_BLOCK.get(), context -> new FigureBlockRenderer());
    }

    // The shader holographic figures and plushes are drawn with
    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), HolographicRenderTypes.SHADER,
                DefaultVertexFormat.NEW_ENTITY), HolographicRenderTypes::setShader);
    }

    // DO NOT add a client-side resource reload listener for collections here!
    // In Singleplayer (and with Kilt on Fabric), the Client and Server share the same static CollectionRegistry.
    // A client reload listener would wipe the server's data because it looks in assets/ (empty)
    // instead of data/ where the JSONs are located.
    // The client receives collections via SyncDynamicCollectionsPacket from the server on join.
}
