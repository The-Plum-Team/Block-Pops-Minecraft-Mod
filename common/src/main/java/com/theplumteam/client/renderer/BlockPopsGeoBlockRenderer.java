package com.theplumteam.client.renderer;

//? if >=26 {
/*import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.constant.DataTickets;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.GeoBlockRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.theplumteam.util.E2EDeterminism;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

// GeckoLib 5.5 captures the clock in render state before extracting controller states.
// Every block, held item, nested figure and collection preview uses this boundary.
class BlockPopsGeoBlockRenderer<T extends BlockEntity & GeoAnimatable, R extends BlockEntityRenderState>
        extends GeoBlockRenderer<T, R> {
    BlockPopsGeoBlockRenderer(BlockEntityRendererProvider.Context context, GeoModel<T> model) {
        super(context, model);
    }

    @Override
    public void captureDefaultRenderState(T animatable, Void relatedObject, R renderState, float partialTick) {
        super.captureDefaultRenderState(animatable, relatedObject, renderState, partialTick);
        if (E2EDeterminism.ENABLED) {
            ((GeoRenderState) renderState).addGeckolibData(DataTickets.TICK, 0.0);
        }
    }
}
*///? }
