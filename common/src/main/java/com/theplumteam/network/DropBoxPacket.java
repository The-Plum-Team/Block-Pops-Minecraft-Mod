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
import com.theplumteam.server.ServerTickHandler;
import com.theplumteam.util.AuthlibProfiles;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
                    player.sendSystemMessage(Component.literal("\u00A7cInventory is full! Cannot receive figure box."));
                    BlockPopsMod.logDebug("Player {} inventory is full, token not consumed", player.getName().getString());
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
    private static GameProfile getFreshGameProfile(ServerPlayer player, FigureDefinition figure) {
        if (figure.getPlayerUUID() == null) return null;
        try {
            GameProfile freshProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            return AuthlibProfiles.fetch(player.getServer().getSessionService(), freshProfile);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch fresh GameProfile for {}: {}", figure.getName(), e.getMessage());
            return null;
        }
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
            List<FigureDefinition> figures = collection.getFigures();
            if (!figures.isEmpty()) {
                FigureDefinition selectedFigure = selectFigure(figures, packet.tokenType,
                        discovery, packet.collectionId);

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
                        GameProfile freshProfile = getFreshGameProfile(player, selectedFigure);
                        if (freshProfile != null && !freshProfile.getProperties().get("textures").isEmpty()) {
                            skinSnapshot = AuthlibProfiles.value(freshProfile.getProperties().get("textures").iterator().next());
                            discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                            BlockPopsMod.logDebug("Saved/updated fresh skin snapshot for {}.", uniqueFigureId);
                        }

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

                    if (!discovery.isDiscovered(uniqueFigureId)) {
                        discovery.discover(uniqueFigureId);
                        // Use cross-platform networking - PASS QUICKSKIN ID TO CLIENT
                        UnlockFigurePacket.sendToPlayer(player, uniqueFigureId, selectedFigure.getName(), skinSnapshot, quickSkinSnapshot);
                        BlockPopsMod.logDebug("Player {} discovered new figure: {} ({})", player.getName().getString(), selectedFigure.getName(), uniqueFigureId);
                        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
                    } else {
                        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
                    }

                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", selectedFigure.getId());
                    blockEntityTag.putString("CollectionId", packet.collectionId);

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

    private static boolean verifyAndConsumeToken(ServerPlayer player, IPlayerDiscovery discovery, TokenType tokenType) {
        if (tokenType == TokenType.REGULAR) {
            if (discovery.getRegularTokens() > 0) {
                discovery.setRegularTokens(discovery.getRegularTokens() - 1);
                BlockPopsMod.logDebug("Player {} used a regular token. Remaining: {}",
                        player.getName().getString(), discovery.getRegularTokens());
                syncTokenDataToClient(player, discovery);
                return true;
            }
        } else if (tokenType == TokenType.GUARANTEED) {
            if (!discovery.hasUsedTodaySpecialToken()) {
                discovery.setUsedTodaySpecialToken(true);
                BlockPopsMod.logDebug("Player {} used their guaranteed token",
                        player.getName().getString());
                syncTokenDataToClient(player, discovery);
                return true;
            }
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
                !discovery.hasUsedTodaySpecialToken(),
                millisUntilReset
        );
    }

    private static FigureDefinition selectFigure(List<FigureDefinition> figures, TokenType tokenType,
                                                 IPlayerDiscovery discovery, String collectionId) {
        Random random = new Random();

        if (tokenType == TokenType.GUARANTEED) {
            Set<String> discoveredSet = discovery.getDiscoveredSet();
            List<FigureDefinition> undiscoveredFigures = new ArrayList<>();
            for (FigureDefinition figure : figures) {
                String figureId = collectionId + ":" + figure.getId();
                if (!discoveredSet.contains(figureId)) {
                    undiscoveredFigures.add(figure);
                }
            }

            if (!undiscoveredFigures.isEmpty()) {
                FigureDefinition selected = undiscoveredFigures.get(random.nextInt(undiscoveredFigures.size()));
                BlockPopsMod.logDebug("Guaranteed token logic: Selected undiscovered figure '{}'", selected.getId());
                return selected;
            } else {
                BlockPopsMod.logDebug("Guaranteed token logic: Collection complete, giving random duplicate");
                return figures.get(random.nextInt(figures.size()));
            }
        } else {
            return figures.get(random.nextInt(figures.size()));
        }
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
