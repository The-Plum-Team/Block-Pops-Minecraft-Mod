package com.theplumteam.neoforge;

import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
//? if >=1.21.4 {
/*import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
*///? } else {
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
//? }
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
//? if <1.21.4 {
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
//? }
//? if <1.21.4 {
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
//? }

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = "blockpops", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class BlockPopsNeoForgeClient {

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

    //? if >=1.21.4 {
    /*// 1.21.4 registers special model renderers through their own event instead of
    // client item extensions, so the renderer is bound to the item model by id.
    @SubscribeEvent
    public static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(BoxBlockItemRenderer.ID, BoxBlockItemRenderer.Unbaked.MAP_CODEC);
        event.register(FigureBlockItemRenderer.ID, FigureBlockItemRenderer.Unbaked.MAP_CODEC);
        event.register(ClawMachineBlockItemRenderer.ID, ClawMachineBlockItemRenderer.Unbaked.MAP_CODEC);
    }
    *///? } else {
    /**
     * NeoForge 21.1 removed Item#initializeClient, so the custom item renderers that
     * Forge attaches per item are bound here instead. The renderer chosen for an item
     * matches the Forge item subclasses: claw machine, figure, or box.
     */
    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        List<Item> clawItems = new ArrayList<>();
        List<Item> figureItems = new ArrayList<>();
        List<Item> boxItems = new ArrayList<>();

        collect(ModItems.CLAW_MACHINE_BLOCK_ITEM.get(), clawItems, figureItems, boxItems);
        collect(ModItems.FIGURE_BLOCK_ITEM.get(), clawItems, figureItems, boxItems);
        ModItems.BOX_BLOCK_ITEMS.values().forEach(supplier -> collect(supplier.get(), clawItems, figureItems, boxItems));
        ModItems.DEFAULT_BOX_BLOCK_ITEMS.values().forEach(supplier -> collect(supplier.get(), clawItems, figureItems, boxItems));

        register(event, clawItems, ClawMachineBlockItemRenderer::new);
        register(event, figureItems, FigureBlockItemRenderer::new);
        register(event, boxItems, BoxBlockItemRenderer::new);
    }

    private static void collect(Item item, List<Item> claw, List<Item> figure, List<Item> box) {
        if (!(item instanceof BlockItem blockItem)) {
            return;
        }
        Block block = blockItem.getBlock();
        if (block instanceof ClawMachineBlock) {
            claw.add(item);
        } else if (block instanceof FigureBlock) {
            figure.add(item);
        } else if (block instanceof BoxBlock) {
            box.add(item);
        }
    }

    private static void register(RegisterClientExtensionsEvent event, List<Item> items,
                                 java.util.function.Supplier<BlockEntityWithoutLevelRenderer> factory) {
        if (items.isEmpty()) {
            return;
        }
        IClientItemExtensions extensions = new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = factory.get();
                }
                return renderer;
            }
        };
        event.registerItem(extensions, items.toArray(new Item[0]));
    }
    //? }
}
