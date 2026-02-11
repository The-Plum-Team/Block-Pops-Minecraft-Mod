package com.theplumteam.client.renderer;

import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;

import com.theplumteam.block.PopBlockColor;

/**
 * Render state for submitting box block entities with 3D rotation to the 1.21.6 PiP
 * (Picture-in-Picture) deferred GUI rendering system.
 *
 * IMPORTANT: This includes the color to make each color's state unique for Minecraft's
 * PiP caching system, preventing texture reuse conflicts.
 */
public record ItemPipRenderState(
    BoxBlockEntity renderEntity,
    PopBlockColor color,
    boolean showFigure,
    float rotationX,
    float rotationY,
    float rotationZ,
    float offsetX,
    float offsetY,
    float camRotX,
    int x0,
    int y0,
    int x1,
    int y1,
    float scale,
    float translateYRatio,
    ScreenRectangle scissorArea
) implements PictureInPictureRenderState {

    @Override
    public ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemPipRenderState other)) return false;
        // Include color in equality check so each color is treated as different content
        return this.color == other.color &&
               this.showFigure == other.showFigure &&
               this.x0 == other.x0 && this.y0 == other.y0 &&
               this.x1 == other.x1 && this.y1 == other.y1;
    }

    @Override
    public int hashCode() {
        // Include color in hash so Minecraft's PiP system treats each color differently
        int result = color != null ? color.hashCode() : 0;
        result = 31 * result + Boolean.hashCode(showFigure);
        result = 31 * result + x0;
        result = 31 * result + y0;
        return result;
    }
}
