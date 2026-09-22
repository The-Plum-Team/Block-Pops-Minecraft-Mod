package com.theplumteam.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.BlockPopsMod;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///? }
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class LinkButton extends Button {

    //? if >=1.21 {
    /*private static final ResourceLocation NORMAL_BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    *///? }

    private final ResourceLocation texture;
    private final int textureWidth;
    private final int textureHeight;

    public LinkButton(int x, int y, int width, int height, ResourceLocation texture, String url, Component tooltip) {
        super(x, y, width, height, Component.empty(), button -> {
            if (url != null) {
                openLink(url);
            }
        }, DEFAULT_NARRATION);

        this.texture = texture;

        // Assuming square textures for logos
        this.textureWidth = 256;
        this.textureHeight = 256;

        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Use the standard button background for the rounded shape.
        // We always use the "normal" (not hovered) state texture. The V-offset for this is 46 + 1 * 20 = 66.
        //? if >=1.21.6 {
        /*graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, NORMAL_BUTTON, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        *///? } elif >=1.21.2 {
        /*graphics.blitSprite(RenderType::guiTextured, NORMAL_BUTTON, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        *///? } elif >=1.21 {
        /*graphics.blitSprite(NORMAL_BUTTON, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        *///? } else {
        graphics.blitNineSliced(WIDGETS_LOCATION, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 20, 4, 200, 20, 0, 46 + 1 * 20);
        //? }

        // Enable blending for the transparent logo. From 1.21.5 the GUI render
        // type owns blending and depth, so there is no global state to set.
        //? if <1.21.5 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        //? }

        // Set color to white (no tint). 1.21.6 has no global shader colour; the
        // widget's own alpha reaches the blit through the render pipeline instead.
        //? if <1.21.6 {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
        //? }

        // Draw the logo texture on top, inset slightly to fit within the rounded border.
        int padding = 2;
        //? if >=1.21.6 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, this.texture,
                this.getX() + padding, this.getY() + padding,
                0.0F, 0.0F,
                this.width - (padding * 2), this.height - (padding * 2),
                this.textureWidth, this.textureHeight,
                this.textureWidth, this.textureHeight
        );
        *///? } elif >=1.21.2 {
        /*// 1.21.4 takes the RenderType function first and reads UV before the on-screen size.
        graphics.blit(RenderType::guiTextured, this.texture,
                this.getX() + padding, this.getY() + padding,
                0.0F, 0.0F,
                this.width - (padding * 2), this.height - (padding * 2),
                this.textureWidth, this.textureHeight,
                this.textureWidth, this.textureHeight
        );
        *///? } else {
        graphics.blit(this.texture,
                this.getX() + padding, this.getY() + padding,           // Screen position (x, y) with padding
                this.width - (padding * 2), this.height - (padding * 2), // Size on screen (width, height) reduced by padding
                0.0F, 0.0F,                                              // Texture UV start
                this.textureWidth, this.textureHeight,                   // Region in texture to draw (the whole image)
                this.textureWidth, this.textureHeight                    // Total texture size
        );
        //? }
    }

    private static void openLink(String url) {
        // Open the link in the default browser
        //? if >=26.3 {
        /*// 26.3 moved opening a link onto Blaze3D, and it takes a parsed URI.
        try {
            com.mojang.blaze3d.Blaze3D.openUri(new java.net.URI(url));
        } catch (java.net.URISyntaxException malformed) {
            return;
        }
        *///? } else {
        Util.getPlatform().openUri(url);
        //? }

        // Log the action
        BlockPopsMod.logDebug("Opening link: {}", url);
    }

    @Override
    //? if >=26 {
    /*protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo bpInfo) {
        int button = bpInfo.button();
    *///? } else {
    protected boolean isValidClickButton(int button) {
    //? }
        // Only allow left-click
        return button == 0;
    }
}
