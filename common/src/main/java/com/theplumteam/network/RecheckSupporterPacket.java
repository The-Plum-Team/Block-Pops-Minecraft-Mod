package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.SpecialAccess;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet a client sends once it linked its player's Ko-fi payment in the game:
 * the server asks the supporter service about the player again, and keeps asking for a while,
 * rather than waiting for its kept answer to run out. It carries nothing the server trusts; the
 * service's answer is what counts. A server from before it drops the packet.
 */
public class RecheckSupporterPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "recheck_supporter");

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        // A dummy byte: empty packets can cause issues with Architectury networking
        buffer.writeBoolean(true);
        return buffer;
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        buf.readBoolean();
        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                SpecialAccess.recheck(player);
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
