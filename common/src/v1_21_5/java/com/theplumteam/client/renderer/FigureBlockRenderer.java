package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
    }

    // In GeckoLib 5, custom rendering logic should be handled via:
    // 1. GeoModel.addAdditionalStateData() to pass data via DataTickets
    // 2. Custom GeoRenderLayers for additional rendering
    // 3. Overriding render() method where animatable is still available

    // For now, we'll rely on the GeoModel to handle texture resolution via RenderState
}
