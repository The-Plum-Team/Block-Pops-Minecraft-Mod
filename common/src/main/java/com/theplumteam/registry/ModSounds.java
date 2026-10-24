package com.theplumteam.registry;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;

/**
 * The mod's own sounds, each naming its files in {@code assets/blockpops/sounds.json}. The menu's
 * and the draw's play for the player at the screen alone; the boxes' and the plushes' come from
 * the block, so their files are mono.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(BlockPopsMod.MOD_ID, Registries.SOUND_EVENT);

    // The machine menu
    public static final RegistrySupplier<SoundEvent> MENU_OPEN = sound("ui.menu_open");
    public static final RegistrySupplier<SoundEvent> CLICK = sound("ui.click");
    public static final RegistrySupplier<SoundEvent> TAB = sound("ui.tab");
    public static final RegistrySupplier<SoundEvent> COLLECTION = sound("ui.collection");
    public static final RegistrySupplier<SoundEvent> VERSION = sound("ui.version");

    // A draw: the ticket spent, each step of the light, its stop and the figure it stops on
    public static final RegistrySupplier<SoundEvent> DRAW_TICKET = sound("draw.ticket");
    public static final RegistrySupplier<SoundEvent> DRAW_STEP = sound("draw.step");
    public static final RegistrySupplier<SoundEvent> DRAW_STOP = sound("draw.stop");
    public static final RegistrySupplier<SoundEvent> DRAW_NEW = sound("draw.new");
    public static final RegistrySupplier<SoundEvent> DRAW_DUPLICATE = sound("draw.duplicate");
    public static final RegistrySupplier<SoundEvent> DRAW_HOLOGRAPHIC = sound("draw.holographic");

    // Boxes and plushes in the world
    public static final RegistrySupplier<SoundEvent> BOX_INSERT = sound("box.insert");
    public static final RegistrySupplier<SoundEvent> BOX_REMOVE = sound("box.remove");
    public static final RegistrySupplier<SoundEvent> BOX_OPEN = sound("box.open");
    public static final RegistrySupplier<SoundEvent> PLUSH_CHANGE = sound("plush.change");

    private ModSounds() {
    }

    private static RegistrySupplier<SoundEvent> sound(String name) {
        return SOUNDS.register(name,
            () -> SoundEvent.createVariableRangeEvent(ResourceLocations.of(BlockPopsMod.MOD_ID, name)));
    }

    public static void register() {
        SOUNDS.register();
    }
}
