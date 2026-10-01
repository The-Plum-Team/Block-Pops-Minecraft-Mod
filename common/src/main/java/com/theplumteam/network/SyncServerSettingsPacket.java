package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client packet that carries the server settings shown in the Settings screen.
 * Sent in answer to a RequestServerSettingsPacket.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class SyncServerSettingsPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "sync_server_settings");

    private final int guaranteedResetHour;

    public SyncServerSettingsPacket(int guaranteedResetHour) {
        this.guaranteedResetHour = guaranteedResetHour;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeInt(guaranteedResetHour);
        return buffer;
    }

    public static SyncServerSettingsPacket decode(FriendlyByteBuf buffer) {
        return new SyncServerSettingsPacket(buffer.readInt());
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SyncServerSettingsPacket packet = decode(buf);

        context.queue(() -> {
            // Same Supplier<Runnable> as OpenFavoriteColorScreenPacket: the client class
            // is only loaded when this runs on the client.
            EnvExecutor.runInEnv(Env.CLIENT, () -> () ->
                    com.theplumteam.client.ClientServerSettings.update(packet.guaranteedResetHour));
        });
    }

    /**
     * Send the current server settings to a specific player
     */
    public static void sendToPlayer(ServerPlayer player) {
        SyncServerSettingsPacket packet = new SyncServerSettingsPacket(
                ServerConfig.getInstance().getGuaranteedTokenResetHour());
        PacketNetworking.sendToPlayer(player, ID, packet.encode());
    }
}
