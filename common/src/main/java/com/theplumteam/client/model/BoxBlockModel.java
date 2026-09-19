package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Locale;

/**
 * GeoModel for box blocks - texture is determined by the collection or color
 */
public class BoxBlockModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation MODEL = ResourceLocations.of(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation ANIMATION = ResourceLocations.of(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");
    private static final ResourceLocation DEFAULT_TEXTURE = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/block/box/original.png");

    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        // Check if this box has a specific color (default collection only)
        // Color is now stored in NBT on the block entity
        PopBlockColor color = animatable.getColor();
        if (color != null) {
            // Use color-based texture for default collection
            // We explicitly force lowercase here as a safety measure against ResourceLocationException
            return ResourceLocations.of(BlockPopsMod.MOD_ID,
                "textures/block/box/" + color.getTextureName().toLowerCase(Locale.ROOT) + ".png");
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
