package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.model.FigureBlockModel;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.util.Collections;
import java.util.List;

public class FigureBlockRenderer extends GeoBlockRenderer<FigureBlockEntity> {
    private float currentDefScale = 1.0f;
    private List<String> currentHiddenBones = Collections.emptyList();
    private FigureDefinition currentFigureDef = null;

    public FigureBlockRenderer() {
        super(new FigureBlockModel());
        addRenderLayer(new FigureBoneTextureLayer<>(this));
    }

    @Override
    public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture, true);
    }

    @Override
    public void render(FigureBlockEntity animatable, float partialTick, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        // Only render if there's a valid figure - prevents rendering the fallback
        // box_block model with Steve's skin while NBT data hasn't synced yet
        if (!animatable.hasFigure()) {
            return;
        }

        FigureDefinition figureDef = animatable.getFigureDefinition();
        this.currentFigureDef = figureDef;
        this.currentDefScale = figureDef != null ? figureDef.getScale() : 1.0f;
        this.currentHiddenBones = figureDef != null ?
            figureDef.getHiddenBonesForSkinIndex(animatable.getAlternativeSkinIndex()) :
            Collections.emptyList();
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);
    }

    @Override
    public void actuallyRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                              @Nullable RenderType renderType, MultiBufferSource bufferSource,
                              @Nullable VertexConsumer buffer, boolean isReRender,
                              int packedLight, int packedOverlay, int renderColor) {
        if (currentDefScale != 1.0f) {
            poseStack.scale(currentDefScale, currentDefScale, currentDefScale);
        }
        // BakedGeoModel is cached/shared, so we must reset all variant bones to visible first,
        // then hide the current variant's hidden bones
        if (currentFigureDef != null) {
            for (String boneName : currentFigureDef.getAllVariantBoneNames()) {
                model.getBone(boneName).ifPresent(bone -> {
                    bone.setHidden(false);
                    bone.setChildrenHidden(false);
                });
            }
            for (String boneName : currentHiddenBones) {
                model.getBone(boneName).ifPresent(bone -> {
                    bone.setHidden(true);
                    bone.setChildrenHidden(true);
                });
            }
            // Hide bones that use extra textures - they'll be re-rendered by FigureBoneTextureLayer
            // This must be done here (after bone reset) because the reset above would undo preRender hiding
            for (FigureDefinition.ExtraTexture extra : currentFigureDef.getExtraTextures()) {
                for (String boneName : extra.bones()) {
                    model.getBone(boneName).ifPresent(bone -> {
                        bone.setHidden(true);
                        bone.setChildrenHidden(false);
                    });
                }
            }
        }
        super.actuallyRender(renderState, poseStack, model, renderType, bufferSource, buffer,
                           isReRender, packedLight, packedOverlay, renderColor);
    }

    @Override
    public void renderRecursively(GeoRenderState renderState, PoseStack poseStack, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, int packedLight, int packedOverlay, int colour) {
        if (!currentHiddenBones.isEmpty() && currentHiddenBones.contains(bone.getName())) {
            return;
        }
        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer,
                              isReRender, packedLight, packedOverlay, colour);
    }
}
