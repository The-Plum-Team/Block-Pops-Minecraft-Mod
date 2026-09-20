package com.theplumteam.client;

//? if >=1.21 {
/*import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
*///? }

public final class ClientSkinRegistration {
    private ClientSkinRegistration() {
    }

    // Legacy callers retain native registerSkins.
    //? if >=1.21 {
    /*public static void register(GameProfile profile) {
        Minecraft minecraft = Minecraft.getInstance();
        SkinProfilePreparation.schedule(minecraft.getMinecraftSessionService(), profile,
            minecraft.getUser().getProfileId(),
            ((LocalProfileProperties) minecraft).blockpops$getInitialProfileProperties(),
            Util.backgroundExecutor(), minecraft::execute, minecraft.getSkinManager()::getOrLoad);
    }
    *///? }
}
