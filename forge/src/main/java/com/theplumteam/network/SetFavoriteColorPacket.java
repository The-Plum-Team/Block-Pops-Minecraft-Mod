package com.theplumteam.network;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionGenerator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Client-to-server packet that sends the player's chosen favorite color.
 * Sent when the player confirms their color choice in the FavoriteColorSelectionScreen.
 */
public class SetFavoriteColorPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetFavoriteColorPacket.class);

    private final String colorName;

    public SetFavoriteColorPacket(String colorName) {
        this.colorName = colorName;
    }

    public static void encode(SetFavoriteColorPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.colorName);
    }

    public static SetFavoriteColorPacket decode(FriendlyByteBuf buffer) {
        String colorName = buffer.readUtf();
        return new SetFavoriteColorPacket(colorName);
    }

    public static void handle(SetFavoriteColorPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the server thread
            ServerPlayer player = context.getSender();
            if (player != null) {
                player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    try {
                        // Parse the color string back to PopBlockColor enum
                        PopBlockColor color = PopBlockColor.valueOf(packet.colorName.toUpperCase());

                        // Set the favorite color and mark as chosen
                        discovery.setFavoriteColor(color);
                        discovery.setHasChosenFavoriteColor(true);

                        LOGGER.info("Player {} chose favorite color: {}",
                                player.getName().getString(), color.getSerializedName());

                        // Regenerate the World Players collection to reflect the updated color
                        if (player.getServer() != null) {
                            FigureCollection updatedCollection = PlayerCollectionGenerator.generate(player.getServer());
                            CollectionRegistry.registerDynamicCollection(updatedCollection);
                            LOGGER.info("Regenerated World Players collection after {} changed their favorite color",
                                    player.getName().getString());
                        }
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Player {} sent invalid color name: {}",
                                player.getName().getString(), packet.colorName);
                    }
                });
            }
        });
        context.setPacketHandled(true);
    }
}
