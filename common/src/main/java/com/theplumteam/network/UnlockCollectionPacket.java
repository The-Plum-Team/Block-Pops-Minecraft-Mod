package com.theplumteam.network;

import com.theplumteam.util.ServerLevels;
import com.mojang.authlib.GameProfile;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModItems;
import com.theplumteam.util.AuthlibProfiles;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Client-to-server packet that unlocks an entire collection for a player.
 * This is used by the cheats system to give players all figures in a collection.
 */
public class UnlockCollectionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnlockCollectionPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "unlock_collection");

    private final String collectionId;

    public UnlockCollectionPacket(String collectionId) {
        this.collectionId = collectionId;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(collectionId);
        return buffer;
    }

    public static UnlockCollectionPacket decode(FriendlyByteBuf buffer) {
        String collectionId = buffer.readUtf();
        return new UnlockCollectionPacket(collectionId);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UnlockCollectionPacket packet = decode(buf);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                BlockPopsMod.logDebug("Player {} requested to unlock collection: {}",
                        player.getName().getString(), packet.collectionId);

                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
                unlockEntireCollection(player, packet.collectionId, discovery);
                PlayerDataManager.markDirty(player, discovery);
            }
        });
    }

    private static void unlockEntireCollection(ServerPlayer player, String collectionId, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();

            if (figures.isEmpty()) {
                LOGGER.warn("Collection {} has no figures", collectionId);
                return;
            }

            BlockPopsMod.logDebug("Unlocking {} figures from collection {} for player {}",
                    figures.size(), collectionId, player.getName().getString());

            for (FigureDefinition figure : figures) {
                giveBoxForFigure(player, collectionId, figure, discovery);
            }

            BlockPopsMod.logDebug("Successfully unlocked all figures from collection {} for player {}",
                    collectionId, player.getName().getString());
        });
    }

    @Nullable
    private static String getQuickSkinIdFromServer(UUID playerId) {
        try {
            Class<?> repoClass = Class.forName("com.quickskin.mod.server.data.ServerPlayerAppearanceRepository");
            java.lang.reflect.Method getInstanceMethod = repoClass.getMethod("getInstance");
            Object repoInstance = getInstanceMethod.invoke(null);

            java.lang.reflect.Method getAppearanceMethod = repoClass.getMethod("getAppearance", UUID.class);
            Object appearance = getAppearanceMethod.invoke(repoInstance, playerId);

            if (appearance != null) {
                Class<?> appearanceClass = appearance.getClass();
                java.lang.reflect.Method getSkinIdMethod = appearanceClass.getMethod("getSkinId");
                return (String) getSkinIdMethod.invoke(appearance);
            }
        } catch (Exception e) {
            // Quick Skin not installed or error accessing
        }
        return null;
    }

    private static void giveBoxForFigure(ServerPlayer player, String collectionId,
                                         FigureDefinition figure, IPlayerDiscovery discovery) {
        ItemStack boxItem = null;
        if (collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
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
        String quickSkinSnapshot = null;

        if (figure.getType() == FigureType.PLAYER) {
            GameProfile freshProfile = getFreshGameProfile(player, figure);
            if (freshProfile != null && !AuthlibProfiles.properties(freshProfile).get("textures").isEmpty()) {
                skinSnapshot = AuthlibProfiles.value(AuthlibProfiles.properties(freshProfile).get("textures").iterator().next());
                discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                LOGGER.debug("Saved skin snapshot for player figure: {}", uniqueFigureId);
            }

            if (figure.getPlayerUUID() != null) {
                String qsId = getQuickSkinIdFromServer(figure.getPlayerUUID());
                if (qsId != null && !qsId.isEmpty()) {
                    quickSkinSnapshot = qsId;
                    // SAVE TO DISCOVERY
                    discovery.saveFigureQuickSkin(uniqueFigureId, quickSkinSnapshot);
                    BlockPopsMod.logDebug("Captured & Saved Quick Skin ID for figure {}: {}", uniqueFigureId, qsId);
                }
            }
        }

        if (!discovery.isDiscovered(uniqueFigureId)) {
            discovery.discover(uniqueFigureId);
            // PASS QUICKSKIN ID TO CLIENT
            UnlockFigurePacket.sendToPlayer(player, uniqueFigureId, figure.getName(), skinSnapshot, quickSkinSnapshot);
            LOGGER.debug("Unlocked new figure: {} ({})", figure.getName(), uniqueFigureId);
        }

        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putString("FigureId", figure.getId());
        blockEntityTag.putString("CollectionId", collectionId);

        if (collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
            PopBlockColor color = figure.getFavoriteColor();
            if (color == null) color = PopBlockColor.ORIGINAL;
            blockEntityTag.putString("Color", color.name());
        }

        if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
            blockEntityTag.putString("SkinSnapshot", skinSnapshot);
        } else if (figure.getType() == FigureType.PLAYER) {
            String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
            if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                blockEntityTag.putString("SkinSnapshot", oldSnapshot);
            }
        }

        if (quickSkinSnapshot != null) {
            blockEntityTag.putString("QuickSkinId", quickSkinSnapshot);
        }

        BlockEntityItemData.write(boxItem, blockEntityTag, "blockpops:box_block");

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

    @Nullable
    private static GameProfile getFreshGameProfile(ServerPlayer player, FigureDefinition figure) {
        if (figure.getPlayerUUID() == null) return null;
        try {
            GameProfile freshProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            return AuthlibProfiles.fetch(ServerLevels.serverOf(player).getSessionService(), freshProfile);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch fresh GameProfile for {}: {}", figure.getName(), e.getMessage());
            return null;
        }
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
