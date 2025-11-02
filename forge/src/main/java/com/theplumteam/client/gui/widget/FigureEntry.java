package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.model.FigureModel;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry for displaying a row of up to 4 figures with 3D models
 */
public class FigureEntry extends ObjectSelectionList.Entry<FigureEntry> {
    private final Minecraft mc;
    private final List<FigureDefinition> figures; // Up to 4 figures per row
    private final String collectionId;
    private final FigureModel figureModel;
    private final GeoBlockRenderer<BoxBlockEntity> figureRenderer;

    // Configuration from parent widget
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 70.0f;
    private float zRotation = 0.0f;
    private float xOffset = -60.0f;
    private float yOffset = 15.0f;
    private float zOffset = 0.0f;

    private static final int FIGURE_SIZE = 80;
    private static final int GRID_SPACING = 4;

    public FigureEntry(List<FigureDefinition> figures, String collectionId) {
        this.mc = Minecraft.getInstance();
        this.figures = new ArrayList<>(figures); // Copy the list
        this.collectionId = collectionId;
        this.figureModel = new FigureModel();
        this.figureRenderer = new GeoBlockRenderer<>(figureModel);
    }

    public void setConfiguration(float modelScale, float xRotation, float yRotation, float zRotation,
                                float xOffset, float yOffset, float zOffset) {
        this.modelScale = modelScale;
        this.xRotation = xRotation;
        this.yRotation = yRotation;
        this.zRotation = zRotation;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;
    }

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {

        // Calculate total width of all figures in this row
        int totalFiguresWidth = figures.size() * FIGURE_SIZE + (figures.size() - 1) * GRID_SPACING;

        // Calculate starting X position to center the figures
        int startX = x + (entryWidth - totalFiguresWidth) / 2;

        // Render each figure in this row (up to 4)
        for (int i = 0; i < figures.size(); i++) {
            FigureDefinition figure = figures.get(i);
            int figureX = startX + i * (FIGURE_SIZE + GRID_SPACING);

            // Check if this figure has been discovered
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = ClientDiscoveryManager.isDiscovered(uniqueFigureId);

            // Draw background (darker for undiscovered figures)
            if (isDiscovered) {
                graphics.fill(figureX, y, figureX + FIGURE_SIZE, y + FIGURE_SIZE, 0x30FFFFFF);
            } else {
                graphics.fill(figureX, y, figureX + FIGURE_SIZE, y + FIGURE_SIZE, 0x50000000);
            }

            // Check if mouse is hovering over this specific figure
            boolean isFigureHovered = mouseX >= figureX && mouseX < figureX + FIGURE_SIZE &&
                                     mouseY >= y && mouseY < y + FIGURE_SIZE;

            if (isFigureHovered) {
                if (isDiscovered) {
                    graphics.fill(figureX, y, figureX + FIGURE_SIZE, y + FIGURE_SIZE, 0x40FFFFFF);
                } else {
                    graphics.fill(figureX, y, figureX + FIGURE_SIZE, y + FIGURE_SIZE, 0x60000000);
                }
            }

            // Draw border around each figure
            int borderColor = isDiscovered ? 0x80FFFFFF : 0x60808080; // White for discovered, gray for undiscovered
            // Top border
            graphics.fill(figureX, y, figureX + FIGURE_SIZE, y + 1, borderColor);
            // Bottom border
            graphics.fill(figureX, y + FIGURE_SIZE - 1, figureX + FIGURE_SIZE, y + FIGURE_SIZE, borderColor);
            // Left border
            graphics.fill(figureX, y, figureX + 1, y + FIGURE_SIZE, borderColor);
            // Right border
            graphics.fill(figureX + FIGURE_SIZE - 1, y, figureX + FIGURE_SIZE, y + FIGURE_SIZE, borderColor);

            if (isDiscovered) {
                // Render the 3D figure model for discovered figures
                render3DFigure(graphics, figure, figureX, y, FIGURE_SIZE, partialTick);

                // Draw figure name
                Component figureName = Component.literal(figure.getName());
                int nameWidth = mc.font.width(figureName);
                if (nameWidth > FIGURE_SIZE - 4) {
                    String truncated = figure.getName();
                    while (mc.font.width(truncated + "...") > FIGURE_SIZE - 4 && truncated.length() > 0) {
                        truncated = truncated.substring(0, truncated.length() - 1);
                    }
                    figureName = Component.literal(truncated + "...");
                }

                int nameX = figureX + (FIGURE_SIZE - mc.font.width(figureName)) / 2;
                int nameY = y + FIGURE_SIZE - mc.font.lineHeight - 2;
                graphics.drawString(mc.font, figureName, nameX, nameY, 0xFFFFFF, true);
            } else {
                // Draw a question mark for undiscovered figures
                Component questionMark = Component.literal("?");
                int qmWidth = mc.font.width(questionMark);
                int qmX = figureX + (FIGURE_SIZE - qmWidth) / 2;
                int qmY = y + (FIGURE_SIZE - mc.font.lineHeight) / 2;
                graphics.drawString(mc.font, questionMark, qmX, qmY, 0x808080, false);

                // Don't show name for undiscovered figures
            }
        }
    }

    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick) {
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        BoxBlockEntity renderEntity = getOrCreateRenderEntity(figure);
        if (renderEntity == null) {
            poseStack.popPose();
            return;
        }

        // Enable scissor test to clip rendering to the figure box
        graphics.enableScissor(x, y, x + size, y + size);

        // Disable depth test to prevent z-fighting and clipping issues
        RenderSystem.disableDepthTest();

        float centerX = (x + size / 2.0f) + xOffset;
        float centerY = (y + size * 0.6f) + yOffset;
        float centerZ = 100.0f + zOffset; // Increased from 50.0f to prevent near-plane clipping

        Lighting.setupForFlatItems();

        poseStack.translate(centerX, centerY, centerZ);
        float scale = size * modelScale;
        poseStack.scale(scale, -scale, scale);

        poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRotation));
        poseStack.mulPose(Axis.ZP.rotationDegrees(zRotation));

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        try {
            ResourceLocation modelResource = figureModel.getModelResource(renderEntity);
            if (modelResource == null) {
                RenderSystem.enableDepthTest();
                graphics.disableScissor();
                poseStack.popPose();
                return;
            }

            BakedGeoModel bakedModel = figureModel.getBakedModel(modelResource);
            ResourceLocation textureResource = figureModel.getTextureResource(renderEntity);
            if (textureResource == null) {
                RenderSystem.enableDepthTest();
                graphics.disableScissor();
                poseStack.popPose();
                return;
            }

            RenderType renderType = figureModel.getRenderType(renderEntity, textureResource);
            VertexConsumer buffer = bufferSource.getBuffer(renderType);

            figureRenderer.actuallyRender(
                poseStack,
                renderEntity,
                bakedModel,
                renderType,
                bufferSource,
                buffer,
                false,
                partialTick,
                15728880,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                1f, 1f, 1f, 1f
            );

            bufferSource.endBatch();
        } catch (Exception e) {
            // Silently fail
        }

        // Re-enable depth test and disable scissor
        RenderSystem.enableDepthTest();
        graphics.disableScissor();

        Lighting.setupFor3DItems();
        poseStack.popPose();
    }

    private BoxBlockEntity getOrCreateRenderEntity(FigureDefinition figure) {
        try {
            BoxBlockEntity entity = new BoxBlockEntity(BlockPos.ZERO, Blocks.AIR.defaultBlockState());
            entity.setFigureId(figure.getId());
            entity.setCollectionIdOverride(collectionId);
            return entity;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public Component getNarration() {
        if (figures.isEmpty()) {
            return Component.literal("Empty row");
        } else if (figures.size() == 1) {
            FigureDefinition figure = figures.get(0);
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = ClientDiscoveryManager.isDiscovered(uniqueFigureId);
            return Component.literal(isDiscovered ? figure.getName() : "Undiscovered Figure");
        } else {
            int discoveredCount = 0;
            for (FigureDefinition figure : figures) {
                String uniqueFigureId = collectionId + ":" + figure.getId();
                if (ClientDiscoveryManager.isDiscovered(uniqueFigureId)) {
                    discoveredCount++;
                }
            }
            return Component.literal(discoveredCount + " of " + figures.size() + " figures discovered");
        }
    }

    public List<FigureDefinition> getFigures() {
        return figures;
    }
}
