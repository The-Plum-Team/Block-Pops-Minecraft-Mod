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
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Server-to-client answer to the supporter button: the checkout the server got for the player, or
 * why there is none, which the machine menu shows. The client opens a checkout only right after
 * its player pressed the button, and only on PayNow or Ko-fi.
 *
 * NOTE: This class must NOT import any client-side classes (Minecraft, Screens, etc.)
 * to prevent crashes on the dedicated server.
 */
public class OpenSupportCheckoutPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "open_support_checkout");
    private static final int MAX_TEXT_LENGTH = 2048;

    // The checkout's address, or the reason there is none
    private final boolean checkout;
    private final String text;

    private OpenSupportCheckoutPacket(boolean checkout, String text) {
        this.checkout = checkout;
        this.text = text;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(checkout);
        buffer.writeUtf(text, MAX_TEXT_LENGTH);
        return buffer;
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        boolean checkout = buf.readBoolean();
        String text = buf.readUtf(MAX_TEXT_LENGTH);

        context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
            // Fully qualified, so the client class loads only on the client
            if (checkout) {
                com.theplumteam.client.ClientSupportCheckout.open(text);
            } else {
                com.theplumteam.client.ClientSupportCheckout.show(text);
            }
        }));
    }

    public static void sendToPlayer(ServerPlayer player, String url) {
        PacketNetworking.sendToPlayer(player, ID, new OpenSupportCheckoutPacket(true, url).encode());
    }

    /** Tells the player, in the machine menu, why no checkout opens. */
    public static void sendMessage(ServerPlayer player, String message) {
        PacketNetworking.sendToPlayer(player, ID, new OpenSupportCheckoutPacket(false, message).encode());
    }

    /** Whether the address is a PayNow or Ko-fi page over HTTPS, the only checkouts handed out. */
    public static boolean isCheckoutUrl(@Nullable String url) {
        return isOn(url, "paynow.gg") || isKofi(url);
    }

    /** Whether the address is a Ko-fi page over HTTPS. */
    public static boolean isKofi(@Nullable String url) {
        return isOn(url, "ko-fi.com");
    }

    private static boolean isOn(@Nullable String url, String domain) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && (host.equals(domain) || host.endsWith("." + domain));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
