package com.theplumteam.client.gui.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Supplier;

/** Plays the screens' sounds, heard the same wherever the player stands. */
public final class UiSounds {
    private UiSounds() {
    }

    public static void play(Supplier<SoundEvent> sound) {
        play(Minecraft.getInstance().getSoundManager(), sound);
    }

    /** For a button's press, which is handed the sound manager. */
    public static void play(SoundManager soundManager, Supplier<SoundEvent> sound) {
        soundManager.play(SimpleSoundInstance.forUI(sound.get(), 1.0f));
    }

    public static void play(Supplier<SoundEvent> sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), pitch, volume));
    }
}
