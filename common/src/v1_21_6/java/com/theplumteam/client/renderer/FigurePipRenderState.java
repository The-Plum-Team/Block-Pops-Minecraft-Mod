package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;

/**
 * Render state for submitting GeckoLib figure models to the 1.21.6 PiP (Picture-in-Picture)
 * deferred GUI rendering system. Stores all data needed by {@link FigurePipRenderer}
 * to render a figure model to an off-screen texture.
 */
public record FigurePipRenderState(
    BoxBlockEntity renderEntity,
    float yRotation,
    float xRotation,
    float zRotation,
    int x0,
    int y0,
    int x1,
    int y1,
    float scale,
    ScreenRectangle scissorArea
) implements PictureInPictureRenderState {

    @Override
    public ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea);
    }
}
