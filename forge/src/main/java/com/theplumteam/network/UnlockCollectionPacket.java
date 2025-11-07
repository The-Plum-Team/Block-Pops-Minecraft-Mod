package com.theplumteam.network;

import com.mojang.authlib.GameProfile;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client-to-server packet that unlocks an entire collection for a player.
 * This is used by the cheats system to give players all figures in a collection.
 */
public class UnlockCollectionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnlockCollectionPacket.class);

    private final String collectionId;

    public UnlockCollectionPacket(String collectionId) {
        this.collectionId = collectionId;
    }

    public static void encode(UnlockCollectionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.collectionId);
    }

    public static UnlockCollectionPacket decode(FriendlyByteBuf buffer) {
        String collectionId = buffer.readUtf();
        return new UnlockCollectionPacket(collectionId);
    }

    public static void handle(UnlockCollectionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player {} requested to unlock collection: {}",
                    player.getName().getString(), packet.collectionId);

                player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    unlockEntireCollection(player, packet.collectionId, discovery);
                });
            } else {
                LOGGER.warn("Player is null in UnlockCollectionPacket handler!");
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * Unlocks all figures in a collection and gives the player all boxes
     */
    private static void unlockEntireCollection(ServerPlayer player, String collectionId, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();

            if (figures.isEmpty()) {
                LOGGER.warn("Collection {} has no figures", collectionId);
                return;
            }

            LOGGER.info("Unlocking {} figures from collection {} for player {}",
                figures.size(), collectionId, player.getName().getString());

            // Unlock and give each figure
            for (FigureDefinition figure : figures) {
                giveBoxForFigure(player, collectionId, figure, discovery);
            }

            LOGGER.info("Successfully unlocked all figures from collection {} for player {}",
                collectionId, player.getName().getString());
        });
    }

    /**
     * Gives a player a box for a specific figure and unlocks it
     */
    private static void giveBoxForFigure(ServerPlayer player, String collectionId,
                                         FigureDefinition figure, IPlayerDiscovery discovery) {
        // Get the appropriate box item - collection/color is already preset in NBT by BoxBlockItem
        ItemStack boxItem = null;
        if (collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
            PopBlockColor color = figure.getFavoriteColor();
            if (color == null) color = PopBlockColor.ORIGINAL;
            boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
        } else if (ModItems.BOX_BLOCK_ITEMS.containsKey(collectionId)) {
            boxItem = new ItemStack(ModItems.BOX_BLOCK_ITEMS.get(collectionId).get());
        } else {
            boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
        }

        if (boxItem == null) {
            LOGGER.warn("Could not find box item for collection {}", collectionId);
            return;
        }
        String uniqueFigureId = collectionId + ":" + figure.getId();
        String skinSnapshot = null;

        // Handle player figures with fresh skin data
        if (figure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
            GameProfile freshProfile = getFreshGameProfile(player, figure);
            if (freshProfile != null && !freshProfile.getProperties().get("textures").isEmpty()) {
                skinSnapshot = freshProfile.getProperties().get("textures").iterator().next().getValue();
                discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                LOGGER.debug("Saved skin snapshot for player figure: {}", uniqueFigureId);
            }
        }

        // Unlock the figure if not already discovered
        if (!discovery.isDiscovered(uniqueFigureId)) {
            discovery.discover(uniqueFigureId);
            UnlockFigurePacket unlockPacket = new UnlockFigurePacket(uniqueFigureId, figure.getName(), skinSnapshot);
            BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), unlockPacket);
            LOGGER.debug("Unlocked new figure: {} ({})", figure.getName(), uniqueFigureId);
        }

        // Set NBT data for the box
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putString("FigureId", figure.getId());
        blockEntityTag.putString("CollectionId", collectionId);

        // For world_players collection, also set the color in NBT so the box uses the right texture
        if (collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
            PopBlockColor color = figure.getFavoriteColor();
            if (color == null) color = PopBlockColor.ORIGINAL;
            blockEntityTag.putString("Color", color.name());
        }

        if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
            blockEntityTag.putString("SkinSnapshot", skinSnapshot);
        } else if (figure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
            String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
            if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                blockEntityTag.putString("SkinSnapshot", oldSnapshot);
            }
        }

        boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

        // Drop the box near the player
        ItemEntity itemEntity = new ItemEntity(
            player.level(),
            player.getX(),
            player.getY() + 1.0,
            player.getZ(),
            boxItem
        );
        itemEntity.setDeltaMovement(0, 0.2, 0);
        player.level().addFreshEntity(itemEntity);
    }

    /**
     * Fetches a fresh GameProfile from the session service for player figures
     */
    @Nullable
    private static GameProfile getFreshGameProfile(ServerPlayer player, FigureDefinition figure) {
        if (figure.getPlayerUUID() == null) return null;
        try {
            GameProfile freshProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            return player.getServer().getSessionService().fillProfileProperties(freshProfile, true);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch fresh GameProfile for {}: {}", figure.getName(), e.getMessage());
            return null;
        }
    }
}
