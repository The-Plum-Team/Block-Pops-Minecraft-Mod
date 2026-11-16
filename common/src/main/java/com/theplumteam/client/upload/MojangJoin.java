package com.theplumteam.client.upload;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.util.E2EDeterminism;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Joins through the game's own session service, the way the game joins a server it connects
 * to, so the access token stays inside authlib and the request goes wherever this version of
 * the game sends it. No Minecraft server takes part.
 */
public final class MojangJoin implements Joiner {
    private MojangJoin() {
    }

    /** The game's: none at all in the packaged E2E, which never contacts Mojang. */
    public static Joiner forGame() {
        return E2EDeterminism.ENABLED ? Joiner.NONE : new MojangJoin();
    }

    @Override
    public String name() {
        return Minecraft.getInstance().getUser().getName();
    }

    @Override
    public void join(String serverId) throws UploadException {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            // 1.21 asks by the account's id instead of its whole profile, and 26 keeps the
            // service with the game's other services
            //? if >=26 {
            /*minecraft.services().sessionService().joinServer(minecraft.getUser().getProfileId(),
                    minecraft.getUser().getAccessToken(), serverId);
            *///? } elif >=1.21 {
            /*minecraft.getMinecraftSessionService().joinServer(minecraft.getUser().getProfileId(),
                    minecraft.getUser().getAccessToken(), serverId);
            *///? } else {
            minecraft.getMinecraftSessionService().joinServer(minecraft.getUser().getGameProfile(),
                    minecraft.getUser().getAccessToken(), serverId);
            //? }
        } catch (AuthenticationUnavailableException e) {
            throw new UploadException(ApiError.MOJANG_UNAVAILABLE);
        } catch (AuthenticationException e) {
            throw new UploadException(error(e));
        } catch (RuntimeException e) {
            throw new UploadException(ApiError.NETWORK);
        }
    }

    // Which refusal this is. Told by the class's name, since the classes differ between authlib's versions
    private static ApiError error(AuthenticationException e) {
        String kind = e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (kind.contains("insufficientprivileges")) {
            return ApiError.MULTIPLAYER_DISABLED;
        }
        if (kind.contains("userbanned")) {
            return ApiError.MOJANG_BANNED;
        }
        if (kind.contains("forcedusernamechange")) {
            return ApiError.NAME_CHANGE_REQUIRED;
        }
        // An offline account, or a session that ran out: the game has to be signed in again
        return ApiError.SESSION_INVALID;
    }
}
