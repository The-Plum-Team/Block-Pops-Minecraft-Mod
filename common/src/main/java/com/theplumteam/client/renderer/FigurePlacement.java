package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.FigureDefinition;

/**
 * Where and how big a figure is drawn, combining the box's own placement with what
 * the figure's definition declares.
 */
public final class FigurePlacement {
    private FigurePlacement() {
    }

    /**
     * The figure's offset inside the box, {x, y, z}. An axis the box still holds at its
     * default takes the figure's own value, so boxes placed before a figure declared one,
     * and boxes an operator moved by hand, keep what they have.
     */
    public static double[] boxOffset(BoxBlockEntity box) {
        double[] offset = {box.getFigureOffsetX(), box.getFigureOffsetY(), box.getFigureOffsetZ()};
        FigureDefinition figure = box.getFigureDefinition();
        float[] declared = figure != null ? figure.getRenderOptions().offset() : null;
        if (declared == null) {
            return offset;
        }
        double[] defaults = {BoxBlockEntity.DEFAULT_FIGURE_OFFSET_X, BoxBlockEntity.DEFAULT_FIGURE_OFFSET_Y,
                BoxBlockEntity.DEFAULT_FIGURE_OFFSET_Z};
        for (int i = 0; i < 3; i++) {
            if (offset[i] == defaults[i] && !Float.isNaN(declared[i])) {
                offset[i] = declared[i];
            }
        }
        return offset;
    }

    /** A skin's own scale, 1 for figures on the default model. */
    public static float scale(FigureDefinition figure, int skinIndex) {
        return figure != null ? figure.getScaleForSkinIndex(skinIndex) : 1.0f;
    }

    /**
     * Scales what a block renderer draws next about the centre of its block's floor,
     * which is where GeckoLib's block renderers centre a model, so the figure grows or
     * shrinks in place.
     */
    public static void scaleAboutCentre(PoseStack poseStack, float scale) {
        if (scale == 1.0f) {
            return;
        }
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-0.5, 0.0, -0.5);
    }
}
