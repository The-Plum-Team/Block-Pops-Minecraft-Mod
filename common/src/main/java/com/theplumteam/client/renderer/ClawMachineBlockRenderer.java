package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.model.ClawMachineBlockModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

//? if >=26 {
/*public class ClawMachineBlockRenderer extends GeoBlockRenderer<ClawMachineBlockEntity,
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
    public ClawMachineBlockRenderer(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context) {
        super(context, new ClawMachineBlockModel());
        GeoRendererContext.capture(context);
    }
}
*///? } else {
public class ClawMachineBlockRenderer extends GeoBlockRenderer<ClawMachineBlockEntity> {
    public ClawMachineBlockRenderer() {
        super(new ClawMachineBlockModel());
    }
}
//? }
