package com.theplumteam.client;

//? if >=1.21 {
/*import com.mojang.authlib.GameProfile;
import com.theplumteam.util.AuthlibProfiles;
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
    //? if >=1.21.9 {
    /*// Authlib 9 profiles are immutable. A profile without textures is not filled in place:
    // a new one carrying the local or fetched properties is registered instead, under the
    // same rules as below.
    public static void schedule(MinecraftSessionService session, GameProfile target, UUID localId,
                                PropertyMap localProperties, Executor background, Executor main,
                                Consumer<GameProfile> register) {
        background.execute(() -> {
            boolean populated;
            try {
                populated = hasTextures(session, target);
            } catch (InsecurePublicKeyException exception) {
                populated = false;
            }
            GameProfile prepared = target;
            if (!populated) {
                UUID id = AuthlibProfiles.id(target);
                boolean local = id.equals(localId);
                PropertyMap properties = local && !localProperties.isEmpty()
                        ? localProperties : fetchedProperties(session, id);
                prepared = new GameProfile(id, AuthlibProfiles.name(target), properties);
                if (local) {
                    hasTextures(session, prepared);
                } else {
                    try {
                        hasTextures(session, prepared);
                    } catch (InsecurePublicKeyException exception) {
                        // Legacy remote decoding tolerates this; local decoding does not.
                    }
                }
            }
            GameProfile result = prepared;
            main.execute(() -> register.accept(result));
        });
    }

    private static PropertyMap fetchedProperties(MinecraftSessionService session, UUID id) {
        var result = session.fetchProfile(id, false);
        return result != null ? AuthlibProfiles.properties(result.profile()) : PropertyMap.EMPTY;
    }

    private static boolean hasTextures(MinecraftSessionService session, GameProfile profile) {
        var textures = session.getTextures(profile);
        return textures.skin() != null || textures.cape() != null || textures.elytra() != null;
    }
    *///? } elif >=1.21 {
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
                AuthlibProfiles.properties(target).clear();
                if (AuthlibProfiles.id(target).equals(localId)) {
                    if (localProperties.isEmpty()) {
                        copyFetchedProperties(session, localId, localProperties);
                    }
                    AuthlibProfiles.properties(target).putAll(localProperties);
                    hasTextures(session, target);
                } else {
                    copyFetchedProperties(session, AuthlibProfiles.id(target), AuthlibProfiles.properties(target));
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
            destination.putAll(AuthlibProfiles.properties(result.profile()));
        }
    }
    *///? }
}
