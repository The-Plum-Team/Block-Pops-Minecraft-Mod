package com.theplumteam.capability;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.data.PlayerDataManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles capability-related events.
 * Attaches the PlayerDiscovery capability to players and manages capability lifecycle.
 */
@Mod.EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CapabilityEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(CapabilityEvents.class);
    private static final ResourceLocation PLAYER_DISCOVERY_CAP = new ResourceLocation(BlockPopsMod.MOD_ID, "player_discovery");
    private static final String MIGRATION_MARKER = BlockPopsMod.MOD_ID + "_cap_migrated";

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

    /**
     * Migrate old capability data to the new SavedData system when a player logs in.
     * This ensures players don't lose their discovery data (including figure skins) after the refactoring.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        CompoundTag persistentData = player.getPersistentData();

        // Check if we've already migrated this player
        if (persistentData.getBoolean(MIGRATION_MARKER)) {
            return;
        }

        // Check if new data already exists (player already has data in new format)
        if (persistentData.contains(PlayerDataManager.DATA_KEY, CompoundTag.TAG_COMPOUND)) {
            CompoundTag existingData = persistentData.getCompound(PlayerDataManager.DATA_KEY);
            // If the new data has figure skins, assume it's already migrated
            if (existingData.contains("FigureSkins", CompoundTag.TAG_COMPOUND)) {
                persistentData.putBoolean(MIGRATION_MARKER, true);
                return;
            }
        }

        // Try to migrate from old capability
        player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(oldDiscovery -> {
            if (oldDiscovery instanceof PlayerDiscovery capDiscovery) {
                CompoundTag capData = capDiscovery.serializeNBT();

                // Check if there's any data worth migrating
                boolean hasData = !capDiscovery.getDiscoveredSet().isEmpty()
                    || !capDiscovery.getAllFigureSkins().isEmpty()
                    || capDiscovery.hasChosenFavoriteColor()
                    || capDiscovery.getRegularTokens() > 0;

                if (hasData) {
                    // Copy capability data to new SavedData location
                    persistentData.put(PlayerDataManager.DATA_KEY, capData);
                    BlockPopsMod.logDebug("Migrated discovery data for player {} - {} discovered figures, {} skin snapshots",
                        player.getName().getString(),
                        capDiscovery.getDiscoveredSet().size(),
                        capDiscovery.getAllFigureSkins().size());
                }
            }
        });

        // Mark as migrated so we don't try again
        persistentData.putBoolean(MIGRATION_MARKER, true);
    }
}
