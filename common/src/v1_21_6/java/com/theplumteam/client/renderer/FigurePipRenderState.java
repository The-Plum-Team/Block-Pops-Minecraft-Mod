package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;

/**
 * Render state for submitting GeckoLib figure models to the 1.21.6 PiP (Picture-in-Picture)
 * deferred GUI rendering system. Stores all data needed by {@link FigurePipRenderer}
 * to render a figure model to an off-screen texture.
 *
 * IMPORTANT: This includes the figureKey to make each figure's state unique for Minecraft's
 * PiP caching system, preventing texture reuse conflicts.
 */
public record FigurePipRenderState(
    BoxBlockEntity renderEntity,
    String figureKey,
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FigurePipRenderState other)) return false;
        return java.util.Objects.equals(this.figureKey, other.figureKey) &&
               this.x0 == other.x0 && this.y0 == other.y0 &&
               this.x1 == other.x1 && this.y1 == other.y1;
    }

    @Override
    public int hashCode() {
        int result = figureKey != null ? figureKey.hashCode() : 0;
        result = 31 * result + x0;
        result = 31 * result + y0;
        return result;
    }
}
