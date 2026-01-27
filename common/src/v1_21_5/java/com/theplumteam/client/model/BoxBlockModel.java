package com.theplumteam.client.model;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.constant.dataticket.SerializableDataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.Locale;

/**
 * GeoModel for box blocks - texture is determined by the collection or color
 */
public class BoxBlockModel extends GeoModel<BoxBlockEntity> {
    // GeckoLib 5: Simplified paths (no geckolib/ prefix, no file extensions)
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "block/box_block");
    private static final ResourceLocation DEFAULT_TEXTURE = ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "textures/block/box/original.png");

    // Data tickets for storing box-specific render data (GeckoLib 5 uses static factory methods)
    private static final DataTicket<String> BOX_COLOR = SerializableDataTicket.ofString(ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "box_color"));
    private static final DataTicket<String> COLLECTION_ID = SerializableDataTicket.ofString(ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID, "collection_id"));

    @Override
    public ResourceLocation getModelResource(GeoRenderState renderState) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GeoRenderState renderState) {
        // Retrieve data from render state, with null defaults if not present
        String colorName = renderState.getOrDefaultGeckolibData(BOX_COLOR, null);
        if (colorName != null && !colorName.isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath(BlockPopsMod.MOD_ID,
                "textures/block/box/" + colorName.toLowerCase(Locale.ROOT) + ".png");
        }

        String collectionId = renderState.getOrDefaultGeckolibData(COLLECTION_ID, null);
        if (collectionId != null) {
            return CollectionRegistry.getCollection(collectionId)
                    .map(FigureCollection::getBoxTexture)
                    .orElse(DEFAULT_TEXTURE);
        }

        return DEFAULT_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void addAdditionalStateData(BoxBlockEntity animatable, GeoRenderState renderState) {
        super.addAdditionalStateData(animatable, renderState);

        // Store color and collection data in the render state
        PopBlockColor color = animatable.getColor();
        if (color != null) {
            renderState.addGeckolibData(BOX_COLOR, color.getTextureName());
        }
        renderState.addGeckolibData(COLLECTION_ID, animatable.getCollectionId());
    }
}
