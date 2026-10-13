package com.theplumteam.platform.fabric;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.FigurePositionScreen;
import com.theplumteam.client.renderer.FigurePlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-only helper for platform methods that require client classes.
 * This class is only loaded on the client to avoid ClassNotFoundException on dedicated servers.
 */
@Environment(EnvType.CLIENT)
public class ClientPlatformHelperImpl {

    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        // The offset the box is drawn with, which a figure's own offset may set
        double[] offset = FigurePlacement.boxOffset(boxBlockEntity);
        Minecraft.getInstance().setScreen(
            new FigurePositionScreen(
                pos,
                offset[0],
                offset[1],
                offset[2],
                boxBlockEntity.getFigureScale(),
                boxBlockEntity.getHitboxOffsetX(),
                boxBlockEntity.getHitboxOffsetY(),
                boxBlockEntity.getHitboxOffsetZ(),
                boxBlockEntity.getHitboxScaleX(),
                boxBlockEntity.getHitboxScaleY(),
                boxBlockEntity.getHitboxScaleZ(),
                boxBlockEntity.getLogoPositionX(),
                boxBlockEntity.getLogoPositionY(),
                boxBlockEntity.getLogoPositionZ(),
                boxBlockEntity.getLogoScaleX(),
                boxBlockEntity.getLogoScaleY(),
                boxBlockEntity.getLogoScaleZ()
            )
        );
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        Minecraft.getInstance().setScreen(
            new CollectionSelectionScreen(
                pos,
                clawMachineBlockEntity.getCollectionId()
            )
        );
    }
}
