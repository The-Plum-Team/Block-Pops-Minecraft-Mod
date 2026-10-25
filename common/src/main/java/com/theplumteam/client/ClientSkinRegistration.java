package com.theplumteam.client;

//? if >=1.21 {
/*import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
*///? }

public final class ClientSkinRegistration {
    private ClientSkinRegistration() {
    }

    // Legacy callers retain native registerSkins.
    //? if >=1.21.9 {
    /*public static void register(GameProfile profile) {
        register(profile, prepared -> { });
    }

    // Authlib 9 profiles are immutable, so a profile that had to be populated comes back as a
    // new one. The caller is handed it and reads the skin from that one from then on.
    public static void register(GameProfile profile, java.util.function.Consumer<GameProfile> prepared) {
        Minecraft minecraft = Minecraft.getInstance();
        SkinProfilePreparation.schedule(
            //? if >=26 {
            /^minecraft.services().sessionService(),
            ^///? } else {
            minecraft.getMinecraftSessionService(),
            //? }
            profile,
            minecraft.getUser().getProfileId(),
            localProperties(minecraft),
            Util.backgroundExecutor(), minecraft::execute,
            result -> {
                prepared.accept(result);
                //? if >=26 {
                /^minecraft.getSkinManager().get(result);
                ^///? } else {
                minecraft.getSkinManager().getOrLoad(result);
                //? }
            });
    }

    // The properties the game started with, which BlockPops' client mixin keeps. Without the mixin,
    // as on a loader that did not apply it, there are none, and the local player's skin is fetched
    // like anyone else's instead of crashing the render.
    private static PropertyMap localProperties(Minecraft minecraft) {
        return minecraft instanceof LocalProfileProperties local
                ? local.blockpops$getInitialProfileProperties() : PropertyMap.EMPTY;
    }
    *///? } elif >=1.21 {
    /*public static void register(GameProfile profile) {
        Minecraft minecraft = Minecraft.getInstance();
        SkinProfilePreparation.schedule(
            //? if >=26 {
            /^minecraft.services().sessionService(),
            ^///? } else {
            minecraft.getMinecraftSessionService(),
            //? }
            profile,
            minecraft.getUser().getProfileId(),
            localProperties(minecraft),
            Util.backgroundExecutor(), minecraft::execute,
            //? if >=26 {
            /^minecraft.getSkinManager()::get);
            ^///? } else {
            minecraft.getSkinManager()::getOrLoad);
            //? }
    }

    // The properties the game started with, which BlockPops' client mixin keeps. Without the mixin,
    // as on a loader that did not apply it, there are none, and the local player's skin is fetched
    // like anyone else's instead of crashing the render.
    private static PropertyMap localProperties(Minecraft minecraft) {
        return minecraft instanceof LocalProfileProperties local
                ? local.blockpops$getInitialProfileProperties() : new PropertyMap();
    }
    *///? }
}
