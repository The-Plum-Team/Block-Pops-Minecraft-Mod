package com.theplumteam.network;

import com.theplumteam.util.ServerLevels;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.DrawOdds;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.figure.Holographic;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.item.PlushPrizes;
import com.theplumteam.registry.ModItems;
import com.theplumteam.server.ServerTickHandler;
import com.theplumteam.server.PlayerSkins;
import com.theplumteam.server.SpecialAccess;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Client-to-server packet for dropping a box from the claw machine.
 * Handles token consumption, figure selection, and box item creation.
 */
public class DropBoxPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(DropBoxPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "drop_box");

    private final BlockPos pos;
    private final String collectionId;
    private final TokenType tokenType;

    public DropBoxPacket(BlockPos pos, String collectionId, TokenType tokenType) {
        this.pos = pos;
        this.collectionId = collectionId;
        this.tokenType = tokenType;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(pos);
        buffer.writeUtf(collectionId);
        buffer.writeEnum(tokenType);
        return buffer;
    }

    public static DropBoxPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String collectionId = buffer.readUtf();
        TokenType tokenType = buffer.readEnum(TokenType.class);
        return new DropBoxPacket(pos, collectionId, tokenType);
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        DropBoxPacket packet = decode(buf);

        BlockPopsMod.logDebug("Received drop box packet on server - Position: {}, Collection ID: {}, Token Type: {}",
                packet.pos, packet.collectionId, packet.tokenType);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                BlockPopsMod.logDebug("Player: {} - Processing {} token request",
                        player.getName().getString(), packet.tokenType);

                if (player.getInventory().getFreeSlot() == -1) {
                    player.sendSystemMessage(Component.translatable("blockpops.message.inventory_full")
                            .withStyle(ChatFormatting.RED));
                    BlockPopsMod.logDebug("Player {} inventory is full, token not consumed", player.getName().getString());
                    return;
                }

                // The list only leaves a hidden collection out; a machine set to it before, or a
                // modified client, still names it here
                if (ServerConfig.getInstance().getHiddenCollections().contains(packet.collectionId)) {
                    player.sendSystemMessage(Component.translatable("blockpops.message.collection_unavailable")
                            .withStyle(ChatFormatting.RED));
                    BlockPopsMod.logDebug("Collection {} is hidden, token not consumed", packet.collectionId);
                    return;
                }

                // Every World Players figure can be disabled, and an empty pool must not cost a token
                FigureCollection collection = CollectionRegistry.getCollection(packet.collectionId).orElse(null);
                List<FigureDefinition> pool = collection != null
                        ? SpecialAccess.figuresFor(player, collection) : List.of();
                if (pool.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("blockpops.message.collection_empty")
                            .withStyle(ChatFormatting.RED));
                    BlockPopsMod.logDebug("Collection {} has no available figures, token not consumed", packet.collectionId);
                    return;
                }

                // A plush with nothing to hand out is refused here, while the token is still unspent
                if (collection.isPlush() && !PlushPrizes.canGiveAll(packet.collectionId, pool)) {
                    player.sendSystemMessage(Component.translatable("blockpops.message.collection_unavailable")
                            .withStyle(ChatFormatting.RED));
                    LOGGER.warn("Plush collection {} names a figure that is not a registered plush, token not consumed",
                            packet.collectionId);
                    return;
                }

                IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
                if (!verifyAndConsumeToken(player, discovery, packet.tokenType)) {
                    LOGGER.warn("Player {} tried to use unavailable {} token",
                            player.getName().getString(), packet.tokenType);
                    // Force sync to correct client state to prevent "fake" token UI
                    syncTokenDataToClient(player, discovery);
                    return;
                }
                PlayerDataManager.markDirty(player, discovery);
                processBoxDrop(player, packet, discovery);
            }
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

    private static void processBoxDrop(ServerPlayer player, DropBoxPacket packet, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(packet.collectionId).ifPresent(collection -> {
            // A collection's specials are only in the pool of a player with special access
            List<FigureDefinition> figures = SpecialAccess.figuresFor(player, collection);
            if (!figures.isEmpty()) {
                FigureDefinition selectedFigure = selectFigure(figures, packet.tokenType,
                        discovery, packet.collectionId);

                if (collection.isPlush()) {
                    // A plush collection hands out the plush itself, not a box
                    givePlush(player, packet.collectionId, selectedFigure, discovery);
                    return;
                }

                ItemStack boxItem = null;
                if (packet.collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
                    PopBlockColor color = selectedFigure.getFavoriteColor();
                    if (color == null) color = PopBlockColor.ORIGINAL;
                    boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
                } else if (ModItems.BOX_BLOCK_ITEMS.containsKey(packet.collectionId)) {
                    boxItem = new ItemStack(ModItems.BOX_BLOCK_ITEMS.get(packet.collectionId).get());
                } else {
                    boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
                }

                if (boxItem != null) {
                    String uniqueFigureId = packet.collectionId + ":" + selectedFigure.getId();
                    String skinSnapshot = null;
                    String quickSkinSnapshot = null;

                    if (selectedFigure.getType() == FigureType.PLAYER) {
                        // The skin known now; a fresh one is fetched without holding up the server
                        skinSnapshot = PlayerSkins.forFigure(player, uniqueFigureId, selectedFigure, discovery);

                        if (selectedFigure.getPlayerUUID() != null) {
                            String qsId = getQuickSkinIdFromServer(selectedFigure.getPlayerUUID());
                            if (qsId != null && !qsId.isEmpty()) {
                                quickSkinSnapshot = qsId;
                                // SAVE TO DISCOVERY
                                discovery.saveFigureQuickSkin(uniqueFigureId, quickSkinSnapshot);
                                BlockPopsMod.logDebug("Captured & Saved Quick Skin ID for figure {}: {}", uniqueFigureId, qsId);
                            }
                        }
                    }

                    boolean isNew = !discovery.isDiscovered(uniqueFigureId);
                    boolean holographic = Holographic.roll();
                    // Ahead of the unlock: the screen plays the draw out and sounds its result
                    DrawResultPacket.sendToPlayer(player, packet.collectionId, selectedFigure.getId(), isNew, holographic);
                    if (isNew) {
                        discovery.discover(uniqueFigureId);
                        // Use cross-platform networking - PASS QUICKSKIN ID TO CLIENT
                        UnlockFigurePacket.sendToPlayer(player, uniqueFigureId, selectedFigure.getName(), skinSnapshot, quickSkinSnapshot);
                        BlockPopsMod.logDebug("Player {} discovered new figure: {} ({})", player.getName().getString(), selectedFigure.getName(), uniqueFigureId);
                    }

                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", selectedFigure.getId());
                    blockEntityTag.putString("CollectionId", packet.collectionId);
                    if (holographic) {
                        blockEntityTag.putBoolean(Holographic.KEY, true);
                    }

                    if (packet.collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
                        PopBlockColor color = selectedFigure.getFavoriteColor();
                        if (color == null) color = PopBlockColor.ORIGINAL;
                        blockEntityTag.putString("Color", color.name());
                    }

                    if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                        blockEntityTag.putString("SkinSnapshot", skinSnapshot);
                    } else if (selectedFigure.getType() == FigureType.PLAYER) {
                        String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
                        if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", oldSnapshot);
                        }
                    }

                    if (quickSkinSnapshot != null) {
                        blockEntityTag.putString("QuickSkinId", quickSkinSnapshot);
                    }

                    BlockEntityItemData.write(boxItem, blockEntityTag, "blockpops:box_block");
                    player.getInventory().add(boxItem);
                    PlayerDataManager.markDirty(player, discovery);
                }
            }
        });
    }

    private static void givePlush(ServerPlayer player, String collectionId, FigureDefinition figure,
                                  IPlayerDiscovery discovery) {
        boolean holographic = Holographic.roll();
        ItemStack plushItem = PlushPrizes.of(collectionId, figure, holographic);
        // The pool was checked before the token was spent, so this is only a guard
        if (plushItem == null) {
            LOGGER.warn("Plush collection {} names {}, which is not a registered plush", collectionId, figure.getId());
            return;
        }
        String uniqueFigureId = collectionId + ":" + figure.getId();
        boolean isNew = !discovery.isDiscovered(uniqueFigureId);
        DrawResultPacket.sendToPlayer(player, collectionId, figure.getId(), isNew, holographic);
        if (isNew) {
            discovery.discover(uniqueFigureId);
            UnlockFigurePacket.sendToPlayer(player, uniqueFigureId, figure.getName(), null, null);
        }
        player.getInventory().add(plushItem);
        PlayerDataManager.markDirty(player, discovery);
    }

    private static boolean verifyAndConsumeToken(ServerPlayer player, IPlayerDiscovery discovery, TokenType tokenType) {
        if (tokenType == TokenType.REGULAR) {
            if (discovery.getRegularTokens() > 0) {
                ServerTickHandler.onRegularTokenSpent(player, discovery);
                discovery.setRegularTokens(discovery.getRegularTokens() - 1);
                BlockPopsMod.logDebug("Player {} used a regular token. Remaining: {}",
                        player.getName().getString(), discovery.getRegularTokens());
                syncTokenDataToClient(player, discovery);
                return true;
            }
        } else if (tokenType == TokenType.GUARANTEED) {
            // Today's token goes first: it comes back at the daily reset, a ticket's does not
            if (!discovery.hasUsedTodaySpecialToken()) {
                discovery.setUsedTodaySpecialToken(true);
            } else if (discovery.getExtraGuaranteedTokens() > 0) {
                discovery.setExtraGuaranteedTokens(discovery.getExtraGuaranteedTokens() - 1);
            } else {
                return false;
            }
            BlockPopsMod.logDebug("Player {} used a guaranteed token. Remaining: {}",
                    player.getName().getString(), discovery.getGuaranteedTokens());
            syncTokenDataToClient(player, discovery);
            return true;
        }
        return false;
    }

    private static void syncTokenDataToClient(ServerPlayer player, IPlayerDiscovery discovery) {
        long gameTime = ServerLevels.of(player).getGameTime();
        long nextRegularTime = discovery.getNextRegularTokenTime();
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
        long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

        SyncTokenDataPacket.sendToPlayer(
                player,
                discovery.getRegularTokens(),
                ticksUntilNext,
                discovery.getGuaranteedTokens(),
                millisUntilReset
        );
    }

    private static FigureDefinition selectFigure(List<FigureDefinition> figures, TokenType tokenType,
                                                 IPlayerDiscovery discovery, String collectionId) {
        Random random = com.theplumteam.util.E2EDeterminism.random();

        if (tokenType == TokenType.GUARANTEED) {
            Set<String> discoveredSet = discovery.getDiscoveredSet();
            List<FigureDefinition> undiscoveredFigures = new ArrayList<>();
            for (FigureDefinition figure : figures) {
                String figureId = collectionId + ":" + figure.getId();
                if (!discoveredSet.contains(figureId)) {
                    undiscoveredFigures.add(figure);
                }
            }

            // Weighted among the figures still to find, so a guaranteed token keeps a legendary rare
            if (!undiscoveredFigures.isEmpty()) {
                FigureDefinition selected = DrawOdds.pick(undiscoveredFigures, random);
                BlockPopsMod.logDebug("Guaranteed token logic: Selected undiscovered figure '{}'", selected.getId());
                return selected;
            } else {
                BlockPopsMod.logDebug("Guaranteed token logic: Collection complete, giving random duplicate");
                return DrawOdds.pick(figures, random);
            }
        } else {
            return DrawOdds.pick(figures, random);
        }
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
