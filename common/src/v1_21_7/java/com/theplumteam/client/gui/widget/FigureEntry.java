package com.theplumteam.client.gui.widget;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.renderer.FigurePipRenderState;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    private static final int FIGURE_SIZE = 80;
    private static final int GRID_SPACING = 4;
    private static final int LINK_BUTTON_SIZE = 12;
    private static final ResourceLocation LINK_ICON = ResourceLocation.fromNamespaceAndPath("blockpops", "textures/gui/search_icon.png");

    private int hoveredLinkFigureIndex = -1;

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

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                      int entryHeight, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {

        this.hoveredLinkFigureIndex = -1;

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

                // Draw figure name only if GUI scale is less than 3 and settings modal is not active
                net.minecraft.client.gui.screens.Screen currentScreen = Minecraft.getInstance().screen;
                boolean isSettingsModalActive = currentScreen instanceof com.theplumteam.client.gui.SettingsScreen;
                int guiScale = mc.options.guiScale().get();
                if (guiScale < 3 && !isSettingsModalActive) {
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

                // Draw link icon in top-right corner when hovered and figure has author URL
                if (isFigureHovered && figure.hasAuthorUrl()) {
                    int effectiveLinkSize = LINK_BUTTON_SIZE;
                    if (GuiScaleManager.isUsingInverseScale()) {
                        effectiveLinkSize = (int)(LINK_BUTTON_SIZE * GuiScaleManager.getRenderScaleFactor());
                    }
                    int linkX = figureX + effectiveFigureSize - effectiveLinkSize - 2;
                    int linkY = y + 2;

                    boolean linkHovered = mouseX >= linkX && mouseX < linkX + effectiveLinkSize &&
                                         mouseY >= linkY && mouseY < linkY + effectiveLinkSize;

                    graphics.fill(linkX, linkY, linkX + effectiveLinkSize, linkY + effectiveLinkSize,
                        linkHovered ? 0xFFB8860B : 0xC0997000);
                    graphics.renderOutline(linkX, linkY, effectiveLinkSize, effectiveLinkSize,
                        linkHovered ? 0xFFDAA520 : 0xFFB8860B);

                    int iconSize = effectiveLinkSize - 4;
                    int iconX = linkX + 2;
                    int iconY = linkY + 2;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(iconX, iconY);
                    float iconScale = iconSize / 256.0f;
                    graphics.pose().scale(iconScale, iconScale);
                    graphics.blit(RenderPipelines.GUI_TEXTURED, LINK_ICON, 0, 0, 0.0f, 0.0f, 256, 256, 256, 256);
                    graphics.pose().popMatrix();

                    if (linkHovered) {
                        this.hoveredLinkFigureIndex = i;
                    }
                }
            } else {
                // Draw a question mark for undiscovered figures (skip if settings modal is active)
                net.minecraft.client.gui.screens.Screen currentScreen = Minecraft.getInstance().screen;
                boolean isSettingsModalActive = currentScreen instanceof com.theplumteam.client.gui.SettingsScreen;

                if (!isSettingsModalActive) {
                    Component questionMark = Component.literal("?");
                    int qmWidth = mc.font.width(questionMark);
                    int qmX = figureX + (effectiveFigureSize - qmWidth) / 2;
                    int qmY = y + (effectiveFigureSize - mc.font.lineHeight) / 2;
                    graphics.drawString(mc.font, questionMark, qmX, qmY, 0xFF808080, false);
                }

                // Don't show name for undiscovered figures
            }
        }
    }

    // Cached reflection fields for accessing GuiGraphics internals
    private static java.lang.reflect.Field guiRenderStateField;
    private static java.lang.reflect.Field scissorStackField;
    private static java.lang.reflect.Method scissorPeekMethod;
    private static boolean reflectionInitialized = false;

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            guiRenderStateField = GuiGraphics.class.getDeclaredField("guiRenderState");
            guiRenderStateField.setAccessible(true);
            scissorStackField = GuiGraphics.class.getDeclaredField("scissorStack");
            scissorStackField.setAccessible(true);
        } catch (Exception e) {
            com.theplumteam.BlockPopsMod.LOGGER.warn("FigureEntry: Failed to initialize reflection for PiP rendering", e);
        }
    }

    private static ScreenRectangle peekScissorArea(GuiGraphics graphics) {
        initReflection();
        try {
            if (scissorStackField != null) {
                Object scissorStack = scissorStackField.get(graphics);
                if (scissorPeekMethod == null) {
                    scissorPeekMethod = scissorStack.getClass().getDeclaredMethod("peek");
                    scissorPeekMethod.setAccessible(true);
                }
                ScreenRectangle result = (ScreenRectangle) scissorPeekMethod.invoke(scissorStack);
                if (result != null) return result;
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return ScreenRectangle.empty();
    }

    private static void submitPipState(GuiGraphics graphics, PictureInPictureRenderState state) {
        initReflection();
        try {
            if (guiRenderStateField != null) {
                GuiRenderState guiRenderState = (GuiRenderState) guiRenderStateField.get(graphics);
                guiRenderState.submitPicturesInPictureState(state);
            }
        } catch (Exception e) {
            com.theplumteam.BlockPopsMod.LOGGER.error("FigureEntry: Failed to submit PiP state", e);
        }
    }

    private void render3DFigure(GuiGraphics graphics, FigureDefinition figure, int x, int y, int size, float partialTick) {
        // Skip 3D rendering if settings modal is currently active (prevents z-index issues)
        net.minecraft.client.gui.screens.Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen instanceof com.theplumteam.client.gui.SettingsScreen) {
            return;
        }

        BoxBlockEntity renderEntity = FigureWidgetRenderer.getOrCreateRenderEntity(figure, collectionId);
        if (renderEntity == null) {
            return;
        }

        // Enable scissor test to clip rendering to the figure box
        graphics.enableScissor(x, y, x + size, y + size);

        // Get the current scissor area for bounds computation
        ScreenRectangle scissorArea = peekScissorArea(graphics);

        // Scale: size * modelScale maps model units to GUI pixels
        float pipScale = size * modelScale * figure.getGuiScale();

        // Create PiP render state with entity, figure key, rotations, bounds, and scale
        String figureKey = collectionId + ":" + figure.getId();
        FigurePipRenderState renderState = new FigurePipRenderState(
            renderEntity, figureKey, yRotation, xRotation, zRotation,
            x, y, x + size, y + size,
            pipScale, scissorArea
        );

        // Submit to the deferred PiP rendering system
        submitPipState(graphics, renderState);

        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hoveredLinkFigureIndex >= 0 && hoveredLinkFigureIndex < figures.size()) {
            FigureDefinition figure = figures.get(hoveredLinkFigureIndex);
            if (figure.hasAuthorUrl()) {
                try {
                    mc.screen.handleComponentClicked(
                        net.minecraft.network.chat.Style.EMPTY.withClickEvent(
                            new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(figure.getAuthorUrl()))
                        )
                    );
                } catch (Exception e) {
                    System.err.println("Failed to open URL: " + figure.getAuthorUrl());
                }
                return true;
            }
        }
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
