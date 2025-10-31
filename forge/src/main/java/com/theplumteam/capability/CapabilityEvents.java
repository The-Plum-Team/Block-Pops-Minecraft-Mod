package com.theplumteam.capability;

import com.theplumteam.BlockPopsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles capability-related events.
 * Attaches the PlayerDiscovery capability to players and manages capability lifecycle.
 */
@Mod.EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CapabilityEvents {
    private static final ResourceLocation PLAYER_DISCOVERY_CAP = new ResourceLocation(BlockPopsMod.MOD_ID, "player_discovery");

    /**
     * Attach the PlayerDiscovery capability to all players when they are created.
     */
    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            PlayerDiscoveryProvider provider = new PlayerDiscoveryProvider();
            event.addCapability(PLAYER_DISCOVERY_CAP, provider);
        }
    }

    /**
     * Clone the capability data when a player respawns or returns from the End.
     * This ensures discovered figures persist through death and dimension changes.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            // Copy the capability data from the old player to the new player
            event.getOriginal().getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(oldDiscovery -> {
                event.getEntity().getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(newDiscovery -> {
                    newDiscovery.syncFrom(oldDiscovery.getDiscoveredSet());
                });
            });
        }
    }
}
