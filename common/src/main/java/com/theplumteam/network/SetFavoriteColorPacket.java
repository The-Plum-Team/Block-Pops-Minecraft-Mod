package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.PlayerCollectionHelper;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-to-server packet that sends the player's chosen favorite color.
 * Sent when the player confirms their color choice in the FavoriteColorSelectionScreen.
 */
public class SetFavoriteColorPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetFavoriteColorPacket.class);
    public static final ResourceLocation ID = new ResourceLocation(BlockPopsMod.MOD_ID, "set_favorite_color");

    private final String colorName;

    public SetFavoriteColorPacket(String colorName) {
        this.colorName = colorName;
    }

    /**
     * Encode the packet to a buffer for sending to the server
     */
    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(colorName);
        return buffer;
    }

    /**
     * Decode a packet from a buffer
     */
    public static SetFavoriteColorPacket decode(FriendlyByteBuf buffer) {
        String colorName = buffer.readUtf();
        return new SetFavoriteColorPacket(colorName);
    }

    /**
     * Handle the packet on the server side
     */
    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        SetFavoriteColorPacket packet = decode(buf);

        context.queue(() -> {
            // This runs on the server thread
            if (context.getPlayer() instanceof ServerPlayer player) {
                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);

                try {
                    // Parse the color string back to PopBlockColor enum
                    PopBlockColor color = PopBlockColor.valueOf(packet.colorName.toUpperCase());

                    // Set the favorite color and mark as chosen
                    discovery.setFavoriteColor(color);
                    discovery.setHasChosenFavoriteColor(true);
                    PlayerDataManager.markDirty(player, discovery);

                    BlockPopsMod.logDebug("Player {} chose favorite color: {}",
                            player.getName().getString(), color.getSerializedName());

                    // Regenerate the World Players collection to reflect the updated color
                    // Uses platform-specific implementation via PlayerCollectionHelper
                    if (player.getServer() != null) {
                        PlayerCollectionHelper.regenerateAndSyncPlayerCollection(player.getServer());
                        BlockPopsMod.logDebug("Regenerated World Players collection after {} changed their favorite color",
                                player.getName().getString());
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Player {} sent invalid color name: {}",
                            player.getName().getString(), packet.colorName);
                }
            }
        });
    }

    /**
     * Send this packet to the server
     */
    public void sendToServer() {
        NetworkManager.sendToServer(ID, encode());
    }
}
