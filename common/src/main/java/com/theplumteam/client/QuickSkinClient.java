package com.theplumteam.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Quick Skin's client services, reached by reflection since Quick Skin is optional: the skin it
 * shows for a player, and the texture of one of its skin ids.
 *
 * Quick Skin syncs the appearance of every player its server knows, those who left included, so
 * it gives a World Players figure its player's skin while that player is offline too. The services
 * are looked up the first time either is asked for, and asked for their instance on every call,
 * which Quick Skin may create after this mod.
 */
public final class QuickSkinClient {
    private static boolean checked;
    @Nullable
    private static Method appearanceService;
    @Nullable
    private static Method appearanceSkin;
    @Nullable
    private static Method skinService;
    @Nullable
    private static Method skinById;

    private QuickSkinClient() {
    }

    private static synchronized void check() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            Class<?> service = Class.forName("com.quickskin.mod.client.services.PlayerAppearanceService");
            appearanceService = service.getMethod("getInstance");
            appearanceSkin = service.getMethod("getSkinLocation", UUID.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            appearanceService = null;
        }
        try {
            Class<?> service = Class.forName("com.quickskin.mod.client.services.SkinService");
            skinService = service.getMethod("getInstance");
            skinById = service.getMethod("getSkinLocation", UUID.class, String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            skinService = null;
        }
    }

    /** The skin Quick Skin shows for the player now, or null when it has none for them. */
    @Nullable
    public static ResourceLocation playerSkin(UUID playerId) {
        check();
        if (appearanceService == null) {
            return null;
        }
        try {
            Object instance = appearanceService.invoke(null);
            return instance == null ? null : (ResourceLocation) appearanceSkin.invoke(instance, playerId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The texture of a Quick Skin skin id, such as the one a figure was drawn with. Quick Skin
     * asks its server for a texture it lacks, so this is null until that arrives.
     */
    @Nullable
    public static ResourceLocation skin(@Nullable String skinId) {
        check();
        if (skinService == null || skinId == null || skinId.isEmpty()) {
            return null;
        }
        try {
            Object instance = skinService.invoke(null);
            return instance == null ? null : (ResourceLocation) skinById.invoke(instance, null, skinId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
