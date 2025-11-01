package com.theplumteam.network;

import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Verify that the player has the requested token and consume it.
     * @return true if token was valid and consumed, false otherwise
     */
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

    /**
     * Sync token data back to the client after consumption
     */
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

    /**
     * Calculate milliseconds until the next daily reset at 18:00 UTC
     */
    private static long calculateMillisUntilNextReset() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
        java.time.ZonedDateTime nextReset = now.withHour(18).withMinute(0).withSecond(0).withNano(0);

        if (now.getHour() >= 18) {
            nextReset = nextReset.plusDays(1);
        }

        return nextReset.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
    }

    /**
     * Process the actual box drop after token verification
     */
    private static void processBoxDrop(ServerPlayer player, DropBoxPacket packet, IPlayerDiscovery discovery) {
                // Get the box block for this collection
                Block boxBlock = null;
                if (packet.collectionId.equals("default")) {
                    // For default collection, use the first color variant (white)
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                } else if (ModBlocks.BOX_BLOCKS.containsKey(packet.collectionId)) {
                    // For static collections, get the specific box block
                    boxBlock = ModBlocks.BOX_BLOCKS.get(packet.collectionId).get();
                } else {
                    // For dynamic collections (like world_players), use the default box block as fallback
                    LOGGER.info("Using default box block for dynamic collection: {}", packet.collectionId);
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                }

                if (boxBlock != null) {
                    // Create an ItemStack from the box block
                    ItemStack boxItem = new ItemStack(boxBlock);

                    // Get the collection and select a figure based on token type
                    CollectionRegistry.getCollection(packet.collectionId).ifPresent(collection -> {
                        List<FigureDefinition> figures = collection.getFigures();
                        if (!figures.isEmpty()) {
                            // Select figure based on token type
                            FigureDefinition selectedFigure = selectFigure(figures, packet.tokenType,
                                    discovery, packet.collectionId);

                            // Create unique figure ID for discovery tracking
                            String uniqueFigureId = packet.collectionId + ":" + selectedFigure.getId();

                            // Check if this is a new discovery
                            if (!discovery.isDiscovered(uniqueFigureId)) {
                                // Mark as discovered on the server
                                discovery.discover(uniqueFigureId);

                                // Notify the client of the new discovery
                                UnlockFigurePacket unlockPacket = new UnlockFigurePacket(uniqueFigureId, selectedFigure.getName());
                                BlockPopsModForge.NETWORK_CHANNEL.send(
                                    PacketDistributor.PLAYER.with(() -> player),
                                    unlockPacket
                                );

                                LOGGER.info("Player {} discovered new figure: {} ({})",
                                        player.getName().getString(), selectedFigure.getName(), uniqueFigureId);
                            } else {
                                LOGGER.debug("Player {} received duplicate figure: {} ({})",
                                        player.getName().getString(), selectedFigure.getName(), uniqueFigureId);
                            }

                            // Create NBT data for the box with the selected figure
                            CompoundTag blockEntityTag = new CompoundTag();
                            blockEntityTag.putString("FigureId", selectedFigure.getId());
                            // Store the collection ID so dynamic collections work correctly
                            blockEntityTag.putString("CollectionId", packet.collectionId);

                            // Set the BlockEntityTag on the item
                            boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                            LOGGER.info("Selected figure '{}' ({}) from collection '{}' using {} token",
                                       selectedFigure.getId(), selectedFigure.getName(),
                                       packet.collectionId, packet.tokenType);
                        } else {
                            LOGGER.warn("Collection '{}' has no figures", packet.collectionId);
                        }
                    });

                    // Spawn the item entity at the claw machine position (slightly above)
                    double x = packet.pos.getX() + 0.5;
                    double y = packet.pos.getY() + 1.0; // Spawn above the lower block
                    double z = packet.pos.getZ() + 0.5;

                    ItemEntity itemEntity = new ItemEntity(player.level(), x, y, z, boxItem);
                    // Add a slight upward velocity for a nice drop effect
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);

                    LOGGER.info("Dropped box item for collection '{}' at position {}", packet.collectionId, packet.pos);
                } else {
                    LOGGER.warn("Could not find box block for collection: {}", packet.collectionId);
                }
    }

    /**
     * Select a figure based on the token type.
     * REGULAR: Random figure from collection
     * GUARANTEED: Random undiscovered figure, or random figure if collection is complete
     */
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
