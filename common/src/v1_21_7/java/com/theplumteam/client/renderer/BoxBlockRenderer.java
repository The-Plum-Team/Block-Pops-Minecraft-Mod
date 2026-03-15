package com.theplumteam.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.model.BoxBlockModel;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.util.RenderUtil;

public class BoxBlockRenderer extends GeoBlockRenderer<BoxBlockEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockRenderer.class);
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;
    // Store current animatable for use in actuallyRender
    private BoxBlockEntity currentAnimatable;
    private float currentPartialTick;
    private Vec3 currentCamPos;

    public BoxBlockRenderer() {
        super(new BoxBlockModel());
        // Create a separate renderer instance for the figure
        // Override rotateBlock to no-op since the box's rotation is already applied in the PoseStack
        this.figureRenderer = new GeoBlockRenderer<>(new FigureModel()) {
            @Override
            protected void rotateBlock(Direction facing, PoseStack poseStack) {
                // Don't apply block rotation - the box's rotation is already in the PoseStack
            }

            @Override
            public RenderType getRenderType(GeoRenderState renderState, ResourceLocation texture) {
                return RenderType.entityTranslucent(texture, true);
            }

            @Override
            public void actuallyRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                      @Nullable RenderType renderType, MultiBufferSource bufferSource,
                                      @Nullable VertexConsumer buffer, boolean isReRender,
                                      int packedLight, int packedOverlay, int renderColor) {
                if (currentAnimatable != null) {
                    FigureDefinition figureDef = currentAnimatable.getFigureDefinition();
                    float defScale = figureDef != null ? figureDef.getScale() : 1.0f;
                    if (defScale != 1.0f) {
                        poseStack.scale(defScale, defScale, defScale);
                    }
                    // BakedGeoModel is cached/shared, so we must reset all variant bones to visible first,
                    // then hide the current variant's hidden bones
                    if (figureDef != null) {
                        java.util.List<String> hiddenBones = figureDef.getHiddenBonesForSkinIndex(currentAnimatable.getAlternativeSkinIndex());
                        for (String boneName : figureDef.getAllVariantBoneNames()) {
                            model.getBone(boneName).ifPresent(bone -> {
                                bone.setHidden(false);
                                bone.setChildrenHidden(false);
                            });
                        }
                        for (String boneName : hiddenBones) {
                            model.getBone(boneName).ifPresent(bone -> {
                                bone.setHidden(true);
                                bone.setChildrenHidden(true);
                            });
                        }
                        // Hide bones that use extra textures - re-rendered by FigureBoneTextureLayer
                        for (FigureDefinition.ExtraTexture extra : figureDef.getExtraTextures()) {
                            for (String boneName : extra.bones()) {
                                model.getBone(boneName).ifPresent(bone -> {
                                    bone.setHidden(true);
                                    bone.setChildrenHidden(false);
                                });
                            }
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
                if (currentAnimatable != null) {
                    FigureDefinition fd = currentAnimatable.getFigureDefinition();
                    if (fd != null) {
                        java.util.List<String> hiddenBones = fd.getHiddenBonesForSkinIndex(currentAnimatable.getAlternativeSkinIndex());
                        if (!hiddenBones.isEmpty() && hiddenBones.contains(bone.getName())) {
                            return;
                        }
                    }
                }
                super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer,
                                      isReRender, packedLight, packedOverlay, colour);
            }
        };
        this.figureRenderer.addRenderLayer(new FigureBoneTextureLayer<>(this.figureRenderer));
    }

    @Override
    public void render(BoxBlockEntity animatable, float partialTick, PoseStack poseStack,
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay, Vec3 camPos) {
        // Store animatable and render params for use in actuallyRender
        this.currentAnimatable = animatable;
        this.currentPartialTick = partialTick;
        this.currentCamPos = camPos;

        // Render the box model (the main model) - this calls actuallyRender() internally
        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay, camPos);

        this.currentAnimatable = null;
    }

    @Override
    public void actuallyRender(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                              @Nullable RenderType renderType, MultiBufferSource bufferSource,
                              @Nullable VertexConsumer buffer, boolean isReRender,
                              int packedLight, int packedOverlay, int renderColor) {
        // Set figure_face bone visibility based on showBoxFace (e.g. Dragon Ball Z uses custom 3D models)
        if (!isReRender && currentAnimatable != null) {
            FigureDefinition figureDef = currentAnimatable.getFigureDefinition();
            boolean hideFace = figureDef != null && !figureDef.showBoxFace();
            model.getBone("figure_face").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
            model.getBone("figure_face_3d").ifPresent(bone -> { bone.setHidden(hideFace); bone.setChildrenHidden(hideFace); });
        }

        // Render the box model normally
        super.actuallyRender(renderState, poseStack, model, renderType, bufferSource, buffer,
                           isReRender, packedLight, packedOverlay, renderColor);

        // Don't render face/logo during re-render passes (e.g. render layers)
        if (isReRender || currentAnimatable == null) return;

        // Render figure face and logo - the PoseStack already has centering + rotation from GeckoLib 5
        if (currentAnimatable.hasFigure()) {
            renderFigureFace(renderState, poseStack, model, bufferSource, packedLight, packedOverlay);
        }

        if (!currentAnimatable.isHideLogo()) {
            renderCollectionLogo(renderState, poseStack, model, bufferSource, packedLight, packedOverlay);
        }

        // Render the 3D figure model inside the box
        // Done here (inside actuallyRender) so the PoseStack already has the box's centering + rotation
        if (currentAnimatable.hasFigure() && !currentAnimatable.isFigureExtracted()) {
            poseStack.pushPose();

            // Apply figure offset relative to the box (rotation already applied by the box)
            poseStack.translate(
                currentAnimatable.getFigureOffsetX(),
                currentAnimatable.getFigureOffsetY(),
                currentAnimatable.getFigureOffsetZ()
            );
            poseStack.scale((float) currentAnimatable.getFigureScale(),
                          (float) currentAnimatable.getFigureScale(),
                          (float) currentAnimatable.getFigureScale());

            // Render the figure using separate renderer
            // figureRenderer has rotateBlock overridden to no-op since box rotation is already applied
            figureRenderer.render(currentAnimatable, currentPartialTick, poseStack, bufferSource,
                                packedLight, packedOverlay, currentCamPos);

            poseStack.popPose();
        }
    }

    private void renderFigureFace(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                  MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        FigureDefinition figure = currentAnimatable.getFigureDefinition();
        if (figure == null) return;

        float[] customUV = figure.getBoxFaceUV();
        if (customUV == null && !figure.showBoxFace()) return;

        ResourceLocation skinTexture = ((FigureModel) figureRenderer.getGeoModel()).resolveTexture(currentAnimatable);
        if (skinTexture == null) return;

        // Use entityTranslucent for proper alpha blending (supports semi-transparent pixels)
        RenderType skinRenderType = RenderType.entityTranslucent(skinTexture);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinRenderType);

        if (customUV != null) {
            // Custom model: render figure_face bone with remapped UVs
            renderFaceBoneWithCustomUV(poseStack, model, skinBuffer, customUV, packedLight, packedOverlay);
        } else {
            // Default skin model: render both face bones with original UVs
            for (GeoBone bone : model.topLevelBones()) {
                if (bone.getName().equals("figure_face") || bone.getName().equals("figure_face_3d")) {
                    poseStack.pushPose();
                    renderRecursively(renderState, poseStack, bone, skinRenderType, bufferSource, skinBuffer,
                                    true, packedLight, packedOverlay, 0xFFFFFFFF);
                    poseStack.popPose();
                }
            }
        }
    }

    /**
     * Renders the figure_face bone with custom UV coordinates for non-skin-based models.
     * Uses the bone's geometry for correct positioning but remaps UVs to the face region
     * in the custom model's texture.
     *
     * @param customUV [u, v, width, height, texWidth, texHeight] in pixel coordinates
     */
    private void renderFaceBoneWithCustomUV(PoseStack poseStack, BakedGeoModel model,
                                             VertexConsumer buffer, float[] customUV,
                                             int packedLight, int packedOverlay) {
        GeoBone faceBone = null;
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("figure_face")) {
                faceBone = bone;
                break;
            }
        }
        if (faceBone == null) return;

        // Target UV range (normalized to custom texture dimensions)
        float newMinU = customUV[0] / customUV[4];
        float newMaxU = (customUV[0] + customUV[2]) / customUV[4];
        float newMinV = customUV[1] / customUV[5];
        float newMaxV = (customUV[1] + customUV[3]) / customUV[5];

        // Original UV range from box model (UV 8,8 size 8,8 on 64x64 texture)
        float origMinU = 8f / 64f;  // 0.125
        float origMaxU = 16f / 64f; // 0.25
        float origMinV = 8f / 64f;
        float origMaxV = 16f / 64f;
        float origRangeU = origMaxU - origMinU;
        float origRangeV = origMaxV - origMinV;

        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, faceBone);

        for (GeoCube cube : faceBone.getCubes()) {
            poseStack.pushPose();
            RenderUtil.translateToPivotPoint(poseStack, cube);
            RenderUtil.rotateMatrixAroundCube(poseStack, cube);
            RenderUtil.translateAwayFromPivotPoint(poseStack, cube);

            Matrix3f normalisedPoseState = poseStack.last().normal();
            Matrix4f poseState = new Matrix4f(poseStack.last().pose());

            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                // Only render the EAST face (the one with actual face UV in box model)
                if (quad.direction() != Direction.EAST) continue;

                Vector3f normal = normalisedPoseState.transform(new Vector3f(quad.normal()));
                RenderUtil.fixInvertedFlatCube(cube, normal);

                for (GeoVertex vertex : quad.vertices()) {
                    Vector4f pos = poseState.transform(new Vector4f(vertex.position(), 1));

                    // Remap UV from original box model space to custom texture space
                    float t_u = (vertex.texU() - origMinU) / origRangeU;
                    float t_v = (vertex.texV() - origMinV) / origRangeV;
                    float remappedU = newMinU + t_u * (newMaxU - newMinU);
                    float remappedV = newMinV + t_v * (newMaxV - newMinV);

                    buffer.addVertex(pos.x(), pos.y(), pos.z(), 0xFFFFFFFF, remappedU, remappedV,
                                   packedOverlay, packedLight, normal.x(), normal.y(), normal.z());
                }
            }

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private void renderCollectionLogo(GeoRenderState renderState, PoseStack poseStack, BakedGeoModel model,
                                     MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        String collectionId = currentAnimatable.getCollectionId();
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        if (collection == null) return;

        FigureCollection.LogoConfig logoConfig = collection.getLogoConfig();
        if (logoConfig == null) return;

        // Use entity-specific logo config if set, otherwise use collection defaults
        float logoPositionX = currentAnimatable.getLogoPositionX() != null ?
                              currentAnimatable.getLogoPositionX().floatValue() : logoConfig.getPositionX();
        float logoPositionY = currentAnimatable.getLogoPositionY() != null ?
                              currentAnimatable.getLogoPositionY().floatValue() : logoConfig.getPositionY();
        float logoPositionZ = currentAnimatable.getLogoPositionZ() != null ?
                              currentAnimatable.getLogoPositionZ().floatValue() : logoConfig.getPositionZ();
        float logoScaleX = currentAnimatable.getLogoScaleX() != null ?
                           currentAnimatable.getLogoScaleX().floatValue() : logoConfig.getScaleX();
        float logoScaleY = currentAnimatable.getLogoScaleY() != null ?
                           currentAnimatable.getLogoScaleY().floatValue() : logoConfig.getScaleY();
        float logoScaleZ = currentAnimatable.getLogoScaleZ() != null ?
                           currentAnimatable.getLogoScaleZ().floatValue() : logoConfig.getScaleZ();

        RenderType logoRenderType = RenderType.entityCutoutNoCull(logoConfig.getTexture());
        VertexConsumer logoBuffer = bufferSource.getBuffer(logoRenderType);

        // Find and render the logo bone
        for (GeoBone bone : model.topLevelBones()) {
            if (bone.getName().equals("logo")) {
                poseStack.pushPose();
                poseStack.translate(logoPositionX, logoPositionY, logoPositionZ);
                poseStack.scale(logoScaleX, logoScaleY, logoScaleZ);
                renderRecursively(renderState, poseStack, bone, logoRenderType, bufferSource, logoBuffer,
                                true, packedLight, packedOverlay, 0xFFFFFFFF);
                poseStack.popPose();
                break;
            }
        }
    }

    @Override
    public void renderRecursively(GeoRenderState renderState, PoseStack poseStack, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  int packedLight, int packedOverlay, int colour) {
        // Skip rendering special bones during normal box rendering
        // They will be rendered separately with their own textures
        String boneName = bone.getName();
        if (boneName.equals("figure_face") || boneName.equals("figure_face_3d")) {
            // During normal box rendering: always skip face bones
            // During re-render (face/logo pass): only render if showBoxFace is true
            if (!isReRender) return;
            if (currentAnimatable != null) {
                FigureDefinition fd = currentAnimatable.getFigureDefinition();
                if (fd != null && !fd.showBoxFace()) return;
            }
        }
        if (boneName.equals("logo") && !isReRender) {
            return;
        }

        super.renderRecursively(renderState, poseStack, bone, renderType, bufferSource, buffer, isReRender,
                              packedLight, packedOverlay, colour);
    }
}
