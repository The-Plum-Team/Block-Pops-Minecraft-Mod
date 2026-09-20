package com.theplumteam.client;

//? if >=1.21 {
/*import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
*///? }

/** Modern preparation of profiles formerly handled by SkinManager.registerSkins. */
public final class SkinProfilePreparation {
    private SkinProfilePreparation() {
    }

    // Legacy consumers retain native registerSkins; this class has no legacy operations.
    //? if >=1.21 {
    /*public static void schedule(MinecraftSessionService session, GameProfile target, UUID localId,
                                PropertyMap localProperties, Executor background, Executor main,
                                Consumer<GameProfile> register) {
        background.execute(() -> {
            boolean populated;
            try {
                populated = hasTextures(session, target);
            } catch (InsecurePublicKeyException exception) {
                populated = false;
            }
            if (!populated) {
                target.getProperties().clear();
                if (target.getId().equals(localId)) {
                    if (localProperties.isEmpty()) {
                        copyFetchedProperties(session, localId, localProperties);
                    }
                    target.getProperties().putAll(localProperties);
                    hasTextures(session, target);
                } else {
                    copyFetchedProperties(session, target.getId(), target.getProperties());
                    try {
                        hasTextures(session, target);
                    } catch (InsecurePublicKeyException exception) {
                        // Legacy remote decoding tolerates this; local decoding does not.
                    }
                }
            }
            main.execute(() -> register.accept(target));
        });
    }

    private static boolean hasTextures(MinecraftSessionService session, GameProfile profile) {
        var textures = session.getTextures(profile);
        return textures.skin() != null || textures.cape() != null || textures.elytra() != null;
    }

    private static void copyFetchedProperties(MinecraftSessionService session, UUID id, PropertyMap destination) {
        var result = session.fetchProfile(id, false);
        if (result != null) {
            destination.putAll(result.profile().getProperties());
        }
    }
    *///? }
}
