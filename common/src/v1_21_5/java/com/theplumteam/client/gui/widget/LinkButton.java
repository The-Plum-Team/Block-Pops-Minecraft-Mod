package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.BlockPopsMod;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class LinkButton extends Button {

    private static final ResourceLocation WIDGETS_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/widgets.png");

    private final ResourceLocation texture;

    public LinkButton(int x, int y, int width, int height, ResourceLocation texture, String url, Component tooltip) {
        super(x, y, width, height, Component.empty(), button -> {
            if (url != null) {
                openLink(url);
            }
        }, DEFAULT_NARRATION);

        this.texture = texture;

        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Draw a simple rounded background
        int bgColor = this.isHoveredOrFocused() ? 0x80FFFFFF : 0x60FFFFFF;
        graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), bgColor);

        // Draw border
        int borderColor = 0x80FFFFFF;
        graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, borderColor);
        graphics.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), borderColor);
        graphics.fill(this.getX(), this.getY() + 1, this.getX() + 1, this.getY() + this.getHeight() - 1, borderColor);
        graphics.fill(this.getX() + this.getWidth() - 1, this.getY() + 1, this.getX() + this.getWidth(), this.getY() + this.getHeight() - 1, borderColor);

        // Enable blending for the transparent logo.

        // Set color to white (no tint).
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);

        // Draw the logo texture on top, inset slightly to fit within the rounded border.
        int padding = 2;
        int drawWidth = this.width - (padding * 2);
        int drawHeight = this.height - (padding * 2);

        // In 1.21.4+, use simple blit with scaling via PoseStack
        // The basic blit draws at uWidth x vHeight size, so we scale to fit our desired size
        graphics.pose().pushPose();
        graphics.pose().translate(this.getX() + padding, this.getY() + padding, 0);

        // Scale to fit the button size (assuming 256x256 texture)
        float scaleX = drawWidth / 256.0f;
        float scaleY = drawHeight / 256.0f;
        graphics.pose().scale(scaleX, scaleY, 1.0f);

        // Draw at (0,0) since we've already translated, full texture size
        graphics.blit(RenderType::guiTextured, this.texture,
                0, 0,                    // Position (already translated)
                0.0f, 0.0f,              // UV offset
                256, 256,                // Region size (full texture)
                256, 256                 // Texture dimensions
        );

        graphics.pose().popPose();

        // Reset shader color
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void openLink(String url) {
        // Open the link in the default browser
        Util.getPlatform().openUri(url);

        // Log the action
        BlockPopsMod.logDebug("Opening link: {}", url);
    }

    @Override
    protected boolean isValidClickButton(int button) {
        // Only allow left-click
        return button == 0;
    }
}
