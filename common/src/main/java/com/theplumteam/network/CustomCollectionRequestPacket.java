package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-to-server packet in which an operator enables a Custom collection by its access
 * code, or disables one by its id. The server looks the code up itself and answers with
 * a {@link CustomCollectionStatusPacket}.
 */
public class CustomCollectionRequestPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomCollectionRequestPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "custom_collection_request");
    private static final int MAX_ARGUMENT = 64;

    private final boolean enable;
    private final String argument;

    /**
     * @param enable   true to enable a collection by access code, false to disable one by id
     * @param argument the access code, or the collection id
     */
    public CustomCollectionRequestPacket(boolean enable, String argument) {
        this.enable = enable;
        this.argument = argument;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(enable);
        buffer.writeUtf(argument, MAX_ARGUMENT);
        return buffer;
    }

    public static CustomCollectionRequestPacket decode(FriendlyByteBuf buffer) {
        boolean enable = buffer.readBoolean();
        return new CustomCollectionRequestPacket(enable, buffer.readUtf(MAX_ARGUMENT));
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        CustomCollectionRequestPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!ServerLevels.hasCommandLevel(player, 2)) {
                LOGGER.warn("Player {} tried to change Custom collections without permission",
                        player.getName().getString());
                return;
            }
            if (packet.enable) {
                CustomCollections.enableByCode(ServerLevels.serverOf(player), packet.argument,
                        status -> CustomCollectionStatusPacket.send(player, status));
            } else {
                CustomCollections.disable(ServerLevels.serverOf(player), packet.argument,
                        status -> CustomCollectionStatusPacket.send(player, status));
            }
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
