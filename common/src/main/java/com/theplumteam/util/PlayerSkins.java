package com.theplumteam.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads a profile's skin texture without demanding a signed profile.
 *
 * 1.21.9 moved PlayerSkin to net.minecraft.world.entity.player and made it a record
 * of ClientAsset textures, and replaced getInsecureSkin with a lookup whose boolean
 * says whether only a secure skin will do. Passing false keeps the older behaviour.
 */
public final class PlayerSkins {
    private PlayerSkins() {
    }

    public static ResourceLocation insecureTexture(GameProfile profile) {
        //? if >=1.21.9 {
        /*return Minecraft.getInstance().getSkinManager().createLookup(profile, false).get().body().texturePath();
        *///? } elif >=1.21 {
        /*return Minecraft.getInstance().getSkinManager().getInsecureSkin(profile).texture();
        *///? } else {
        return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        //? }
    }
}
