package com.theplumteam.client;

import com.theplumteam.client.renderer.FigureWidgetRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side event handler for FORGE bus events.
 * Handles cleanup tasks when disconnecting from servers/worlds.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = "blockpops", value = Dist.CLIENT)
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
