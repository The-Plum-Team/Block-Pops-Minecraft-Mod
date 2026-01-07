package com.theplumteam.item;

import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * Forge-specific GeoBlockItem that provides custom item renderers via IClientItemExtensions.
 */
public class ForgeGeoBlockItem extends GeoBlockItem {

    public ForgeGeoBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    // Return the appropriate renderer based on block type
                    if (getBlock() instanceof ClawMachineBlock) {
                        renderer = new ClawMachineBlockItemRenderer();
                    } else if (getBlock() instanceof FigureBlock) {
                        renderer = new FigureBlockItemRenderer();
                    } else {
                        // Default to BoxBlockItemRenderer for BoxBlock and other types
                        renderer = new BoxBlockItemRenderer();
                    }
                }
                return renderer;
            }
        });
    }
}
