package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.model.ClawMachineBlockModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

//? if >=1.21.9 {
/*public class ClawMachineBlockRenderer extends GeoBlockRenderer<ClawMachineBlockEntity,
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
*///? } else {
public class ClawMachineBlockRenderer extends GeoBlockRenderer<ClawMachineBlockEntity> {
//? }
    public ClawMachineBlockRenderer() {
        super(new ClawMachineBlockModel());
    }
}
