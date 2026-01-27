package com.theplumteam.item;

import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.FigureBlock;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

import java.util.List;

/**
 * Base BlockItem for GeckoLib animated blocks.
 * Platform-specific extensions (like custom item renderers) are handled in forge/fabric modules.
 */
public class GeoBlockItem extends BlockItem {

    public GeoBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    public BoxBlock getBoxBlock() {
        return (BoxBlock) getBlock();
    }

    private CompoundTag getBlockEntityTag(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return customData != null ? customData.copyTag() : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        String collectionId = null;

        if (getBlock() instanceof BoxBlock boxBlock) {
            CompoundTag blockEntityTag = getBlockEntityTag(stack);
            String figureId = "";

            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getStringOr("CollectionId", "");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getStringOr("FigureId", "");
                }
            }

            if (collectionId == null || collectionId.isEmpty()) {
                return super.getName(stack);
            }

            FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
            if (collection == null) {
                return super.getName(stack);
            }

            String collectionName = collection.getName();

            if (figureId != null && !figureId.isEmpty()) {
                FigureDefinition figure = collection.getFigure(figureId).orElse(null);
                if (figure != null) {
                    return Component.literal(figure.getName()).withStyle(ChatFormatting.WHITE);
                }
            }

            return Component.literal(collectionName + " Box");

        } else if (getBlock() instanceof FigureBlock) {
            CompoundTag blockEntityTag = getBlockEntityTag(stack);
            String figureId = "";

            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getStringOr("CollectionId", "");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getStringOr("FigureId", "");
                }
            }

            if (collectionId == null || collectionId.isEmpty() || figureId.isEmpty()) {
                return super.getName(stack);
            }

            FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
            if (collection == null) {
                return super.getName(stack);
            }

            FigureDefinition figure = collection.getFigure(figureId).orElse(null);
            if (figure != null) {
                return Component.literal(figure.getName()).withStyle(ChatFormatting.WHITE);
            }
        }

        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);

        String collectionId = null;
        String figureId = "";
        CompoundTag blockEntityTag = getBlockEntityTag(stack);

        if (getBlock() instanceof BoxBlock boxBlock) {
            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getStringOr("CollectionId", "");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getStringOr("FigureId", "");
                }
            }

        } else if (getBlock() instanceof FigureBlock) {
            if (blockEntityTag != null) {
                if (blockEntityTag.contains("CollectionId")) {
                    collectionId = blockEntityTag.getStringOr("CollectionId", "");
                }
                if (blockEntityTag.contains("FigureId")) {
                    figureId = blockEntityTag.getStringOr("FigureId", "");
                }
            }
        } else {
            return;
        }

        if (collectionId == null || collectionId.isEmpty()) {
            return;
        }

        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection != null && figureId != null && !figureId.isEmpty()) {
            tooltip.accept(Component.literal(collection.getName()).withStyle(ChatFormatting.GRAY));

            FigureDefinition figure = collection.getFigure(figureId).orElse(null);
            if (figure != null && figure.hasAlternatives()) {
                tooltip.accept(Component.translatable("tooltip.blockpops.has_alternatives")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            }

            if (getBlock() instanceof FigureBlock) {
                tooltip.accept(Component.translatable("tooltip.blockpops.pose_hint")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }
    }
}
