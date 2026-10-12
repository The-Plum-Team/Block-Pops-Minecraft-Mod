package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.util.GuiLighting;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry for displaying a row of up to 4 figures with 3D models
 */
public class FigureEntry extends ObjectSelectionList.Entry<FigureEntry> {
    private final Minecraft mc;
    private final List<FigureDefinition> figures; // Up to 4 figures per row
    private final String collectionId;

    // Configuration from parent widget
    private float modelScale = 1.0f;
    private float xRotation = 0.0f;
    private float yRotation = 70.0f;
    private float zRotation = 0.0f;
    private float xOffset = -60.0f;
    private float yOffset = 15.0f;
    private float zOffset = 0.0f;
    // Shrinks the preview of models wider than a figure, so they stay inside their cell
    private float previewScale = 1.0f;
    // Written above this row, in the widened gap after the row before, to head a group of figures
    @org.jetbrains.annotations.Nullable
    private String heading;

    private static final int FIGURE_SIZE = 80;
    private static final int GRID_SPACING = 4;
    // The gap the list leaves between two rows, and what it adds above a row with a heading
    private static final int ROW_GAP = 10;
    static final int HEADING_SPACE = 16;
    // Special figures are outlined in the purple the BlockPops-Hytale project gives its specials
    private static final int SPECIAL_BORDER_COLOR = 0xE0BB8FCE;
    private static final int SPECIAL_BORDER_COLOR_UNDISCOVERED = 0x80BB8FCE;

    public FigureEntry(List<FigureDefinition> figures, String collectionId) {
        this.mc = Minecraft.getInstance();
        this.figures = new ArrayList<>(figures); // Copy the list
        this.collectionId = collectionId;
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

    public void setPreviewScale(float previewScale) {
        this.previewScale = previewScale;
    }

    public void setHeading(@org.jetbrains.annotations.Nullable String heading) {
        this.heading = heading;
    }

    //? if >=26 {
    /*// 26.1 gives an entry its own bounds and asks it to extract its contents rather
    // than passing the row geometry in on every draw.
    @Override
    public void extractContent(GuiGraphics graphics, int mouseX, int mouseY,
                               boolean isMouseOver, float partialTick) {
        render(graphics, 0, getY(), getX(), getWidth(), getHeight(), mouseX, mouseY, isMouseOver, partialTick);
    }

    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
    *///? } else {
    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
    //? }

        // Calculate effective figure size - scale when using inverse scale mode
        int effectiveFigureSize = FIGURE_SIZE;
        int effectiveSpacing = GRID_SPACING;
        if (GuiScaleManager.isUsingInverseScale()) {
            float scale = GuiScaleManager.getRenderScaleFactor();
            effectiveFigureSize = (int)(FIGURE_SIZE * scale);
            effectiveSpacing = (int)(GRID_SPACING * scale);
        }

        // Calculate total width of all figures in this row
        int totalFiguresWidth = figures.size() * effectiveFigureSize + (figures.size() - 1) * effectiveSpacing;

        // Calculate starting X position to center the figures
        int startX = x + (entryWidth - totalFiguresWidth) / 2;

        if (heading != null) {
            // Centred over the row, between two rules as wide as a full row
            int fullRowWidth = 4 * effectiveFigureSize + 3 * effectiveSpacing;
            int rowLeft = x + (entryWidth - fullRowWidth) / 2;
            int headingWidth = mc.font.width(heading);
            int headingX = x + (entryWidth - headingWidth) / 2;
            int headingGap = ROW_GAP + HEADING_SPACE;
            if (GuiScaleManager.isUsingInverseScale()) {
                headingGap = (int)(headingGap * GuiScaleManager.getRenderScaleFactor());
            }
            int headingY = y - (headingGap + mc.font.lineHeight) / 2;
            int ruleY = headingY + mc.font.lineHeight / 2 - 1;
            graphics.fill(rowLeft, ruleY, headingX - 6, ruleY + 1, SPECIAL_BORDER_COLOR_UNDISCOVERED);
            graphics.fill(headingX + headingWidth + 6, ruleY, rowLeft + fullRowWidth, ruleY + 1, SPECIAL_BORDER_COLOR_UNDISCOVERED);
            graphics.drawString(mc.font, heading, headingX, headingY, SPECIAL_BORDER_COLOR, false);
        }

        // Render each figure in this row (up to 4)
        for (int i = 0; i < figures.size(); i++) {
            FigureDefinition figure = figures.get(i);
            int figureX = startX + i * (effectiveFigureSize + effectiveSpacing);

            // Check if this figure has been discovered
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = ClientDiscoveryManager.isDiscovered(uniqueFigureId);

            // Draw background (darker for undiscovered figures)
            if (isDiscovered) {
                graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, 0x30FFFFFF);
            } else {
                graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, 0x50000000);
            }

            // Check if mouse is hovering over this specific figure
            boolean isFigureHovered = mouseX >= figureX && mouseX < figureX + effectiveFigureSize &&
                                     mouseY >= y && mouseY < y + effectiveFigureSize;

            if (isFigureHovered) {
                if (isDiscovered) {
                    graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, 0x40FFFFFF);
                } else {
                    graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, 0x60000000);
                }
            }

            // Draw border around each figure
            int borderColor = isDiscovered ? 0x80FFFFFF : 0x60808080; // White for discovered, gray for undiscovered
            if (figure.isSpecial()) {
                borderColor = isDiscovered ? SPECIAL_BORDER_COLOR : SPECIAL_BORDER_COLOR_UNDISCOVERED;
            }
            // Top border
            graphics.fill(figureX, y, figureX + effectiveFigureSize, y + 1, borderColor);
            // Bottom border
            graphics.fill(figureX, y + effectiveFigureSize - 1, figureX + effectiveFigureSize, y + effectiveFigureSize, borderColor);
            // Left border
            graphics.fill(figureX, y, figureX + 1, y + effectiveFigureSize, borderColor);
            // Right border
            graphics.fill(figureX + effectiveFigureSize - 1, y, figureX + effectiveFigureSize, y + effectiveFigureSize, borderColor);

            if (isDiscovered) {
                // Render the 3D figure model for discovered figures
                render3DFigure(graphics, figure, figureX, y, effectiveFigureSize, partialTick);

                // Draw figure name only if GUI scale is less than 3
                int guiScale = mc.options.guiScale().get();
                if (guiScale < 3) {
                    Component figureName = Component.literal(figure.getName());
                    int nameWidth = mc.font.width(figureName);
                    if (nameWidth > effectiveFigureSize - 4) {
                        String truncated = figure.getName();
                        while (mc.font.width(truncated + "...") > effectiveFigureSize - 4 && truncated.length() > 0) {
                            truncated = truncated.substring(0, truncated.length() - 1);
                        }
                        figureName = Component.literal(truncated + "...");
                    }

                    int nameX = figureX + (effectiveFigureSize - mc.font.width(figureName)) / 2;
                    int nameY = y + effectiveFigureSize - mc.font.lineHeight - 2;
                    graphics.drawString(mc.font, figureName, nameX, nameY, 0xFFFFFFFF, true);
                }
            } else {
                // Draw a question mark for undiscovered figures
                Component questionMark = Component.literal("?");
                int qmWidth = mc.font.width(questionMark);
                int qmX = figureX + (effectiveFigureSize - qmWidth) / 2;
                int qmY = y + (effectiveFigureSize - mc.font.lineHeight) / 2;
                graphics.drawString(mc.font, questionMark, qmX, qmY, 0xFF808080, false);

                // Don't show name for undiscovered figures
            }
        }
    }

    //? if >=26 {
    /*// 26.1 routes every 3D element in a screen through picture-in-picture, and
    // its GUI accepts only the state types vanilla declares: an entity, a skin, a
    // book, a sign, a map. A GeckoLib model is none of those, so drawing one here
    // needs a PictureInPictureRenderState of this mod's own plus a renderer
    // registered against GuiRenderer. Until that exists the row shows the figure's
    // face flat, which the caller already falls back to when no model resolves.
    private void render3DFigure(GuiGraphicsExtractor graphics, FigureDefinition figure,
                                int x, int y, int size, float partialTick) {
    }
    *///? } else {
    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick) {
        // From 1.21.6 the GUI's own pose is two-dimensional, so the model is placed in a
        // stack of its own and drawn straight into the buffer source below.
        //? if >=1.21.6 {
        /*PoseStack poseStack = new PoseStack();
        *///? } else {
        PoseStack poseStack = graphics.pose();
        //? }
        poseStack.pushPose();

        BoxBlockEntity renderEntity = FigureWidgetRenderer.getOrCreateRenderEntity(figure, collectionId);
        if (renderEntity == null) {
            poseStack.popPose();
            return;
        }

        // Enable scissor test to clip rendering to the figure box
        graphics.enableScissor(x, y, x + size, y + size);

        // Disable depth test to prevent z-fighting and clipping issues.
        // 1.21.5 removed the global depth state; the render type owns it there.
        //? if <1.21.5 {
        RenderSystem.disableDepthTest();
        //? }

        float centerX = (x + size / 2.0f) + xOffset;
        float centerY = (y + size * 0.6f) + yOffset;
        // Scale Z position proportionally to prevent clipping at large scales (GUI scale 1)
        float baseZ = 100.0f + zOffset;
        float centerZ = baseZ * (size / (float)FIGURE_SIZE);

        GuiLighting.flatItems();

        poseStack.translate(centerX, centerY, centerZ);
        float scale = size * modelScale * previewScale;
        poseStack.scale(scale, -scale, scale);

        poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRotation));
        poseStack.mulPose(Axis.ZP.rotationDegrees(zRotation));

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Use the shared renderer and model from FigureWidgetRenderer
        var figureModel = FigureWidgetRenderer.getModel();
        var figureRenderer = FigureWidgetRenderer.getRenderer();

        try {
            //? if >=1.21.5 {
            /*// GeckoLib 5 resolves everything from a render state, so the widget builds
            // the same state the block renderer would and draws from that.
            software.bernie.geckolib.renderer.base.GeoRenderState renderState =
                    figureRenderer.fillRenderState(renderEntity, null,
                            new software.bernie.geckolib.renderer.base.GeoRenderState.Impl(), partialTick);
            ResourceLocation modelResource = figureModel.getModelResource(renderState);
            *///? } elif >=1.21.2 {
            /*ResourceLocation modelResource = figureModel.getModelResource(renderEntity, null);
            *///? } else {
            ResourceLocation modelResource = figureModel.getModelResource(renderEntity);
            //? }
            if (modelResource == null) {
                restoreDepthTest();
                graphics.disableScissor();
                poseStack.popPose();
                return;
            }

            BakedGeoModel bakedModel = figureModel.getBakedModel(modelResource);
            //? if >=1.21.5 {
            /*ResourceLocation textureResource = figureModel.getTextureResource(renderState);
            *///? } elif >=1.21.2 {
            /*ResourceLocation textureResource = figureModel.getTextureResource(renderEntity, null);
            *///? } else {
            ResourceLocation textureResource = figureModel.getTextureResource(renderEntity);
            //? }
            if (textureResource == null) {
                restoreDepthTest();
                graphics.disableScissor();
                poseStack.popPose();
                return;
            }

            //? if >=1.21.5 {
            /*RenderType renderType = figureModel.getRenderType(renderState, textureResource);
            VertexConsumer buffer = bufferSource.getBuffer(renderType);

            figureRenderer.actuallyRender(
                renderState,
                poseStack,
                bakedModel,
                renderType,
                bufferSource,
                buffer,
                false,
                15728880,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF
            );
            *///? } else {
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
                //? if >=1.21 {
                /*0xFFFFFFFF
                *///? } else {
                1f, 1f, 1f, 1f
                //? }
            );
            //? }

            bufferSource.endBatch();
        } catch (Exception e) {
            // Silently fail
        }

        // Re-enable depth test and disable scissor
        restoreDepthTest();
        graphics.disableScissor();

        GuiLighting.items3D();
        poseStack.popPose();
    }
    //? }

    /** Restores the global depth test, which only exists below 1.21.5. */
    private static void restoreDepthTest() {
        //? if <1.21.5 {
        RenderSystem.enableDepthTest();
        //? }
    }

    @Override
    //? if >=26 {
    /*public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent bpEvent, boolean bpDouble) {
        double mouseX = bpEvent.x();
        double mouseY = bpEvent.y();
        int button = bpEvent.button();
        return false;
    }
    *///? } else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }
    //? }

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
