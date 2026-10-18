package com.theplumteam.client.gui.widget;

import com.theplumteam.client.gui.util.GuiLighting;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.FigureDraw;
import com.theplumteam.client.gui.GreyedTextures;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.renderer.FigurePreviewItemRenderer;
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
import net.minecraft.world.item.ItemStack;
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
    // The light of a token's draw, without alpha
    private static final int DRAW_LIGHT_COLOR = 0xFFD700;

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

    /** Draws one figure of this row in colour at any size, placed as its cell places it. */
    public void renderPreview(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick) {
        float cellXOffset = xOffset;
        float cellYOffset = yOffset;
        xOffset *= size / (float) FIGURE_SIZE;
        yOffset *= size / (float) FIGURE_SIZE;
        render3DFigure(graphics, figure, x, y, size, partialTick, false);
        xOffset = cellXOffset;
        yOffset = cellYOffset;
    }

    private void drawFigureName(GuiGraphics graphics, FigureDefinition figure, int figureX, int y,
                                int effectiveFigureSize, int color) {
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
            graphics.drawString(mc.font, figureName, nameX, nameY, color, true);
        }
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

            // Check if this figure has been discovered; a draw that unlocked it keeps it hidden
            // until the draw stops on it
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = ClientDiscoveryManager.isDiscovered(uniqueFigureId)
                    && !FigureDraw.hides(collectionId, figure.getId());

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
            // Each corner pixel is drawn once, so a translucent border does not brighten there
            graphics.renderOutline(figureX, y, effectiveFigureSize, effectiveFigureSize, borderColor);

            if (FigureDraw.isLit(collectionId, figure.getId())) {
                // The draw's light: a gold wash under the model and a thick gold frame
                int alpha = (int) (0x70 * FigureDraw.glow(partialTick));
                graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, (alpha << 24) | DRAW_LIGHT_COLOR);
                int frame = 2;
                int frameColor = 0xFF000000 | DRAW_LIGHT_COLOR;
                graphics.fill(figureX, y, figureX + effectiveFigureSize, y + frame, frameColor);
                graphics.fill(figureX, y + effectiveFigureSize - frame, figureX + effectiveFigureSize, y + effectiveFigureSize, frameColor);
                graphics.fill(figureX, y, figureX + frame, y + effectiveFigureSize, frameColor);
                graphics.fill(figureX + effectiveFigureSize - frame, y, figureX + effectiveFigureSize, y + effectiveFigureSize, frameColor);
            }

            if (isDiscovered) {
                // Render the 3D figure model for discovered figures
                render3DFigure(graphics, figure, figureX, y, effectiveFigureSize, partialTick, false);

                drawFigureName(graphics, figure, figureX, y, effectiveFigureSize, 0xFFFFFFFF);
            } else {
                // The figure is there to see, in black and white until it is discovered
                render3DFigure(graphics, figure, figureX, y, effectiveFigureSize, partialTick, true);
                drawFigureName(graphics, figure, figureX, y, effectiveFigureSize, 0xFFC0C0C0);
            }
        }
    }

    //? if >=1.21.4 {
    /*// From 1.21.4 a figure is drawn as an item whose model holds this screen's camera: the
    // rotation is baked there, and only the placement and size come from the widget. From
    // 1.21.6 an item is also the one way a model reaches the deferred interface.
    private final java.util.Map<String, ItemStack> previewStacks = new java.util.HashMap<>();

    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick, boolean greyed) {
        ItemStack stack = previewStacks.computeIfAbsent(figure.getId() + (greyed ? "|greyed" : ""),
                key -> FigurePreviewItemRenderer.stack(collectionId, figure.getId(), greyed));
        graphics.enableScissor(x, y, x + size, y + size);
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x + size / 2.0f + xOffset, y + size * 0.6f + yOffset);
        // The model bakes its scale so the deferred interface renders it at full resolution.
        // A figure with its own model also declares its size on this screen.
        float scale = size * modelScale * previewScale * figure.getRenderOptions().guiScale()
                / (16.0f * FigurePreviewItemRenderer.BAKED_SCALE);
        GuiPose.scale(graphics, scale, scale);
        GuiPose.translate(graphics, -8, -8);
        graphics.renderItem(stack, 0, 0);
        GuiPose.pop(graphics);
        graphics.disableScissor();
    }
    *///? } else {
    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick, boolean greyed) {
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        BoxBlockEntity renderEntity = FigureWidgetRenderer.getOrCreateRenderEntity(figure, collectionId);
        if (renderEntity == null) {
            poseStack.popPose();
            return;
        }

        // Enable scissor test to clip rendering to the figure box
        graphics.enableScissor(x, y, x + size, y + size);

        // Disable depth test to prevent z-fighting and clipping issues.
        RenderSystem.disableDepthTest();

        float centerX = (x + size / 2.0f) + xOffset;
        float centerY = (y + size * 0.6f) + yOffset;
        // Scale Z position proportionally to prevent clipping at large scales (GUI scale 1)
        float baseZ = 100.0f + zOffset;
        float centerZ = baseZ * (size / (float)FIGURE_SIZE);

        GuiLighting.flatItems();

        poseStack.translate(centerX, centerY, centerZ);
        // A figure with its own model declares its size on this screen
        float scale = size * modelScale * previewScale * figure.getRenderOptions().guiScale();
        poseStack.scale(scale, -scale, scale);

        poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRotation));
        poseStack.mulPose(Axis.ZP.rotationDegrees(zRotation));
        // The figure stands on its own origin in the middle of the cell. GeckoLib 4.7 centres
        // the block inside actuallyRender, half a block that grows with the figure's scale, so
        // that step is undone here. From 4.8 it runs in a hook this direct call never reaches.
        //? if <1.21 {
        poseStack.translate(-0.5, 0, -0.5);
        //? }

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Use the shared renderer and model from FigureWidgetRenderer
        var figureModel = FigureWidgetRenderer.getModel();
        var figureRenderer = FigureWidgetRenderer.getRenderer();

        try {
            //? if >=1.21.2 {
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
            //? if >=1.21.2 {
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
            if (greyed) {
                // A figure that cannot be turned black and white is left out rather than shown in colour
                textureResource = GreyedTextures.of(textureResource);
                if (textureResource == null) {
                    restoreDepthTest();
                    graphics.disableScissor();
                    poseStack.popPose();
                    return;
                }
            }

            // The model resolves its own texture again and ignores the one it is handed, so
            // the black-and-white copy gets its render type directly
            RenderType renderType = greyed
                    ? RenderType.entityCutoutNoCull(textureResource)
                    : figureModel.getRenderType(renderEntity, textureResource);
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

    private static void restoreDepthTest() {
        RenderSystem.enableDepthTest();
    }
    //? }

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
