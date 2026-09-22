package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.util.GeoBones;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
//? if >=1.21.5 {
/*import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.base.GeoRenderState;
*///? }

//? if >=26 {
/*public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity,
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity,
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> figureRenderer;
*///? } else {
public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
//? }

    //? if >=1.21.5 {
    /*// From 1.21.5 the render methods only see the render state, so the box being
    // drawn is held for the length of one render pass, which is where the figure,
    // its face and the collection logo read their placement from.
    private BoxBlockEntity currentBox;
    private float currentPartialTick;
    private Vec3 currentCamPos = Vec3.ZERO;
    *///? }

    //? if >=26 {
    /*public BoxBlockRenderer(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context) {
        super(context, new BoxBlockModel());
        GeoRendererContext.capture(context);
        // A separate renderer draws the figure that sits inside the box.
        this.figureRenderer = new GeoBlockRenderer<>(context, new FigureModel()) {
            @Override
            @SuppressWarnings("rawtypes")
            protected void tryRotateByBlockstate(com.geckolib.renderer.base.RenderPassInfo renderPassInfo,
                                                 PoseStack poseStack) {
                // The figure keeps its own facing rather than the block's.
            }

            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public void adjustModelBonesForRender(com.geckolib.renderer.base.RenderPassInfo renderPassInfo,
                                                  com.geckolib.renderer.base.BoneSnapshots snapshots) {
                com.geckolib.renderer.base.GeoRenderState state =
                        (com.geckolib.renderer.base.GeoRenderState) renderPassInfo.renderState();
                FigureBlockRenderer.applyArmVisibility(snapshots, this.getGeoModel().getTextureResource(state));
            }
        };
    }
    *///? } else {
    public BoxBlockRenderer() {
        super(new BoxBlockModel());
        // Create a separate renderer instance for the figure (like Lineages does with the book)
        this.figureRenderer = new GeoBlockRenderer<>(new FigureModel()) {
            @Override
            protected void rotateBlock(Direction facing, PoseStack poseStack) {
                // Don't apply block rotation to the figure - we want it to always face the same direction
                // or we can apply a custom rotation here
            }

            //? if >=1.21.5 {
            /*@Override
            public void preRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  int packedLight, int packedOverlay, int colour) {
                super.preRender(renderState, poseStack, model, bufferSource, buffer, isReRender,
                        packedLight, packedOverlay, colour);
                FigureBlockRenderer.applyArmVisibility(model, this.getGeoModel().getTextureResource(renderState));
            }
            *///? } else {
            @Override
            public void preRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                 MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                 //? if >=1.21 {
                                 /*float partialTick, int packedLight, int packedOverlay, int colour) {
                                 *///? } else {
                                 float partialTick, int packedLight, int packedOverlay, float red, float green,
                                 float blue, float alpha) {
                                 //? }
                super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                               //? if >=1.21 {
                               /*packedLight, packedOverlay, colour);
                               *///? } else {
                               packedLight, packedOverlay, red, green, blue, alpha);
                               //? }

                // Detect skin model and show/hide appropriate arms
                if (animatable.hasFigure()) {
                    //? if >=1.21.2 {
                    /*FigureBlockRenderer.applyArmVisibility(model, this.getGeoModel().getTextureResource(animatable, this));
                    *///? } else {
                    FigureBlockRenderer.applyArmVisibility(model, this.getGeoModel().getTextureResource(animatable));
                    //? }
                }
            }
            //? }
        };
    }
    //? }

    //? if >=26 {
    /*// 26.1 draws nothing inline: a pass collects what to draw and submits it. The
    // three things this renderer puts on top of the box — the figure inside it, that
    // figure's face on the lid, and the collection logo — are captured off the block
    // entity while it is still in hand, then queued per bone.
    private static final com.geckolib.constant.dataticket.DataTicket<BoxOverlay> BOX_OVERLAY =
            com.geckolib.constant.dataticket.DataTicket.create("blockpops:box_overlay", BoxOverlay.class);

    private record BoxOverlay(boolean hasFigure, boolean figureExtracted,
                              float offsetX, float offsetY, float offsetZ, float scale,
                              net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState figureState,
                              ResourceLocation skinTexture,
                              ResourceLocation logoTexture, float[] logoPlacement) {
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void addRenderData(BoxBlockEntity animatable, Void relatedObject,
                              net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState renderState,
                              float partialTick) {
        super.addRenderData(animatable, relatedObject, renderState, partialTick);
        // The figure's own pass is extracted here, while the block entity is still in
        // hand; preRenderPass only submits what this captured.
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState figureState = null;
        if (animatable.hasFigure() && !animatable.isFigureExtracted()) {
            figureState = figureRenderer.createRenderState();
            figureRenderer.extractRenderState(animatable, figureState, partialTick,
                    net.minecraft.world.phys.Vec3.ZERO, null);
        }
        ((com.geckolib.renderer.base.GeoRenderState) renderState).addGeckolibData(BOX_OVERLAY, new BoxOverlay(
                animatable.hasFigure(), animatable.isFigureExtracted(),
                (float) animatable.getFigureOffsetX(), (float) animatable.getFigureOffsetY(),
                (float) animatable.getFigureOffsetZ(), (float) animatable.getFigureScale(),
                figureState,
                ((FigureModel) figureRenderer.getGeoModel()).resolveTexture(animatable),
                logoTexture(animatable), logoPlacement(animatable)));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void adjustModelBonesForRender(com.geckolib.renderer.base.RenderPassInfo renderPassInfo,
                                          com.geckolib.renderer.base.BoneSnapshots snapshots) {
        // The separately textured bones are drawn by their own submissions below, so
        // they stay out of the main pass instead of being skipped inside a draw call.
        for (com.geckolib.cache.model.GeoBone bone : renderPassInfo.model().topLevelBones()) {
            if (isSeparatelyTexturedBone(bone.name())) {
                snapshots.ifPresent(bone.name(), snapshot -> snapshot.skipRender(true));
            }
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void preRenderPass(com.geckolib.renderer.base.RenderPassInfo renderPassInfo,
                              net.minecraft.client.renderer.SubmitNodeCollector renderTasks) {
        super.preRenderPass(renderPassInfo, renderTasks);
        BoxOverlay overlay = ((com.geckolib.renderer.base.GeoRenderState) renderPassInfo.renderState())
                .getGeckolibData(BOX_OVERLAY);
        if (overlay == null) {
            return;
        }

        for (com.geckolib.cache.model.GeoBone bone : renderPassInfo.model().topLevelBones()) {
            String name = bone.name();
            if (overlay.hasFigure() && isFaceBone(name) && overlay.skinTexture() != null) {
                renderPassInfo.addPerBoneRender(bone, (info, posedBone, tasks) -> submitBone(info, posedBone, tasks,
                        net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(overlay.skinTexture())));
            } else if (name.equals("logo") && overlay.logoPlacement() != null && overlay.logoTexture() != null) {
                renderPassInfo.addPerBoneRender(bone, (info, posedBone, tasks) -> {
                    float[] placement = overlay.logoPlacement();
                    PoseStack poseStack = info.poseStack();
                    poseStack.pushPose();
                    poseStack.translate(placement[0], placement[1], placement[2]);
                    poseStack.scale(placement[3], placement[4], placement[5]);
                    submitBone(info, posedBone, tasks,
                            net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(overlay.logoTexture()));
                    poseStack.popPose();
                });
            }
        }

        if (overlay.figureState() != null) {
            PoseStack poseStack = renderPassInfo.poseStack();
            poseStack.pushPose();
            poseStack.translate(overlay.offsetX(), overlay.offsetY(), overlay.offsetZ());
            poseStack.scale(overlay.scale(), overlay.scale(), overlay.scale());
            figureRenderer.submit(overlay.figureState(), poseStack, renderTasks,
                    new net.minecraft.client.renderer.state.level.CameraRenderState());
            poseStack.popPose();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void submitBone(com.geckolib.renderer.base.RenderPassInfo info,
                                   com.geckolib.cache.model.GeoBone bone,
                                   net.minecraft.client.renderer.SubmitNodeCollector tasks,
                                   RenderType renderType) {
        tasks.submitCustomGeometry(info.poseStack(), renderType, (pose, vertexConsumer) -> {
            PoseStack poseStack = info.poseStack();
            poseStack.pushPose();
            poseStack.last().set(pose);
            bone.positionAndRender(info, vertexConsumer, info.packedLight(), info.packedOverlay(),
                    info.renderColor());
            poseStack.popPose();
        });
    }
    *///? } elif >=1.21.5 {
    /*@Override
    public void render(BoxBlockEntity animatable, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        this.currentBox = animatable;
        this.currentPartialTick = partialTick;
        this.currentCamPos = camPos;
        try {
            super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);
        } finally {
            this.currentBox = null;
        }
    }

    @Override
    public void actuallyRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                               RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                               boolean isReRender, int packedLight, int packedOverlay, int colour) {
        // First, render the box model (the main model)
        super.actuallyRender(renderState, poseStack, model, renderType, bufferSource, buffer,
                isReRender, packedLight, packedOverlay, colour);

        BoxBlockEntity animatable = this.currentBox;
        if (isReRender || animatable == null) {
            return;
        }

        // Then, render the figure model if one exists
        if (animatable.hasFigure()) {
            // Only render the 3D figure model if it hasn't been extracted
            if (!animatable.isFigureExtracted()) {
                poseStack.pushPose();

                // Apply figure offset and scale
                poseStack.translate(animatable.getFigureOffsetX(),
                                  animatable.getFigureOffsetY(),
                                  animatable.getFigureOffsetZ());
                poseStack.scale((float) animatable.getFigureScale(),
                              (float) animatable.getFigureScale(),
                              (float) animatable.getFigureScale());

                // Render the figure using separate renderer
                figureRenderer.render(animatable, this.currentPartialTick, poseStack, bufferSource,
                                    packedLight, packedOverlay, this.currentCamPos);

                poseStack.popPose();
            }

            // Always render the figure face on the box (even when extracted)
            renderFigureFace(renderState, poseStack, animatable, model, bufferSource, packedLight, packedOverlay);
        }

        // Render the collection logo on the box
        renderLogo(renderState, poseStack, animatable, model, bufferSource, packedLight, packedOverlay);
    }

    private void renderFigureFace(GeoRenderState renderState, PoseStack poseStack, BoxBlockEntity animatable,
                                  BakedGeoModel model, MultiBufferSource bufferSource,
                                  int packedLight, int packedOverlay) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return;

        // Get the player skin texture directly (bypassing figure model)
        ResourceLocation skinTexture = ((FigureModel) figureRenderer.getGeoModel()).resolveTexture(animatable);
        if (skinTexture == null) return; // Safety check

        RenderType skinRenderType = RenderType.entityTranslucent(skinTexture);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        for (GeoBone bone : model.topLevelBones()) {
            if (isFaceBone(GeoBones.name(bone))) {
                poseStack.pushPose();
                renderRecursively(renderState, poseStack, bone, skinRenderType, bufferSource, skinBuffer,
                        true, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
            }
        }
    }

    private void renderLogo(GeoRenderState renderState, PoseStack poseStack, BoxBlockEntity animatable,
                            BakedGeoModel model, MultiBufferSource bufferSource,
                            int packedLight, int packedOverlay) {
        float[] placement = logoPlacement(animatable);
        if (placement == null) return;

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoTexture(animatable));
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        for (GeoBone bone : model.topLevelBones()) {
            if (GeoBones.name(bone).equals("logo")) {
                poseStack.pushPose();
                poseStack.translate(placement[0], placement[1], placement[2]);
                poseStack.scale(placement[3], placement[4], placement[5]);
                renderRecursively(renderState, poseStack, bone, logoRenderType, bufferSource, logoBuffer,
                        true, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(GeoRenderState renderState, PoseStack poseStack, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, int packedLight, int packedOverlay, int colour) {
        if (isSeparatelyTexturedBone(GeoBones.name(bone)) && !isReRender) {
            return;
        }
        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender,
                packedLight, packedOverlay, colour);
    }
    *///? } else {
    @Override
    public void actuallyRender(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                              //? if >=1.21 {
                              /*int colour) {
                              *///? } else {
                              float red, float green, float blue, float alpha) {
                              //? }
        // First, render the box model (the main model)
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                           //? if >=1.21 {
                           /*isReRender, partialTick, packedLight, packedOverlay, colour);
                           *///? } else {
                           isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
                           //? }

        // Then, render the figure model if one exists
        if (animatable.hasFigure()) {
            // Only render the 3D figure model if it hasn't been extracted
            if (!animatable.isFigureExtracted()) {
                poseStack.pushPose();

                // Apply figure offset and scale
                poseStack.translate(animatable.getFigureOffsetX(),
                                  animatable.getFigureOffsetY(),
                                  animatable.getFigureOffsetZ());
                poseStack.scale((float) animatable.getFigureScale(),
                              (float) animatable.getFigureScale(),
                              (float) animatable.getFigureScale());

                // Render the figure using separate renderer
                figureRenderer.render(animatable, partialTick, poseStack, bufferSource,
                                    packedLight, packedOverlay);

                poseStack.popPose();
            }

            // Always render the figure face on the box (even when extracted)
            renderFigureFace(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
        }

        // Render the collection logo on the box
        renderLogo(poseStack, animatable, model, bufferSource, partialTick, packedLight, packedOverlay);
    }

    private void renderFigureFace(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                                  MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) return;

        // Get the player skin texture directly (bypassing figure model)
        //? if >=1.21.2 {
        /*ResourceLocation skinTexture = figureRenderer.getGeoModel().getTextureResource(animatable, figureRenderer);
        *///? } else {
        ResourceLocation skinTexture = figureRenderer.getGeoModel().getTextureResource(animatable);
        //? }
        if (skinTexture == null) return; // Safety check

        // Render both the base skin layer and 3D overlay layer directly from player skin
        // Use entityTranslucentCull for proper alpha blending with face culling (like player rendering)
        //? if >=1.21.2 {
        /*RenderType skinRenderType = RenderType.entityTranslucent(skinTexture);
        *///? } else {
        RenderType skinRenderType = RenderType.entityTranslucentCull(skinTexture);
        //? }
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        for (GeoBone bone : model.topLevelBones()) {
            if (isFaceBone(GeoBones.name(bone))) {
                // Base skin layer is the head front; the 3D bone is the hat overlay.
                // Both use the same render type for consistency with player rendering.
                poseStack.pushPose();
                renderRecursively(poseStack, animatable, bone, skinRenderType, bufferSource, skinBuffer,
                                //? if >=1.21 {
                                /*true, partialTick, packedLight, packedOverlay, 0xFFFFFFFF);
                                *///? } else {
                                true, partialTick, packedLight, packedOverlay, 1, 1, 1, 1);
                                //? }
                poseStack.popPose();
            }
        }
    }

    private void renderLogo(PoseStack poseStack, BoxBlockEntity animatable, BakedGeoModel model,
                           MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
        float[] placement = logoPlacement(animatable);
        if (placement == null) return;

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoTexture(animatable));
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find the generic "logo" bone
        for (GeoBone bone : model.topLevelBones()) {
            if (GeoBones.name(bone).equals("logo")) {
                poseStack.pushPose();

                // Apply translate first, then scale (matrices apply in reverse order!)
                // A vertex goes through: scale -> translate
                poseStack.translate(placement[0], placement[1], placement[2]);
                poseStack.scale(placement[3], placement[4], placement[5]);

                // Render this bone with the logo texture using a special flag
                renderRecursively(poseStack, animatable, bone, logoRenderType, bufferSource, logoBuffer,
                                //? if >=1.21 {
                                /*true, partialTick, packedLight, packedOverlay, 0xFFFFFFFF);
                                *///? } else {
                                true, partialTick, packedLight, packedOverlay, 1, 1, 1, 1);
                                //? }

                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, BoxBlockEntity animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  //? if >=1.21 {
                                  /*float partialTick, int packedLight, int packedOverlay, int colour) {
                                  *///? } else {
                                  float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
                                  //? }
        // Skip the face and logo bones during normal box rendering. They are rendered
        // separately with their own textures, which is what isReRender marks.
        if (isSeparatelyTexturedBone(GeoBones.name(bone)) && !isReRender) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                              //? if >=1.21 {
                              /*partialTick, packedLight, packedOverlay, colour);
                              *///? } else {
                              partialTick, packedLight, packedOverlay, red, green, blue, alpha);
                              //? }
    }
    //? }

    private static boolean isFaceBone(String boneName) {
        return boneName.equals("figure_face") || boneName.equals("figure_face_3d");
    }

    private static boolean isSeparatelyTexturedBone(String boneName) {
        return isFaceBone(boneName) || boneName.equals("logo");
    }

    /** The logo texture the box's collection declares. */
    private static ResourceLocation logoTexture(BoxBlockEntity animatable) {
        return CollectionRegistry.getCollection(animatable.getCollectionId())
                .map(FigureCollection::getLogoConfig)
                .map(FigureCollection.LogoConfig::getTexture)
                .orElse(null);
    }

    /**
     * The logo's position and scale as {x, y, z, scaleX, scaleY, scaleZ}, or null when
     * this box shows no logo. The block entity overrides the collection's defaults.
     */
    private static float[] logoPlacement(BoxBlockEntity animatable) {
        // Check if logo should be hidden (e.g., in UI displays)
        if (animatable.isHideLogo()) return null;

        FigureCollection collection = CollectionRegistry.getCollection(animatable.getCollectionId()).orElse(null);
        if (collection == null) return null;

        FigureCollection.LogoConfig config = collection.getLogoConfig();
        if (config == null) return null;

        return new float[] {
            animatable.getLogoPositionX() != null ? animatable.getLogoPositionX().floatValue() : config.getPositionX(),
            animatable.getLogoPositionY() != null ? animatable.getLogoPositionY().floatValue() : config.getPositionY(),
            animatable.getLogoPositionZ() != null ? animatable.getLogoPositionZ().floatValue() : config.getPositionZ(),
            animatable.getLogoScaleX() != null ? animatable.getLogoScaleX().floatValue() : config.getScaleX(),
            animatable.getLogoScaleY() != null ? animatable.getLogoScaleY().floatValue() : config.getScaleY(),
            animatable.getLogoScaleZ() != null ? animatable.getLogoScaleZ().floatValue() : config.getScaleZ(),
        };
    }
}
