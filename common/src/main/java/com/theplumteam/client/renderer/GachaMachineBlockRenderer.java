package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.GachaMachineBlockEntity;
import com.theplumteam.client.model.GachaMachineBlockModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

//? if >=26 {
/*public class GachaMachineBlockRenderer extends BlockPopsGeoBlockRenderer<GachaMachineBlockEntity,
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
    public GachaMachineBlockRenderer(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context) {
        super(context, new GachaMachineBlockModel());
        GeoRendererContext.capture(context);
    }
}
*///? } else {
public class GachaMachineBlockRenderer extends GeoBlockRenderer<GachaMachineBlockEntity> {
    public GachaMachineBlockRenderer() {
        super(new GachaMachineBlockModel());
    }
}
//? }
