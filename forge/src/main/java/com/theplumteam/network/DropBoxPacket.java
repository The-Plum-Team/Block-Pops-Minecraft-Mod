// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\network\DropBoxPacket.java ==========
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
import com.theplumteam.server.ServerTickHandler;
import net.minecraft.core.BlockPos;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class DropBoxPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(DropBoxPacket.class);

    private final BlockPos pos;
    private final String collectionId;
    private final TokenType tokenType;

    public DropBoxPacket(BlockPos pos, String collectionId, TokenType tokenType) {
        this.pos = pos;
        this.collectionId = collectionId;
        this.tokenType = tokenType;
    }

    public static void encode(DropBoxPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeUtf(packet.collectionId);
        buffer.writeEnum(packet.tokenType);
    }

    public static DropBoxPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String collectionId = buffer.readUtf();
        TokenType tokenType = buffer.readEnum(TokenType.class);
        return new DropBoxPacket(pos, collectionId, tokenType);
    }

    public static void handle(DropBoxPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        LOGGER.info("Received drop box packet on server - Position: {}, Collection ID: {}, Token Type: {}",
                packet.pos, packet.collectionId, packet.tokenType);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player: {} - Processing {} token request",
                        player.getName().getString(), packet.tokenType);

                if (player.getInventory().getFreeSlot() == -1) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cInventory is full! Cannot receive figure box."));
                    LOGGER.info("Player {} inventory is full, token not consumed", player.getName().getString());
                    return;
                }

                player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    if (!verifyAndConsumeToken(player, discovery, packet.tokenType)) {
                        LOGGER.warn("Player {} tried to use unavailable {} token",
                                player.getName().getString(), packet.tokenType);
                        return;
                    }
                    processBoxDrop(player, packet, discovery);
                });
            } else {
                LOGGER.warn("Player is null in packet handler!");
            }
        });
        context.setPacketHandled(true);
    }

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

    // Helper to reflectively get Quick Skin ID from server repo
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
                if (packet.collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
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

                    if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        // Capture Mojang Snapshot
                        GameProfile freshProfile = getFreshGameProfile(player, selectedFigure);
                        if (freshProfile != null && !freshProfile.getProperties().get("textures").isEmpty()) {
                            skinSnapshot = freshProfile.getProperties().get("textures").iterator().next().getValue();
                            discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                            LOGGER.info("Saved/updated fresh skin snapshot for {}.", uniqueFigureId);
                        }

                        // Capture Quick Skin Snapshot
                        // Only applicable if the figure represents the current player (usually World Players collection)
                        // or if we could look up other players' quick skins (ServerPlayerAppearanceRepository does hold all players)
                        if (selectedFigure.getPlayerUUID() != null) {
                            String qsId = getQuickSkinIdFromServer(selectedFigure.getPlayerUUID());
                            if (qsId != null && !qsId.isEmpty()) {
                                quickSkinSnapshot = qsId;
                                LOGGER.info("Captured Quick Skin ID for figure {}: {}", uniqueFigureId, qsId);
                            }
                        }
                    }

                    if (!discovery.isDiscovered(uniqueFigureId)) {
                        discovery.discover(uniqueFigureId);
                        UnlockFigurePacket unlockPacket = new UnlockFigurePacket(uniqueFigureId, selectedFigure.getName(), skinSnapshot);
                        BlockPopsModForge.NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), unlockPacket);
                        LOGGER.info("Player {} discovered new figure: {} ({})", player.getName().getString(), selectedFigure.getName(), uniqueFigureId);
                    }

                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", selectedFigure.getId());
                    blockEntityTag.putString("CollectionId", packet.collectionId);

                    if (packet.collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
                        PopBlockColor color = selectedFigure.getFavoriteColor();
                        if (color == null) color = PopBlockColor.ORIGINAL;
                        blockEntityTag.putString("Color", color.name());
                    }

                    if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                        blockEntityTag.putString("SkinSnapshot", skinSnapshot);
                    } else if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
                        if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", oldSnapshot);
                        }
                    }

                    // Save Quick Skin ID to NBT if found
                    if (quickSkinSnapshot != null) {
                        blockEntityTag.putString("QuickSkinId", quickSkinSnapshot);
                    }

                    boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                    player.getInventory().add(boxItem);
                }
            }
        });
    }

    private static boolean verifyAndConsumeToken(ServerPlayer player, IPlayerDiscovery discovery, TokenType tokenType) {
        if (tokenType == TokenType.REGULAR) {
            if (discovery.getRegularTokens() > 0) {
                discovery.setRegularTokens(discovery.getRegularTokens() - 1);
                LOGGER.info("Player {} used a regular token. Remaining: {}",
                        player.getName().getString(), discovery.getRegularTokens());
                syncTokenDataToClient(player, discovery);
                return true;
            }
        } else if (tokenType == TokenType.GUARANTEED) {
            if (!discovery.hasUsedTodaySpecialToken()) {
                discovery.setUsedTodaySpecialToken(true);
                LOGGER.info("Player {} used their guaranteed token",
                        player.getName().getString());
                syncTokenDataToClient(player, discovery);
                return true;
            }
        }
        return false;
    }

    private static void syncTokenDataToClient(ServerPlayer player, IPlayerDiscovery discovery) {
        long gameTime = player.serverLevel().getGameTime();
        long nextRegularTime = discovery.getNextRegularTokenTime();
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
        long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

        SyncTokenDataPacket packet = new SyncTokenDataPacket(
                discovery.getRegularTokens(),
                ticksUntilNext,
                !discovery.hasUsedTodaySpecialToken(),
                millisUntilReset
        );

        BlockPopsModForge.NETWORK_CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                packet
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
                LOGGER.info("Guaranteed token logic: Selected undiscovered figure '{}'", selected.getId());
                return selected;
            } else {
                LOGGER.info("Guaranteed token logic: Collection complete, giving random duplicate");
                return figures.get(random.nextInt(figures.size()));
            }
        } else {
            return figures.get(random.nextInt(figures.size()));
        }
    }
}