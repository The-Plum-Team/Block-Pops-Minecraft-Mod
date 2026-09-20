package com.theplumteam.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Names the level a server player is in, which 1.21.6 renamed. */
public final class ServerLevels {
    private ServerLevels() {
    }

    public static ServerLevel of(ServerPlayer player) {
        //? if >=1.21.6 {
        /*return player.level();
        *///? } else {
        return player.serverLevel();
        //? }
    }
}
