package com.theplumteam.network;

import com.theplumteam.util.ServerLevels;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.item.PlushPrizes;
import com.theplumteam.registry.ModItems;
import com.theplumteam.server.PlayerSkins;
import com.theplumteam.server.worldcollections.WorldCollections;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                // Check if player has permission (level 2, same as /blockpops getbox)
                if (!ServerLevels.hasCommandLevel(player, 2)) {
                    LOGGER.warn("Player {} tried to unlock a collection without permission", player.getName().getString());
                    return;
                }

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
            List<FigureDefinition> figures = WorldContentIds.isWorldCollectionId(collectionId)
                    ? figuresEnabledInRecord(collectionId, collection) : collection.getEnabledFigures();

            if (figures.isEmpty()) {
                LOGGER.warn("Collection {} has no figures", collectionId);
                return;
            }

            BlockPopsMod.logDebug("Unlocking {} figures from collection {} for player {}",
                    figures.size(), collectionId, player.getName().getString());

            for (FigureDefinition figure : figures) {
                if (collection.isPlush()) {
                    givePlush(player, collectionId, figure, discovery);
                } else {
                    giveBoxForFigure(player, collectionId, figure, discovery);
                }
            }

            BlockPopsMod.logDebug("Successfully unlocked all figures from collection {} for player {}",
                    collectionId, player.getName().getString());
        });
    }

    /**
     * The figures of a World collection that its record does not retire. A draft or archived
     * collection is registered with none of them enabled, yet an operator's unlock still gives
     * them, so a draft can be tried out before it is published.
     */
    private static List<FigureDefinition> figuresEnabledInRecord(String collectionId, FigureCollection collection) {
        WorldCollectionRecord record = WorldCollections.record(collectionId);
        if (record == null) {
            return List.of();
        }
        Set<String> enabled = new HashSet<>();
        for (WorldCollectionRecord.Figure figure : record.figures()) {
            if (figure.enabled()) {
                enabled.add(figure.id());
            }
        }
        List<FigureDefinition> figures = new ArrayList<>();
        for (FigureDefinition figure : collection.getFigures()) {
            if (enabled.contains(figure.getId())) {
                figures.add(figure);
            }
        }
        return figures;
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

    private static void givePlush(ServerPlayer player, String collectionId, FigureDefinition figure,
                                  IPlayerDiscovery discovery) {
        ItemStack plushItem = PlushPrizes.of(collectionId, figure);
        if (plushItem == null) {
            LOGGER.warn("Plush collection {} names {}, which is not a registered plush", collectionId, figure.getId());
            return;
        }
        String uniqueFigureId = collectionId + ":" + figure.getId();
        if (!discovery.isDiscovered(uniqueFigureId)) {
            discovery.discover(uniqueFigureId);
            UnlockFigurePacket.sendToPlayer(player, uniqueFigureId, figure.getName(), null, null);
        }

        ItemEntity itemEntity = new ItemEntity(
                player.level(),
                player.getX(),
                player.getY() + 1.0,
                player.getZ(),
                plushItem
        );
        itemEntity.setDeltaMovement(0, 0.2, 0);
        player.level().addFreshEntity(itemEntity);
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
            // The skin known now; a fresh one is fetched without holding up the server
            skinSnapshot = PlayerSkins.forFigure(player, uniqueFigureId, figure, discovery);

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

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
