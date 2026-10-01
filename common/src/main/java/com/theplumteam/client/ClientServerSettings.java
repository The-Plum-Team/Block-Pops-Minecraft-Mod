package com.theplumteam.client;

import com.theplumteam.network.RequestServerSettingsPacket;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.lang.ref.WeakReference;

/**
 * Client-side cache of the settings the connected server last reported.
 * The Settings screen reads these instead of the local ServerConfig, which on a dedicated
 * server is a different file. Only used from the client thread.
 */
public final class ClientServerSettings {
    // The connection the cached values came from; another connection knows nothing yet
    private static WeakReference<ClientPacketListener> source = new WeakReference<>(null);
    private static int guaranteedResetHour;
    private static int revision;

    private ClientServerSettings() {
    }

    /**
     * Ask the server for its settings. A server without this packet is not asked.
     */
    public static void request() {
        if (Minecraft.getInstance().getConnection() != null
                && NetworkManager.canServerReceive(RequestServerSettingsPacket.ID)) {
            new RequestServerSettingsPacket().sendToServer();
        }
    }

    /**
     * Store the server's answer.
     * Called when the SyncServerSettingsPacket is received from the server.
     */
    public static void update(int guaranteedResetHourUtc) {
        if (guaranteedResetHourUtc < 0 || guaranteedResetHourUtc > 23) {
            return;
        }
        source = new WeakReference<>(Minecraft.getInstance().getConnection());
        guaranteedResetHour = guaranteedResetHourUtc;
        revision++;
    }

    /**
     * Get the server's guaranteed token reset hour.
     *
     * @return The hour (0-23 in UTC), or null while the connected server has not reported it
     */
    public static Integer getGuaranteedResetHour() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && source.get() == connection ? guaranteedResetHour : null;
    }

    /**
     * Changes with every answer, so the Settings screen can tell a new answer from the one it shows.
     */
    public static int getRevision() {
        return revision;
    }
}
