package com.theplumteam.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.BoxBlockRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.network.ModNetworking;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class BlockPopsModForgeClient {

    // Lazy-initialized renderers (shared across all items of same type)
    private static BoxBlockItemRenderer boxRenderer;
    private static FigureBlockItemRenderer figureRenderer;
    private static ClawMachineBlockItemRenderer clawRenderer;

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
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        // Collect all box block items (color variants and collection variants)
        List<Item> boxItems = new ArrayList<>();

        // Add color variant box items
        for (PopBlockColor color : PopBlockColor.values()) {
            var itemSupplier = ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color);
            if (itemSupplier != null) {
                boxItems.add(itemSupplier.get());
            }
        }

        // Add collection variant box items
        for (var entry : ModItems.BOX_BLOCK_ITEMS.entrySet()) {
            var itemSupplier = entry.getValue();
            if (itemSupplier != null) {
                boxItems.add(itemSupplier.get());
            }
        }

        // Register box item renderer for all box items
        if (!boxItems.isEmpty()) {
            IClientItemExtensions boxExtensions = new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (boxRenderer == null) {
                        boxRenderer = new BoxBlockItemRenderer();
                    }
                    return boxRenderer;
                }
            };
            event.registerItem(boxExtensions, boxItems.toArray(new Item[0]));
            BlockPopsMod.logDebug("Registered box item renderer for {} items", boxItems.size());
        }

        // Register figure block item renderer
        if (ModItems.FIGURE_BLOCK_ITEM != null) {
            event.registerItem(new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (figureRenderer == null) {
                        figureRenderer = new FigureBlockItemRenderer();
                    }
                    return figureRenderer;
                }
            }, ModItems.FIGURE_BLOCK_ITEM.get());
            BlockPopsMod.logDebug("Registered figure block item renderer");
        }

        // Register claw machine block item renderer
        if (ModItems.CLAW_MACHINE_BLOCK_ITEM != null) {
            event.registerItem(new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (clawRenderer == null) {
                        clawRenderer = new ClawMachineBlockItemRenderer();
                    }
                    return clawRenderer;
                }
            }, ModItems.CLAW_MACHINE_BLOCK_ITEM.get());
            BlockPopsMod.logDebug("Registered claw machine block item renderer");
        }
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
