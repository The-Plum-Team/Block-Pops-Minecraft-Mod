package com.theplumteam.client.gui;

import com.theplumteam.client.gui.util.GuiPose;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
//? if >=1.21.5 {
/*import net.minecraft.client.renderer.RenderType;
*///? } elif >=1.21 {
/*import com.mojang.blaze3d.vertex.BufferUploader;
*///? }
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.client.config.ClientConfig;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.widget.ColorSelectionButton;
import com.theplumteam.network.SetFavoriteColorPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen displayed when a player joins a world for the first time.
 * Allows them to choose their favorite color which will be used for their figure box
 * in the World Players collection.
 */
public class FavoriteColorSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(FavoriteColorSelectionScreen.class);

    @Nullable
    private PopBlockColor selectedColor = null;
    private Button doneButton;
    private Button toggleFigureButton;
    private boolean showFigureInBox = true;
    private final List<ColorSelectionButton> colorButtons = new ArrayList<>();

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // Constants
    private static final int MIN_PANEL_WIDTH = 400;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 450;

    // Transformation values for box rendering
    private float rotationX = 342.3f;  // Default rotation
    private float rotationY = 335.9f;
    private float rotationZ = 0.0f;
    private float scale = 1.2f;
    private float offsetX = 198.0f;
    private float offsetY = -134.0f;
    private float offsetZ = 0.0f;

    // Box container scale (scales the grid area only, not the entire panel)
    private float containerScale = 1.2f;

    // Track if we forced GUI scale change
    private boolean guiScaleForced = false;
    // Track if the screen is in the process of closing (to prevent init() from re-applying scale)
    private boolean isClosing = false;

    public FavoriteColorSelectionScreen() {
        super(Component.literal("Choose Your Favorite Color"));
        BlockPopsMod.logDebug("FavoriteColorSelectionScreen created");
    }

    @Override
    protected void init() {
        // Don't change scale if we're in the process of closing
        // (resizeDisplay() during restore triggers init() again)
        if (isClosing) {
            super.init();
            return;
        }

        // Enforce GUI Scale - Force it even if shaders are detected (true parameter)
        // Only attempt to change scale once per screen instance
        if (!guiScaleForced) {
            if (GuiScaleManager.setMenuGuiScale(GuiScaleManager.getOptimalMenuScale(), true)) {
                guiScaleForced = true;
                // If scale changed, the screen will be re-initialized by the engine
                return;
            }
        }

        super.init();
        clearWidgets();
        colorButtons.clear();

        // Calculate panel dimensions
        calculatePanelDimensions();

        int scaledPadding = 20;
        int scaledSpacing = 10;
        int scaledComponentHeight = 24;

        // Title and description
        int titleY = panelY + scaledPadding;

        // Color grid setup - scale button size with container scale
        int buttonSize = (int)(60 * containerScale); // Size of each color button
        int gridCols = 4; // 4x4 grid
        int gridRows = 4;
        int gridWidth = (gridCols * buttonSize) + ((gridCols - 1) * scaledSpacing);
        int gridHeight = (gridRows * buttonSize) + ((gridRows - 1) * scaledSpacing);

        // Center the grid horizontally
        int gridStartX = panelX + (panelWidth - gridWidth) / 2;

        // Position grid below title and description
        int descriptionHeight = font.lineHeight * 3; // Title + description (2 lines)
        int gridStartY = titleY + descriptionHeight + scaledPadding;

        // Create 4x4 grid of color buttons
        PopBlockColor[] colors = PopBlockColor.values();
        for (int i = 0; i < colors.length; i++) {
            PopBlockColor color = colors[i];
            int row = i / gridCols;
            int col = i % gridCols;
            int x = gridStartX + (col * (buttonSize + scaledSpacing));
            int y = gridStartY + (row * (buttonSize + scaledSpacing));

            ColorSelectionButton colorButton = new ColorSelectionButton(x, y, buttonSize, color, this);
            this.addRenderableWidget(colorButton);
            colorButtons.add(colorButton);
        }

        // Bottom buttons - Toggle Figure button on left, Done button on right
        int buttonWidth = 200;
        int toggleButtonWidth = 120;
        int buttonSpacing = 10;
        int totalButtonWidth = buttonWidth + toggleButtonWidth + buttonSpacing;
        int buttonsStartX = panelX + (panelWidth - totalButtonWidth) / 2;
        int doneButtonY = panelY + panelHeight - scaledPadding - scaledComponentHeight;

        // Toggle Figure button (left of Done)
        toggleFigureButton = Button.builder(Component.literal(showFigureInBox ? "Figure: ON" : "Figure: OFF"), button -> {
            showFigureInBox = !showFigureInBox;
            button.setMessage(Component.literal(showFigureInBox ? "Figure: ON" : "Figure: OFF"));
            updateFigureVisibility();
        }).bounds(buttonsStartX, doneButtonY, toggleButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(toggleFigureButton);

        // Done button (right of Toggle)
        int doneButtonX = buttonsStartX + toggleButtonWidth + buttonSpacing;
        doneButton = Button.builder(Component.literal("Done"), button -> {
            if (selectedColor != null) {
                BlockPopsMod.logDebug("Player confirmed favorite color choice: {}", selectedColor.getSerializedName());
                // Send packet to server using cross-platform networking
                SetFavoriteColorPacket packet = new SetFavoriteColorPacket(selectedColor.getSerializedName());
                packet.sendToServer();
                this.onClose();
            }
        }).bounds(doneButtonX, doneButtonY, buttonWidth, scaledComponentHeight).build();

        doneButton.active = false; // Initially disabled
        this.addRenderableWidget(doneButton);

        // Initialize button transforms
        updateButtonTransforms();
    }

    /**
     * Update all color buttons with current transformation values
     */
    private void updateButtonTransforms() {
        for (ColorSelectionButton button : colorButtons) {
            button.setTransforms(rotationX, rotationY, rotationZ, scale, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Update all color buttons with current figure visibility setting
     */
    private void updateFigureVisibility() {
        for (ColorSelectionButton button : colorButtons) {
            button.setShowFigure(showFigureInBox);
        }
    }

    /**
     * Calculate panel dimensions based on screen size
     */
    private void calculatePanelDimensions() {
        int desiredWidth = (int)(this.width * 0.5f);
        int desiredHeight = (int)(this.height * 0.7f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, this.width - 60)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                this.height - 60
        );

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    /**
     * Called when a color is selected
     */
    public void setSelectedColor(PopBlockColor color) {
        this.selectedColor = color;
        this.doneButton.active = true; // Enable Done button

        // Update button visual states
        for (ColorSelectionButton button : colorButtons) {
            button.setSelected(button.getColor() == color);
        }

        LOGGER.debug("Selected color: {}", color.getSerializedName());
    }

    @Override
    public void onClose() {
        // Mark as closing to prevent init() from re-applying scale during resizeDisplay()
        isClosing = true;
        // Restore original GUI scale when screen is closed
        restoreGuiScaleIfNeeded();
        super.onClose();
    }

    @Override
    public void removed() {
        // Mark as closing in case removed() is called directly
        isClosing = true;
        // Also restore here as a fallback
        restoreGuiScaleIfNeeded();
        super.removed();
    }

    private void restoreGuiScaleIfNeeded() {
        if (guiScaleForced) {
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
            BlockPopsMod.logDebug("Restored original GUI scale");
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render animated starry background
        renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render title and description on top
        renderTitleAndDescription(graphics);
    }

    /**
     * Render animated starry background
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        // 1. Fill with black background as a base layer
        // Modified to force default black background (ignoring ClientConfig which may carry over
        // colors from the Claw Machine/Collection screen).
        int bgColor = 0xFF000000;
        graphics.fill(0, 0, this.width, this.height, bgColor);

        // 2. Render the moving star pattern
        renderStarPattern(graphics, partialTick);

        // 3. Close the background layer so the panel and its text draw above it
        GuiPose.nextStratum(graphics);
    }

    /**
     * Render the animated star pattern (OPTIMIZED - pre-tiled texture cache, 1 draw call)
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        // Calculate smooth scrolling offset
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        // Apply star color tint and opacity from config
        ClientConfig config = ClientConfig.getInstance();

        // Use the pre-tiled cached texture
        ResourceLocation cacheTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();
        int starWidth = this.width;
        int starHeight = this.height;

        //? if >=1.21.6 {
        /*// 1.21.6 selects the GUI shader by render pipeline rather than render type.
        int argb = ((int) (config.starOpacity * 255) << 24)
                | ((int) (config.starColorR * 255) << 16)
                | ((int) (config.starColorG * 255) << 8)
                | (int) (config.starColorB * 255);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, cacheTexture,
                0, 0, (float) offsetX, 0.0f, starWidth, starHeight, cacheWidth, cacheHeight, argb);
        *///? } elif >=1.21.5 {
        /*// 1.21.5 tints and blends inside the GUI render type, so the scrolling quad is
        // one blit with a packed colour instead of a hand-built buffer.
        int argb = ((int) (config.starOpacity * 255) << 24)
                | ((int) (config.starColorR * 255) << 16)
                | ((int) (config.starColorG * 255) << 8)
                | (int) (config.starColorB * 255);
        graphics.blit(RenderType::guiTextured, cacheTexture, 0, 0, (float) offsetX, 0.0f,
                starWidth, starHeight, cacheWidth, cacheHeight, argb);
        *///? } elif >=1.21.2 {
        /*float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);

        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(pose.last().pose(), 0, starHeight, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, starHeight, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        *///? } elif >=1.21 {
        /*float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.addVertex(pose.last().pose(), 0, starHeight, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, starHeight, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), starWidth, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        *///? } else {
        float u0 = (float) offsetX / (float) cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float) starWidth / (float) cacheWidth);
        float v1 = (float) starHeight / (float) cacheHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(config.starColorR, config.starColorG, config.starColorB, config.starOpacity);

        var pose = graphics.pose();
        pose.pushPose();
        RenderSystem.setShaderTexture(0, cacheTexture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(pose.last().pose(), 0, starHeight, 0).uv(u0, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), starWidth, starHeight, 0).uv(u1, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), starWidth, 0, 0).uv(u1, v0).endVertex();
        bufferBuilder.vertex(pose.last().pose(), 0, 0, 0).uv(u0, v0).endVertex();
        tesselator.end();

        pose.popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        //? }
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent with configurable opacity)
        ClientConfig config = ClientConfig.getInstance();
        int alpha = (int)(config.panelOpacity * 255);
        int panelBgColor = (alpha << 24) | 0x000000;  // Black with configurable alpha

        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                panelBgColor
        );

        // Panel outline (subtle white)
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
    }

    /**
     * Render the title and description text
     */
    private void renderTitleAndDescription(GuiGraphics graphics) {
        int scaledPadding = 20;
        int titleY = panelY + scaledPadding;

        // Title
        String title = "Choose Your Favorite Color";
        int titleWidth = font.width(title);
        int titleX = panelX + (panelWidth - titleWidth) / 2;
        graphics.drawString(this.font, title, titleX, titleY, 0xFFFFFF, false);

        // Description (2 lines)
        titleY += font.lineHeight + 8;
        String desc1 = "This color will be used for your figure box";
        int desc1Width = font.width(desc1);
        int desc1X = panelX + (panelWidth - desc1Width) / 2;
        graphics.drawString(this.font, desc1, desc1X, titleY, 0xAAAAAA, false);

        titleY += font.lineHeight + 2;
        String desc2 = "in the World Players collection.";
        int desc2Width = font.width(desc2);
        int desc2X = panelX + (panelWidth - desc2Width) / 2;
        graphics.drawString(this.font, desc2, desc2X, titleY, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Prevent closing with ESC - player must make a choice
        return false;
    }

}
