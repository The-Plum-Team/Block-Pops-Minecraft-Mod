package com.theplumteam.item;

import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.ClawMachineBlock;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.client.renderer.BoxBlockItemRenderer;
import com.theplumteam.client.renderer.ClawMachineBlockItemRenderer;
import com.theplumteam.client.renderer.FigureBlockItemRenderer;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class GeoBlockItem extends BlockItem {

    public GeoBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    public BoxBlock getBoxBlock() {
        return (BoxBlock) getBlock();
    }

    @Override
    public Component getName(ItemStack stack) {
        // Customize name for both BoxBlock and FigureBlock items
        String collectionId = null;

        if (getBlock() instanceof BoxBlock boxBlock) {
            // Try to get collection ID and figure ID from NBT
            CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
            String figureId = "";

            if (blockEntityTag != null) {
                // Check for collection ID override (for dynamic collections)
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getString("CollectionId");
                }
                // Get figure ID if present
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getString("FigureId");
                }
            }

            // If still no collection ID in NBT, use default naming
            if (collectionId == null || collectionId.isEmpty()) {
                return super.getName(stack);
            }

            // Look up the collection
            FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
            if (collection == null) {
                return super.getName(stack);
            }

            String collectionName = collection.getName();

            // If there's a figure, just show the figure name
            if (figureId != null && !figureId.isEmpty()) {
                FigureDefinition figure = collection.getFigure(figureId).orElse(null);
                if (figure != null) {
                    // Show only the figure name in white
                    return Component.literal(figure.getName()).withStyle(ChatFormatting.WHITE);
                }
            }

            // No figure, use "Collection Name Box"
            return Component.literal(collectionName + " Box");

        } else if (getBlock() instanceof FigureBlock) {
            // Handle FigureBlock items
            CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
            String figureId = "";

            if (blockEntityTag != null) {
                // Check for collection ID override (for dynamic collections)
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getString("CollectionId");
                }
                // Get figure ID if present
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getString("FigureId");
                }
            }

            // If still no collection ID or figure ID, use default naming
            if (collectionId == null || collectionId.isEmpty() || figureId.isEmpty()) {
                return super.getName(stack);
            }

            // Look up the collection
            FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
            if (collection == null) {
                return super.getName(stack);
            }

            // If there's a figure, show the figure name
            FigureDefinition figure = collection.getFigure(figureId).orElse(null);
            if (figure != null) {
                // Show only the figure name in white
                return Component.literal(figure.getName()).withStyle(ChatFormatting.WHITE);
            }
        }

        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        // Add tooltip for both BoxBlock and FigureBlock items
        String collectionId = null;
        String figureId = "";
        CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");

        if (getBlock() instanceof BoxBlock boxBlock) {
            // Try to get collection ID from NBT or block
            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getString("CollectionId");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getString("FigureId");
                }
            }

        } else if (getBlock() instanceof FigureBlock) {
            // Handle FigureBlock items
            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getString("CollectionId");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getString("FigureId");
                }
            }
        } else {
            return;
        }

        if (collectionId == null || collectionId.isEmpty()) {
            return;
        }

        // Look up the collection and add to tooltip if figure exists
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection != null && figureId != null && !figureId.isEmpty()) {
            // Add collection name to tooltip
            tooltip.add(Component.literal(collection.getName()).withStyle(ChatFormatting.GRAY));

            // Check if figure has alternative skins
            FigureDefinition figure = collection.getFigure(figureId).orElse(null);
            if (figure != null && figure.hasAlternatives()) {
                tooltip.add(Component.translatable("tooltip.blockpops.has_alternatives")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            }

            // Show pose hint only for extracted figures (FigureBlock), not boxes
            if (getBlock() instanceof FigureBlock) {
                tooltip.add(Component.translatable("tooltip.blockpops.pose_hint")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }
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