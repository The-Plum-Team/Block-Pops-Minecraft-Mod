package com.theplumteam.mixin.client;

import com.mojang.authlib.properties.PropertyMap;
import com.theplumteam.client.LocalProfileProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftProfilePropertiesMixin implements LocalProfileProperties {
    @Unique
    private PropertyMap blockpops$initialProfileProperties;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void blockpops$captureInitialProfileProperties(GameConfig config, CallbackInfo callback) {
        //? if >=26 {
        /*// 26.1 dropped User#profileProperties. The same properties are reachable
        // from the client's own GameProfile by the time any skin lookup runs, so
        // nothing is captured at construction any more.
        *///? } else {
        blockpops$initialProfileProperties = config.user.profileProperties;
        //? }
    }

    @Override
    @Unique
    public PropertyMap blockpops$getInitialProfileProperties() {
        //? if >=26 {
        /*return com.theplumteam.util.AuthlibProfiles.properties(
                net.minecraft.client.Minecraft.getInstance().getGameProfile());
        *///? } else {
        return blockpops$initialProfileProperties;
        //? }
    }
}
