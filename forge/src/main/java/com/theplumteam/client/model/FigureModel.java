package com.theplumteam.client.model;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for rendering figures dynamically based on collection data
 */
public class FigureModel extends GeoModel<BoxBlockEntity> {
    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getTexturePath();
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        // Use entityCutoutNoCull for proper rendering without culling issues
        return RenderType.entityCutoutNoCull(getTextureResource(animatable));
    }
}
