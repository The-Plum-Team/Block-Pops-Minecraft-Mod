package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.model.ClawMachineBlockModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class ClawMachineBlockRenderer extends GeoBlockRenderer<ClawMachineBlockEntity> {
    public ClawMachineBlockRenderer() {
        super(new ClawMachineBlockModel());
    }
}
