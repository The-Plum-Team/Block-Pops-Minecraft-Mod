package com.theplumteam.client;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.FigurePositionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Helper class for client-side interactions to avoid loading client classes on the server.
 */
public class ClientHelpers {
    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        Minecraft.getInstance().setScreen(new FigurePositionScreen(
                pos,
                boxBlockEntity.getFigureOffsetX(),
                boxBlockEntity.getFigureOffsetY(),
                boxBlockEntity.getFigureOffsetZ(),
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
        ));
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity entity) {
        Minecraft.getInstance().setScreen(new CollectionSelectionScreen(
                pos,
                entity.getCollectionId()
        ));
    }
}