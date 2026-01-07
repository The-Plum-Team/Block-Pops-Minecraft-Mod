package com.theplumteam.item;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * Forge-specific BoxBlockItem that provides custom item renderers via IClientItemExtensions.
 */
public class ForgeBoxBlockItem extends BoxBlockItem {

    public ForgeBoxBlockItem(Block block, Properties properties, String collectionId) {
        super(block, properties, collectionId);
    }

    public ForgeBoxBlockItem(Block block, Properties properties, PopBlockColor color) {
        super(block, properties, color);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new BoxBlockItemRenderer();
                }
                return renderer;
            }
        });
    }
}
