package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.util.GeoAssets;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
//? if >=1.21.5 {
/*import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? } elif >=1.21.2 {
/*import software.bernie.geckolib.renderer.GeoRenderer;
*///? }

import java.util.Locale;

/**
 * GeoModel for box blocks - texture is determined by the collection or color
 */
public class BoxBlockModel extends GeoModel<BoxBlockEntity> {
    private static final ResourceLocation MODEL = GeoAssets.model(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation ANIMATION = GeoAssets.animation(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation DEFAULT_TEXTURE = ResourceLocations.of(BlockPopsMod.MOD_ID, "textures/block/box/original.png");

    // GeckoLib 5 resolves textures from the render state alone, so the texture of
    // the box being drawn is carried across as render data.
    //? if >=1.21.5 {
    /*private static final DataTicket<ResourceLocation> BOX_TEXTURE =
            DataTicket.create("blockpops:box_texture", ResourceLocation.class);
    *///? }

    @Override
    //? if >=1.21.5 {
    /*public ResourceLocation getModelResource(GeoRenderState renderState) {
    *///? } elif >=1.21.2 {
    /*public ResourceLocation getModelResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
    *///? } else {
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
    //? }
        return MODEL;
    }

    //? if >=1.21.5 {
    /*@Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        return renderState.getOrDefaultGeckolibData(BOX_TEXTURE, DEFAULT_TEXTURE);
    }

    @Override
    public void addAdditionalStateData(BoxBlockEntity animatable,
                                       //? if >=1.21.9 {
                                       /^Object relatedObject,
                                       ^///? }
                                       GeoRenderState renderState) {
        super.addAdditionalStateData(animatable,
                //? if >=1.21.9 {
                /^relatedObject,
                ^///? }
                renderState);
        renderState.addGeckolibData(BOX_TEXTURE, resolveTexture(animatable));
    }
    *///? } elif >=1.21.2 {
    /*@Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable, GeoRenderer<BoxBlockEntity> renderer) {
        return resolveTexture(animatable);
    }
    *///? } else {
    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        return resolveTexture(animatable);
    }
    //? }

    private ResourceLocation resolveTexture(BoxBlockEntity animatable) {
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
