package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.BoxBlock;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for box blocks - texture is determined by the collection or color
 */
public class BoxBlockModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation MODEL = new ResourceLocation(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation ANIMATION = new ResourceLocation(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");
    private static final ResourceLocation DEFAULT_TEXTURE = new ResourceLocation(BlockPopsMod.MOD_ID, "textures/block/box/original.png");

    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        // Check if this box has a specific color (default collection only)
        if (animatable.getBlockState().getBlock() instanceof BoxBlock boxBlock) {
            PopBlockColor color = boxBlock.getColor();
            if (color != null) {
                // Use color-based texture for default collection
                return new ResourceLocation(BlockPopsMod.MOD_ID,
                    "textures/block/box/" + color.getTextureName() + ".png");
            }
        }

        // Otherwise, get texture from the collection
        String collectionId = animatable.getCollectionId();
        return CollectionRegistry.getCollection(collectionId)
                .map(FigureCollection::getBoxTexture)
                .orElse(DEFAULT_TEXTURE);
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        return ANIMATION;
    }
}
