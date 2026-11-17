package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client packet that opens the dialog where the player links their Ko-fi payment
 * ({@code /blockpops supporter link}, which the chat's "Link my account" runs). Sent only to a
 * client that said it has that dialog ({@link com.theplumteam.server.ClientCapabilities#kofiLink});
 * the client opens it only over no screen, the chat or the machine menu.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class OpenSupporterLinkPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "open_supporter_link");

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        // A dummy byte: empty packets can cause issues with Architectury networking
        buffer.writeBoolean(true);
        return buffer;
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        buf.readBoolean();
        // Fully qualified, so the client class loads only on the client
        context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () ->
                com.theplumteam.client.gui.SupporterLinkScreen.openOverCurrent()));
    }

    public static void sendToPlayer(ServerPlayer player) {
        PacketNetworking.sendToPlayer(player, ID, new OpenSupporterLinkPacket().encode());
    }
}
