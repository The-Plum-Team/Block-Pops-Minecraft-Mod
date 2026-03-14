package com.theplumteam.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor for GuiGraphics private fields.
 * Using @Accessor ensures field names are properly remapped between
 * development (Mojang mappings) and production (intermediary mappings).
 */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {

    @Accessor("guiRenderState")
    GuiRenderState blockpops$getGuiRenderState();
}
