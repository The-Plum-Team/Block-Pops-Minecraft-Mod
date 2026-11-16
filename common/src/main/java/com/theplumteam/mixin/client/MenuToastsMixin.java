package com.theplumteam.mixin.client;

import com.theplumteam.client.gui.style.MenuGuiScale;
import net.minecraft.client.Minecraft;
//? if >=1.21.2 {
/*import net.minecraft.client.gui.components.toasts.ToastManager;
*///? } else {
import net.minecraft.client.gui.components.toasts.ToastComponent;
//? }
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the game's toasts (tutorial steps, advancements, recipes, another mod's notices) back
 * while a BlockPops menu is shown. The game draws them after the screen, in its top right corner
 * and at the menu's own scale, so they covered the menu's header and buttons.
 *
 * <p>Nothing is dropped: a toast that arrives while the menu is shown stays queued and comes in
 * when the menu goes away, with its sound. Until 1.21.1 one method both advances and draws the
 * toasts; from 1.21.2 the two are apart, and both stop, so no toast runs out its time unseen.
 */
//? if >=1.21.2 {
/*@Mixin(ToastManager.class)
*///? } else {
@Mixin(ToastComponent.class)
//? }
public abstract class MenuToastsMixin {
    //? if >=1.21.2 {
    /*@Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void blockpops$holdToastsBehindMenu(CallbackInfo callback) {
        if (MenuGuiScale.shown(Minecraft.getInstance())) {
            callback.cancel();
        }
    }
    *///? }

    //? if >=26 {
    /*@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    *///? } else {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    //? }
    private void blockpops$keepToastsOffMenu(CallbackInfo callback) {
        if (MenuGuiScale.shown(Minecraft.getInstance())) {
            callback.cancel();
        }
    }
}
