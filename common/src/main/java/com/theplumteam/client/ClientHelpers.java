package com.theplumteam.client;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.client.gui.CollectionSelectionScreen;
import com.theplumteam.client.gui.FavoriteColorSelectionScreen;
import com.theplumteam.client.gui.FigurePositionScreen;
import com.theplumteam.client.renderer.FigurePlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Helper class for client-side interactions to avoid loading client classes on the server.
 * This class MUST NOT be loaded on the dedicated server.
 */
public class ClientHelpers {
    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        // Use the cross-platform FigurePositionScreen from common
        // The offset the box is drawn with, which a figure's own offset may set
        double[] offset = FigurePlacement.boxOffset(boxBlockEntity);
        Minecraft.getInstance().setScreen(new FigurePositionScreen(
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
        ));
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity entity) {
        Minecraft.getInstance().setScreen(new CollectionSelectionScreen(
                pos,
                entity.getCollectionId()
        ));
    }

    public static void openFavoriteColorScreen() {
        Minecraft.getInstance().setScreen(new FavoriteColorSelectionScreen());
    }
}
