package com.theplumteam.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;

/** Authlib access for fresh, signed player-figure skin snapshots. */
public final class AuthlibProfiles {
    private AuthlibProfiles() {
    }

    /** Returns the service's profile, or null; callers retain their error policy. */
    public static GameProfile fetch(MinecraftSessionService session, GameProfile profile) {
        //? if >=1.21 {
        /*var result = session.fetchProfile(profile.getId(), true);
        return result != null ? result.profile() : null;
        *///? } else {
        return session.fillProfileProperties(profile, true);
        //? }
    }

    public static String value(Property property) {
        //? if >=1.21 {
        /*return property.value();
        *///? } else {
        return property.getValue();
        //? }
    }
}
