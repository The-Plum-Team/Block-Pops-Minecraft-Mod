package com.theplumteam.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.registry.ModItems;
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

                // Get player capability and verify token
                player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                    // Verify and consume token
                    if (!verifyAndConsumeToken(player, discovery, packet.tokenType)) {
                        LOGGER.warn("Player {} tried to use unavailable {} token",
                                player.getName().getString(), packet.tokenType);
                        return;
                    }

                    // Token verified and consumed, proceed with drop
                    processBoxDrop(player, packet, discovery);
                });
            } else {
                LOGGER.warn("Player is null in packet handler!");
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * Always fetches a fresh GameProfile from the session service to avoid using stale, cached skins.
     */
    @Nullable
    private static GameProfile getFreshGameProfile(ServerPlayer player, FigureDefinition figure) {
        if (figure.getPlayerUUID() == null) return null;
        try {
            // Create a shell profile and force the session service to fill it with fresh, signed properties.
            GameProfile freshProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            return player.getServer().getSessionService().fillProfileProperties(freshProfile, true);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch fresh GameProfile for {}: {}", figure.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Process the actual box drop after token verification
     */
    private static void processBoxDrop(ServerPlayer player, DropBoxPacket packet, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(packet.collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();
            if (!figures.isEmpty()) {
                FigureDefinition selectedFigure = selectFigure(figures, packet.tokenType,
                        discovery, packet.collectionId);

                // Get the appropriate box item - collection/color is already preset in NBT by BoxBlockItem
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

                    if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        GameProfile freshProfile = getFreshGameProfile(player, selectedFigure);
                        if (freshProfile != null && !freshProfile.getProperties().get("textures").isEmpty()) {
                            skinSnapshot = freshProfile.getProperties().get("textures").iterator().next().getValue();
                            discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                            LOGGER.info("Saved/updated fresh skin snapshot for {}.", uniqueFigureId);
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

                    // For world_players collection, also set the color in NBT so the box uses the right texture
                    if (packet.collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
                        PopBlockColor color = selectedFigure.getFavoriteColor();
                        if (color == null) color = PopBlockColor.ORIGINAL;
                        blockEntityTag.putString("Color", color.name());
                    }

                    if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                        blockEntityTag.putString("SkinSnapshot", skinSnapshot);
                        LOGGER.info("Added fresh skin snapshot to box item NBT for figure: {}", uniqueFigureId);
                    } else if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
                        if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", oldSnapshot);
                        }
                    }

                    boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                    ItemEntity itemEntity = new ItemEntity(player.level(), packet.pos.getX() + 0.5, packet.pos.getY() + 1.0, packet.pos.getZ() + 0.5, boxItem);
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);
                }
            }
        });
    }

    // ... The rest of the file (verifyAndConsumeToken, syncTokenDataToClient, etc.) remains unchanged ...
    private static boolean verifyAndConsumeToken(ServerPlayer player, IPlayerDiscovery discovery, TokenType tokenType) {
        if (tokenType == TokenType.REGULAR) {
            if (discovery.getRegularTokens() > 0) {
                discovery.setRegularTokens(discovery.getRegularTokens() - 1);
                LOGGER.info("Player {} used a regular token. Remaining: {}",
                        player.getName().getString(), discovery.getRegularTokens());

                // Sync token data to client
                syncTokenDataToClient(player, discovery);
                return true;
            }
        } else if (tokenType == TokenType.GUARANTEED) {
            if (!discovery.hasUsedTodaySpecialToken()) {
                discovery.setUsedTodaySpecialToken(true);
                LOGGER.info("Player {} used their guaranteed token",
                        player.getName().getString());

                // Sync token data to client
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
        long millisUntilReset = calculateMillisUntilNextReset();

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
    private static long calculateMillisUntilNextReset() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
        java.time.ZonedDateTime nextReset = now.withHour(18).withMinute(0).withSecond(0).withNano(0);

        if (now.getHour() >= 18) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }
    private static FigureDefinition selectFigure(List<FigureDefinition> figures, TokenType tokenType,
                                                 IPlayerDiscovery discovery, String collectionId) {
        Random random = new Random();

        if (tokenType == TokenType.GUARANTEED) {
            // Get all discovered figures for this collection
            Set<String> discoveredSet = discovery.getDiscoveredSet();

            // Build list of undiscovered figures
            List<FigureDefinition> undiscoveredFigures = new ArrayList<>();
            for (FigureDefinition figure : figures) {
                String figureId = collectionId + ":" + figure.getId();
                if (!discoveredSet.contains(figureId)) {
                    undiscoveredFigures.add(figure);
                }
            }

            // If there are undiscovered figures, pick one randomly
            if (!undiscoveredFigures.isEmpty()) {
                FigureDefinition selected = undiscoveredFigures.get(random.nextInt(undiscoveredFigures.size()));
                LOGGER.info("Guaranteed token: Selected undiscovered figure '{}' from {} options",
                        selected.getId(), undiscoveredFigures.size());
                return selected;
            } else {
                // Collection is complete, give a random duplicate as fallback
                LOGGER.info("Guaranteed token: Collection complete, giving random duplicate");
                return figures.get(random.nextInt(figures.size()));
            }
        } else {
            // REGULAR token: just pick any random figure
            return figures.get(random.nextInt(figures.size()));
        }
    }
}