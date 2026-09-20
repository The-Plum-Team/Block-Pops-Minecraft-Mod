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
        blockpops$initialProfileProperties = config.user.profileProperties;
    }

    @Override
    @Unique
    public PropertyMap blockpops$getInitialProfileProperties() {
        return blockpops$initialProfileProperties;
    }
}
