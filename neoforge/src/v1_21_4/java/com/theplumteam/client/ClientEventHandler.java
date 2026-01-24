package com.theplumteam.client;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.renderer.FigureWidgetRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-side event handler for NeoForge bus events.
 * Handles cleanup tasks when disconnecting from servers/worlds.
 */
@EventBusSubscriber(modid = BlockPopsMod.MOD_ID, value = Dist.CLIENT)
public class ClientEventHandler {

    /**
     * Called when the player logs out (disconnects from server or leaves world).
     * Clears cached render entities to prevent memory leaks from entities
     * holding references to old Level instances.
     */
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        FigureWidgetRenderer.clearCache();
    }
}
