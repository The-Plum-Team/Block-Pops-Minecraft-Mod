package com.theplumteam.client.neoforge;

import com.theplumteam.client.renderer.FigureWidgetRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Client-side event handler for NeoForge game bus events.
 * Handles cleanup tasks when disconnecting from servers/worlds.
 */
@EventBusSubscriber(modid = "blockpops", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
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
