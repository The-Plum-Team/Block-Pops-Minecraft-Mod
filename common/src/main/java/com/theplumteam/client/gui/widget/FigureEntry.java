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
import com.theplumteam.client.gui.style.Chip;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.renderer.FigurePreviewItemRenderer;
import com.theplumteam.client.renderer.HolographicRenderTypes;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//? }
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import com.theplumteam.figure.LocalNames;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Entry for displaying a row of figures with 3D models, as many as the list's columns
 */
public class FigureEntry extends ObjectSelectionList.Entry<FigureEntry> {
    private final Minecraft mc;
    private final List<FigureDefinition> figures; // Up to the list's columns per row
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
    // Set only by the creator's previews: a turn about each figure's upright axis, in degrees,
    // and the pose of its cycle to show. Without a view a figure keeps its camera and pose.
    private boolean hasView;
    private float previewYaw;
    private int previewPose;
    // Which of a figure's skins the creator's previews show: 0 its own, 1 onwards its variants
    private int previewSkin;
    // Whether a figure counts as discovered, asked with "collection:figure"; the creator's
    // previews set their own to show a collection as a new player or a collector would see it
    private Predicate<String> discovered = DEFAULT_DISCOVERED;
    // Which version each figure's cell shows, by "collection:figure": 0 the figure's own, 1
    // onwards its alternatives. The list shares one map with its rows, and the machine menu keeps
    // it while it is open.
    private Map<String, Integer> shownSkins = new HashMap<>();
    // Where this row last drew its first cell, as clicks come without the row's place; unknown
    // until the row is drawn, and again before every draw of the list
    private int cellsLeft;
    private int cellsTop = Integer.MIN_VALUE;

    /** Whether the player discovered a figure, asked with "collection:figure": the client's own record. */
    public static final Predicate<String> DEFAULT_DISCOVERED = ClientDiscoveryManager::isDiscovered;

    /** A cell's usual size, which the models' offsets are tuned for. */
    public static final int FIGURE_SIZE = 80;
    public static final int GRID_SPACING = 4;
    // The gap the list leaves between two rows, and what it adds above a row with a heading
    private static final int ROW_GAP = 10;
    static final int HEADING_SPACE = 16;
    // Special figures are outlined in the purple the BlockPops-Hytale project gives its specials
    private static final int SPECIAL_BORDER_COLOR = 0xE0BB8FCE;
    private static final int SPECIAL_BORDER_COLOR_UNDISCOVERED = 0x80BB8FCE;
    // The light of a token's draw, without alpha
    private static final int DRAW_LIGHT_COLOR = 0xFFD700;
    // The cells are Quick Skin's skin tiles: a dark translucent square in a light outline that
    // brightens under the mouse; a figure not yet discovered is dimmer
    private static final int CELL = 0x80000000;
    private static final int CELL_UNDISCOVERED = 0xA0000000;
    private static final int CELL_HOVER = 0x20FFFFFF;
    private static final int BORDER = 0x60FFFFFF;
    private static final int BORDER_UNDISCOVERED = 0x30FFFFFF;
    private static final int BORDER_HOVER = 0xC0FFFFFF;

    // The size of this row's cells and how many a full row holds, which the list sets
    private int cellSize = FIGURE_SIZE;
    private int columns = 4;

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

    /** The size of this row's cells and how many cells a full row of the list holds. */
    public void setGrid(int cellSize, int columns) {
        this.cellSize = cellSize;
        this.columns = columns;
    }

    public void setPreviewScale(float previewScale) {
        this.previewScale = previewScale;
    }

    public void setHeading(@org.jetbrains.annotations.Nullable String heading) {
        this.heading = heading;
    }

    /**
     * Decides which figures this entry shows as discovered, asked with "collection:figure", in
     * place of the client's own record; null goes back to that record.
     */
    public void setDiscovered(@org.jetbrains.annotations.Nullable Predicate<String> discovered) {
        this.discovered = discovered != null ? discovered : DEFAULT_DISCOVERED;
    }

    /**
     * Turns every figure this entry draws about its own upright axis, in degrees, on top of the
     * camera, as the creator's previews do when dragged round.
     */
    public void setPreviewYaw(float yaw) {
        this.previewYaw = yaw;
        this.hasView = true;
    }

    /**
     * Shows every figure this entry draws in one pose of its cycle, counted from 0 (standing) to
     * the figure's pose count less one (sitting), as the creator's previews do.
     */
    public void setPreviewPose(int pose) {
        this.previewPose = Math.max(0, pose);
        this.hasView = true;
    }

    /**
     * Shows every figure this entry draws wearing one of its skins: 0 its own, 1 onwards its
     * variants, kept within those it has, as the creator's previews do.
     */
    public void setPreviewSkin(int skin) {
        this.previewSkin = Math.max(0, skin);
        this.hasView = true;
    }

    /**
     * Shares the map of which version each figure's cell shows, by "collection:figure", so the
     * rows of a list, and a list a screen makes again, show the same ones.
     */
    public void setShownSkins(Map<String, Integer> shownSkins) {
        this.shownSkins = shownSkins;
    }

    // How many versions a cell can show: the figure's own and its alternatives. The creator's
    // previews show the skin their view names and none of this.
    private int versions(FigureDefinition figure) {
        return hasView ? 1 : 1 + figure.getAlternatives().size();
    }

    // The version a figure's cell shows, kept within those it has
    private int shownSkin(FigureDefinition figure) {
        int skin = shownSkins.getOrDefault(collectionId + ":" + figure.getId(), 0);
        return skin < versions(figure) ? Math.max(0, skin) : 0;
    }

    private String nameOf(FigureDefinition figure, int skin) {
        return (skin > 0 ? LocalNames.alternative(collectionId, figure, skin) : LocalNames.figure(collectionId, figure))
                .getString();
    }

    /** Forgets where its cells are, as the list has every row do before it draws them. */
    void forgetCells() {
        cellsTop = Integer.MIN_VALUE;
    }

    /**
     * Shows the next version of the figure whose cell holds the point, back to its own after the
     * last, with a button's click; false when the point is on no cell of a figure with versions.
     */
    boolean showNextVersion(double mouseX, double mouseY) {
        if (cellsTop == Integer.MIN_VALUE || mouseY < cellsTop || mouseY >= cellsTop + cellSize) {
            return false;
        }
        int pitch = cellSize + GRID_SPACING;
        int column = (int) Math.floor((mouseX - cellsLeft) / pitch);
        if (column < 0 || column >= figures.size() || mouseX - cellsLeft - column * pitch >= cellSize) {
            return false;
        }
        FigureDefinition figure = figures.get(column);
        int versions = versions(figure);
        if (versions < 2) {
            return false;
        }
        shownSkins.put(collectionId + ":" + figure.getId(), (shownSkin(figure) + 1) % versions);
        UiSounds.play(ModSounds.VERSION);
        return true;
    }

    /** Draws one figure of this row in colour at any size, placed as its cell places it. */
    public void renderPreview(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick) {
        renderPreview(graphics, figure, x, y, size, partialTick, false);
    }

    /**
     * Draws one figure of this row at any size, placed as its cell places it, in black and white
     * when greyed, as an undiscovered figure is shown.
     */
    public void renderPreview(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick,
                              boolean greyed) {
        renderPreview(graphics, figure, x, y, size, partialTick, greyed, false);
    }

    /**
     * As {@link #renderPreview(GuiGraphics, FigureDefinition, int, int, int, float, boolean)},
     * holographic if asked; a greyed figure is never shown holographic.
     */
    public void renderPreview(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick,
                              boolean greyed, boolean holographic) {
        renderPreview(graphics, figure, x, y, size, partialTick, greyed, holographic, 0);
    }

    // As above, wearing one of the figure's skins, 0 its own; a creator's preview wears the one
    // its view names
    private void renderPreview(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick,
                               boolean greyed, boolean holographic, int skin) {
        float cellXOffset = xOffset;
        float cellYOffset = yOffset;
        xOffset *= size / (float) FIGURE_SIZE;
        yOffset *= size / (float) FIGURE_SIZE;
        render3DFigure(graphics, figure, x, y, size, partialTick, greyed, holographic && !greyed, skin);
        xOffset = cellXOffset;
        yOffset = cellYOffset;
    }

    private void drawFigureName(GuiGraphics graphics, String figureName, int figureX, int y,
                                int effectiveFigureSize, int color) {
        String name = UiText.fit(mc.font, figureName, effectiveFigureSize - 4);
        int nameX = figureX + (effectiveFigureSize - mc.font.width(name)) / 2;
        int nameY = y + effectiveFigureSize - mc.font.lineHeight - 2;
        UiText.draw(graphics, mc.font, name, nameX, nameY, color);
    }

    // A cell of a figure with versions says which one it shows, "1/3", in a chip in its top right
    // corner, gold under the mouse because a click on the cell shows the next
    private void drawVersionCounter(GuiGraphics graphics, int cellX, int cellY, int size, int skin, int versions,
                                    boolean hovered) {
        // Above the model, which reaches into the corner: a later stratum from 1.21.6, depth
        // before it. The models reach no further than about 180, and the draw's result card and
        // the token tickets are lifted to 400, so the counter stays below those.
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 300.0f);
        Chip.draw(graphics, (skin + 1) + "/" + versions, null, cellX + size - 3, cellY + 3, hovered);
        GuiPose.pop(graphics);
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

        int effectiveFigureSize = cellSize;
        int effectiveSpacing = GRID_SPACING;

        // Calculate total width of all figures in this row
        int totalFiguresWidth = figures.size() * effectiveFigureSize + (figures.size() - 1) * effectiveSpacing;

        // Calculate starting X position to center the figures
        int startX = x + (entryWidth - totalFiguresWidth) / 2;
        cellsLeft = startX;
        cellsTop = y;

        if (heading != null) {
            // Centred over the row, between two rules as wide as a full row
            int fullRowWidth = columns * effectiveFigureSize + (columns - 1) * effectiveSpacing;
            int rowLeft = x + (entryWidth - fullRowWidth) / 2;
            String shown = UiText.fit(mc.font, heading, fullRowWidth - 24);
            int headingWidth = mc.font.width(shown);
            int headingX = x + (entryWidth - headingWidth) / 2;
            int headingGap = ROW_GAP + HEADING_SPACE;
            int headingY = y - (headingGap + mc.font.lineHeight) / 2;
            int ruleY = headingY + mc.font.lineHeight / 2 - 1;
            graphics.fill(rowLeft, ruleY, headingX - 6, ruleY + 1, SPECIAL_BORDER_COLOR_UNDISCOVERED);
            graphics.fill(headingX + headingWidth + 6, ruleY, rowLeft + fullRowWidth, ruleY + 1, SPECIAL_BORDER_COLOR_UNDISCOVERED);
            UiText.draw(graphics, mc.font, shown, headingX, headingY, SPECIAL_BORDER_COLOR);
        }

        // Render each figure in this row
        for (int i = 0; i < figures.size(); i++) {
            FigureDefinition figure = figures.get(i);
            int figureX = startX + i * (effectiveFigureSize + effectiveSpacing);

            // Check if this figure has been discovered; a draw that unlocked it keeps it hidden
            // until the draw stops on it
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = discovered.test(uniqueFigureId)
                    && !FigureDraw.hides(collectionId, figure.getId());

            // The tile, darker for an undiscovered figure
            graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize,
                    isDiscovered ? CELL : CELL_UNDISCOVERED);

            // Check if mouse is hovering over this specific figure
            boolean isFigureHovered = mouseX >= figureX && mouseX < figureX + effectiveFigureSize &&
                                     mouseY >= y && mouseY < y + effectiveFigureSize;

            if (isFigureHovered) {
                graphics.fill(figureX, y, figureX + effectiveFigureSize, y + effectiveFigureSize, CELL_HOVER);
            }

            // Its outline: light, brighter under the mouse, purple for a special
            int borderColor = isFigureHovered ? BORDER_HOVER : isDiscovered ? BORDER : BORDER_UNDISCOVERED;
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

            // The version the cell shows: the figure's own until a click on it shows another
            int skin = shownSkin(figure);
            if (isDiscovered) {
                // Render the 3D figure model for discovered figures
                renderPreview(graphics, figure, figureX, y, effectiveFigureSize, partialTick, false, false, skin);

                drawFigureName(graphics, nameOf(figure, skin), figureX, y, effectiveFigureSize, 0xFFFFFFFF);
            } else {
                // The figure is there to see, in black and white until it is discovered
                renderPreview(graphics, figure, figureX, y, effectiveFigureSize, partialTick, true, false, skin);
                drawFigureName(graphics, nameOf(figure, skin), figureX, y, effectiveFigureSize, 0xFFB0B0B0);
            }
            int versions = versions(figure);
            if (versions > 1) {
                drawVersionCounter(graphics, figureX, y, effectiveFigureSize, skin, versions, isFigureHovered);
            }
        }
    }

    //? if >=1.21.4 {
    /*// From 1.21.4 a figure is drawn as an item whose model holds this screen's camera: the
    // rotation is baked there, and only the placement and size come from the widget. From
    // 1.21.6 an item is also the one way a model reaches the deferred interface.
    private final java.util.Map<String, ItemStack> previewStacks = new java.util.HashMap<>();

    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick,
                                boolean greyed, boolean holographic, int skin) {
        ItemStack stack = previewStacks.computeIfAbsent(
                figure.getId() + (greyed ? "|greyed" : "") + (holographic ? "|holographic" : "")
                        + (skin > 0 ? "|skin" + skin : ""),
                key -> FigurePreviewItemRenderer.stack(collectionId, figure.getId(), greyed, holographic, skin));
        if (hasView) {
            FigurePreviewItemRenderer.setView(stack, previewYaw, previewPose, previewSkin);
        }
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
    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick,
                                boolean greyed, boolean holographic, int skin) {
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        // A preview with a view poses its own render entity, apart from the lists' standing one
        BoxBlockEntity renderEntity = FigureWidgetRenderer.getOrCreateRenderEntity(figure, collectionId, hasView);
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
        if (hasView) {
            // The figure stands on the origin, so a preview's turn goes round it
            poseStack.mulPose(Axis.YP.rotationDegrees(previewYaw));
            renderEntity.setPoseIndex(previewPose);
            renderEntity.setAlternativeSkinIndexForPreview(previewSkin);
        } else {
            // Every draw of the figure shares this entity, so each says which skin it wears
            renderEntity.setAlternativeSkinIndexForPreview(skin);
        }
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
            RenderType renderType;
            if (greyed) {
                renderType = RenderType.entityCutoutNoCull(textureResource);
            } else if (holographic) {
                renderType = HolographicRenderTypes.entity(textureResource);
            } else {
                renderType = figureModel.getRenderType(renderEntity, textureResource);
            }
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
            return Component.translatable("blockpops.machine.narration.empty_row");
        } else if (figures.size() == 1) {
            FigureDefinition figure = figures.get(0);
            String uniqueFigureId = collectionId + ":" + figure.getId();
            boolean isDiscovered = discovered.test(uniqueFigureId);
            return isDiscovered ? LocalNames.figure(collectionId, figure)
                    : Component.translatable("blockpops.machine.narration.undiscovered");
        } else {
            int discoveredCount = 0;
            for (FigureDefinition figure : figures) {
                String uniqueFigureId = collectionId + ":" + figure.getId();
                if (discovered.test(uniqueFigureId)) {
                    discoveredCount++;
                }
            }
            return Component.translatable("blockpops.machine.narration.discovered", discoveredCount, figures.size());
        }
    }

    public List<FigureDefinition> getFigures() {
        return figures;
    }
}
