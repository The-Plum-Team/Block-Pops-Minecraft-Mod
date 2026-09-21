package com.theplumteam.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Plays a sound for one player and nobody else.
 *
 * 1.21.9 removed ServerPlayer#playNotifySound. The packet below is what that
 * method sent, so only the addressed client hears it; playing the sound through
 * the level would broadcast it to everyone nearby instead.
 */
public final class PlayerSounds {
    private PlayerSounds() {
    }

    public static void notify(ServerPlayer player, SoundEvent sound, SoundSource source, float volume, float pitch) {
        //? if >=1.21.9 {
        /*player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
        *///? } else {
        player.playNotifySound(sound, source, volume, pitch);
        //? }
    }
}
