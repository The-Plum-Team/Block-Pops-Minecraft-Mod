package com.theplumteam.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;

/** Authlib access for fresh, signed player-figure skin snapshots. */
public final class AuthlibProfiles {
    private AuthlibProfiles() {
    }

    /** Returns the service's profile, or null; callers retain their error policy. */
    public static GameProfile fetch(MinecraftSessionService session, GameProfile profile) {
        //? if >=1.21 {
        /*var result = session.fetchProfile(id(profile), true);
        return result != null ? result.profile() : null;
        *///? } else {
        return session.fillProfileProperties(profile, true);
        //? }
    }

    /** The profile's property map. Authlib 9 renamed every accessor to record form. */
    public static PropertyMap properties(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.properties();
        *///? } else {
        return profile.getProperties();
        //? }
    }

    public static UUID id(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.id();
        *///? } else {
        return profile.getId();
        //? }
    }

    public static String name(GameProfile profile) {
        //? if >=1.21.9 {
        /*return profile.name();
        *///? } else {
        return profile.getName();
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
