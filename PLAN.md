An excellent request! Implementing a skin snapshot system is a great way to make collectibles feel more permanent and unique. Here is the implementation to create a system of instantaneous storage for player skins in the "World Players" collection.

This system will:
1.  Continue to show **live skins** in the Claw Machine UI for figures you haven't obtained yet.
2.  When you obtain a "World Players" figure, it will **instantly snapshot** the player's current skin data on the server.
3.  The obtained Box and Figure will **permanently use that snapshotted skin**, even if the player changes their skin later.
4.  This is achieved by storing the skin's texture properties, leveraging Minecraft's own skin caching and rendering system for maximum efficiency.

Here are the necessary code modifications:

### 1. Update `UnlockFigurePacket` to Carry Skin Data

First, we need to enhance our network packet that tells the client about a new figure. It will now also carry the skin "snapshot" data if the figure is a player.

<update_file file="forge/src/main/java/com/theplumteam/network/UnlockFigurePacket.java">
```java
package com.theplumteam.network;

import com.theplumteam.client.discovery.ClientDiscoveryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Server-to-client packet that notifies the client of a newly discovered figure.
 * Sent when a player receives a new figure from the claw machine for the first time.
 */
public class UnlockFigurePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnlockFigurePacket.class);

    private final String figureId;
    private final String figureName;
    @Nullable
    private final String skinSnapshot; // New: Holds the Base64 texture property

    public UnlockFigurePacket(String figureId, String figureName) {
        this(figureId, figureName, null);
    }

    public UnlockFigurePacket(String figureId, String figureName, @Nullable String skinSnapshot) {
        this.figureId = figureId;
        this.figureName = figureName;
        this.skinSnapshot = skinSnapshot;
    }

    public static void encode(UnlockFigurePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.figureId);
        buffer.writeUtf(packet.figureName);
        // Encode the nullable skin snapshot
        buffer.writeBoolean(packet.skinSnapshot != null);
        if (packet.skinSnapshot != null) {
            buffer.writeUtf(packet.skinSnapshot);
        }
    }

    public static UnlockFigurePacket decode(FriendlyByteBuf buffer) {
        String figureId = buffer.readUtf();
        String figureName = buffer.readUtf();
        String skinSnapshot = null;
        if (buffer.readBoolean()) {
            skinSnapshot = buffer.readUtf();
        }
        return new UnlockFigurePacket(figureId, figureName, skinSnapshot);
    }

    public static void handle(UnlockFigurePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // This runs on the client thread
            LOGGER.info("Unlocked new figure: {} ({})", packet.figureName, packet.figureId);
            ClientDiscoveryManager.unlock(packet.figureId);

            // If a skin snapshot was sent, save it on the client
            if (packet.skinSnapshot != null) {
                ClientDiscoveryManager.saveFigureSkin(packet.figureId, packet.skinSnapshot);
                LOGGER.info("Saved skin snapshot for unlocked figure: {}", packet.figureId);
            }

            // TODO: Optional - Play sound effect and/or show toast notification
            // Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            //     SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F
            // ));
        });
        context.setPacketHandled(true);
    }

    public String getFigureId() {
        return figureId;
    }

    public String getFigureName() {
        return figureName;
    }
}
```
</update_file>

### 2. Add Skin Saving to `ClientDiscoveryManager`

The client needs a way to store the received skin snapshot in its local cache.

<update_file file="forge/src/main/java/com/theplumteam/client/discovery/ClientDiscoveryManager.java">
```java
package com.theplumteam.client.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side manager for tracking which figures the player has discovered.
 * This is a cache of the server-side capability data, synced via network packets.
 */
public class ClientDiscoveryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientDiscoveryManager.class);
    private static final Set<String> discoveredFigures = new HashSet<>();
    private static final Map<String, String> figureSkins = new HashMap<>();

    /**
     * Replace the entire discovered set with new data from the server.
     * Called when the SyncDiscoveryDataPacket is received on login.
     *
     * @param figures The complete set of discovered figure IDs
     * @param skins Map of figure IDs to skin URLs
     */
    public static void setData(Set<String> figures, Map<String, String> skins) {
        discoveredFigures.clear();
        discoveredFigures.addAll(figures);
        figureSkins.clear();
        figureSkins.putAll(skins);
        LOGGER.debug("Discovery data synced: {} figures, {} skins", discoveredFigures.size(), figureSkins.size());
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public static void setData(Set<String> figures) {
        setData(figures, Collections.emptyMap());
    }

    /**
     * Add a newly discovered figure to the local cache.
     * Called when the UnlockFigurePacket is received.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     */
    public static void unlock(String figureId) {
        if (discoveredFigures.add(figureId)) {
            LOGGER.info("Figure unlocked: {}", figureId);
        }
    }

    /**
     * Check if a figure has been discovered by the player.
     * This is the main method used by the UI to determine what to display.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return true if the figure has been discovered, false otherwise
     */
    public static boolean isDiscovered(String figureId) {
        return discoveredFigures.contains(figureId);
    }

    /**
     * Get an unmodifiable view of all discovered figures.
     * Useful for debugging or displaying collection statistics.
     *
     * @return An unmodifiable set of discovered figure IDs
     */
    public static Set<String> getAllDiscovered() {
        return Collections.unmodifiableSet(discoveredFigures);
    }

    /**
     * Clear all discovery data.
     * Should be called when the player logs out to prevent data leakage between sessions.
     */
    public static void clear() {
        discoveredFigures.clear();
        figureSkins.clear();
        LOGGER.debug("Discovery data cleared");
    }

    /**
     * Save a skin URL for a figure.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @param skinUrl The skin URL to save
     */
    public static void saveFigureSkin(String figureId, String skinUrl) {
        figureSkins.put(figureId, skinUrl);
    }

    /**
     * Get the saved skin URL for a figure.
     *
     * @param figureId The unique figure identifier in format "collectionId:figureId"
     * @return The saved skin URL, or null if not saved
     */
    @Nullable
    public static String getFigureSkin(String figureId) {
        return figureSkins.get(figureId);
    }
}
```
</update_file>

### 3. Implement Server-Side Skin Snapshotting Logic

Now for the core server logic. When a box is dropped, we'll capture the skin information and save it to the player's capability data, then send it to the client.

<update_file file="forge/src/main/java/com/theplumteam/network/DropBoxPacket.java">
```java
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
                // Get the collection and select a figure based on token type FIRST
                // This is necessary to determine the box color for world_players
                CollectionRegistry.getCollection(packet.collectionId).ifPresent(collection -> {
                    List<FigureDefinition> figures = collection.getFigures();
                    if (!figures.isEmpty()) {
                        // Select figure based on token type
                        FigureDefinition selectedFigure = selectFigure(figures, packet.tokenType,
                                discovery, packet.collectionId);

                        // Now determine the box block to use
                        Block boxBlock = null;

                        if (packet.collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
                            // It's a world_players figure, use their favorite color
                            PopBlockColor color = selectedFigure.getFavoriteColor();
                            if (color == null) color = PopBlockColor.ORIGINAL; // Safety default

                            boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.get(color).get();
                            LOGGER.info("Using {} color box for world_players figure", color.getSerializedName());
                        } else if (packet.collectionId.equals("default")) {
                            // For default collection, use the first color variant (white)
                            boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                                .findFirst()
                                .map(supplier -> supplier.get())
                                .orElse(null);
                        } else if (ModBlocks.BOX_BLOCKS.containsKey(packet.collectionId)) {
                            // For static collections, get the specific box block
                            boxBlock = ModBlocks.BOX_BLOCKS.get(packet.collectionId).get();
                        } else {
                            // For other dynamic collections, use the default box block as fallback
                            LOGGER.info("Using default box block for dynamic collection: {}", packet.collectionId);
                            boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                                .findFirst()
                                .map(supplier -> supplier.get())
                                .orElse(null);
                        }

                        if (boxBlock != null) {
                            // Create an ItemStack from the box block
                            ItemStack boxItem = new ItemStack(boxBlock);

                            // Create unique figure ID for discovery tracking
                            String uniqueFigureId = packet.collectionId + ":" + selectedFigure.getId();

                            // Check if this is a new discovery
                            if (!discovery.isDiscovered(uniqueFigureId)) {
                                // Mark as discovered on the server
                                discovery.discover(uniqueFigureId);

                                String skinSnapshot = null;
                                // If this is a player figure, snapshot their skin
                                if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER && selectedFigure.getPlayerUUID() != null) {
                                    GameProfile profile = player.getServer().getProfileCache().get(selectedFigure.getPlayerUUID()).orElse(null);
                                    if (profile != null && !profile.getProperties().get("textures").isEmpty()) {
                                        // The snapshot is the Base64 value of the texture property
                                        skinSnapshot = profile.getProperties().get("textures").iterator().next().getValue();
                                        discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                                        LOGGER.debug("Saved skin snapshot for player figure {}", uniqueFigureId);
                                    } else {
                                        LOGGER.warn("Could not find GameProfile or texture property for player {}", selectedFigure.getPlayerUUID());
                                    }
                                }

                                // Notify the client of the new discovery, including the skin snapshot if available
                                UnlockFigurePacket unlockPacket = new UnlockFigurePacket(uniqueFigureId, selectedFigure.getName(), skinSnapshot);
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
                    } else {
                        LOGGER.warn("Collection '{}' has no figures", packet.collectionId);
                    }
                });
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
```
</update_file>

<update_file file="forge/src/main/java/com/theplumteam/command/GetBoxCommand.java">
```java
package com.theplumteam.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.capability.IPlayerDiscovery;
import com.theplumteam.capability.PlayerDiscoveryProvider;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.PlayerCollectionGenerator;
import com.theplumteam.forge.BlockPopsModForge;
import com.theplumteam.network.TokenType;
import com.theplumteam.network.UnlockFigurePacket;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Command to obtain a collection box without requiring a token.
 * Usage: /blockpops getbox <collection_id> [token_type]
 *
 * - collection_id: The ID of the collection (e.g., "jojos", "world_players")
 * - token_type: Optional - "regular" or "guaranteed" (defaults to "regular")
 *   - regular: Random figure from collection
 *   - guaranteed: Undiscovered figure (if available), or random if collection complete
 */
public class GetBoxCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetBoxCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("getbox")
                        .requires(source -> source.hasPermission(2)) // Requires operator permission
                        .then(Commands.argument("collection_id", StringArgumentType.string())
                                .suggests(COLLECTION_SUGGESTIONS)
                                .executes(context -> {
                                    // Default to REGULAR token type
                                    return executeCommand(context, TokenType.REGULAR);
                                })
                                .then(Commands.argument("token_type", StringArgumentType.string())
                                        .suggests(TOKEN_TYPE_SUGGESTIONS)
                                        .executes(context -> {
                                            String tokenTypeStr = StringArgumentType.getString(context, "token_type");
                                            TokenType tokenType = parseTokenType(tokenTypeStr);
                                            if (tokenType == null) {
                                                context.getSource().sendFailure(Component.literal("Invalid token type. Use 'regular' or 'guaranteed'"));
                                                return 0;
                                            }
                                            return executeCommand(context, tokenType);
                                        })
                                )
                        )
                )
        );
    }

    /**
     * Suggestions for collection IDs - dynamically generated from CollectionRegistry
     */
    private static final SuggestionProvider<CommandSourceStack> COLLECTION_SUGGESTIONS = (context, builder) -> {
        Set<String> collectionIds = CollectionRegistry.getCollectionIds();
        return SharedSuggestionProvider.suggest(collectionIds, builder);
    };

    /**
     * Suggestions for token types
     */
    private static final SuggestionProvider<CommandSourceStack> TOKEN_TYPE_SUGGESTIONS = (context, builder) -> {
        return SharedSuggestionProvider.suggest(new String[]{"regular", "guaranteed"}, builder);
    };

    /**
     * Parse token type string to TokenType enum
     */
    private static TokenType parseTokenType(String tokenTypeStr) {
        if (tokenTypeStr.equalsIgnoreCase("regular")) {
            return TokenType.REGULAR;
        } else if (tokenTypeStr.equalsIgnoreCase("guaranteed")) {
            return TokenType.GUARANTEED;
        }
        return null;
    }

    /**
     * Execute the command
     */
    private static int executeCommand(CommandContext<CommandSourceStack> context, TokenType tokenType) {
        String collectionId = StringArgumentType.getString(context, "collection_id");
        CommandSourceStack source = context.getSource();

        // Get the player executing the command
        try {
            ServerPlayer player = source.getPlayerOrException();

            // Verify the collection exists
            if (!CollectionRegistry.getCollection(collectionId).isPresent()) {
                source.sendFailure(Component.literal("Collection '" + collectionId + "' does not exist"));
                return 0;
            }

            // Get player discovery capability
            player.getCapability(PlayerDiscoveryProvider.PLAYER_DISCOVERY).ifPresent(discovery -> {
                processBoxDrop(player, collectionId, tokenType, discovery);
            });

            source.sendSuccess(() -> Component.literal("Gave box from collection '" + collectionId + "' using " + tokenType.name().toLowerCase() + " token logic"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            LOGGER.error("Error executing getbox command", e);
            return 0;
        }
    }

    /**
     * Process the box drop - replicates logic from DropBoxPacket without token verification
     */
    private static void processBoxDrop(ServerPlayer player, String collectionId, TokenType tokenType, IPlayerDiscovery discovery) {
        // Get the collection and select a figure based on token type FIRST
        // This is necessary to determine the box color for world_players
        CollectionRegistry.getCollection(collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();
            if (!figures.isEmpty()) {
                // Select figure based on token type
                FigureDefinition selectedFigure = selectFigure(figures, tokenType,
                        discovery, collectionId);

                // Now determine the box block to use
                Block boxBlock = null;

                if (collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
                    // It's a world_players figure, use their favorite color
                    PopBlockColor color = selectedFigure.getFavoriteColor();
                    if (color == null) color = PopBlockColor.ORIGINAL; // Safety default

                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.get(color).get();
                    LOGGER.info("Using {} color box for world_players figure", color.getSerializedName());
                } else if (collectionId.equals("default")) {
                    // For default collection, use the first color variant (white)
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                } else if (ModBlocks.BOX_BLOCKS.containsKey(collectionId)) {
                    // For static collections, get the specific box block
                    boxBlock = ModBlocks.BOX_BLOCKS.get(collectionId).get();
                } else {
                    // For other dynamic collections, use the default box block as fallback
                    LOGGER.info("Using default box block for dynamic collection: {}", collectionId);
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                }

                if (boxBlock != null) {
                    // Create an ItemStack from the box block
                    ItemStack boxItem = new ItemStack(boxBlock);

                    // Create unique figure ID for discovery tracking
                    String uniqueFigureId = collectionId + ":" + selectedFigure.getId();

                    // Check if this is a new discovery
                    if (!discovery.isDiscovered(uniqueFigureId)) {
                        // Mark as discovered on the server
                        discovery.discover(uniqueFigureId);

                        String skinSnapshot = null;
                        // If this is a player figure, snapshot their skin
                        if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER && selectedFigure.getPlayerUUID() != null) {
                            GameProfile profile = player.getServer().getProfileCache().get(selectedFigure.getPlayerUUID()).orElse(null);
                            if (profile != null && !profile.getProperties().get("textures").isEmpty()) {
                                // The snapshot is the Base64 value of the texture property
                                skinSnapshot = profile.getProperties().get("textures").iterator().next().getValue();
                                discovery.saveFigureSkin(uniqueFigureId, skinSnapshot);
                                LOGGER.debug("Saved skin snapshot for player figure {}", uniqueFigureId);
                            } else {
                                LOGGER.warn("Could not find GameProfile or texture property for player {}", selectedFigure.getPlayerUUID());
                            }
                        }

                        // Notify the client of the new discovery, including skin snapshot if available
                        UnlockFigurePacket unlockPacket = new UnlockFigurePacket(uniqueFigureId, selectedFigure.getName(), skinSnapshot);
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
                    blockEntityTag.putString("CollectionId", collectionId);

                    // Set the BlockEntityTag on the item
                    boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                    LOGGER.info("Selected figure '{}' ({}) from collection '{}' using {} token logic",
                               selectedFigure.getId(), selectedFigure.getName(),
                               collectionId, tokenType);

                    // Spawn the item entity at the player's position
                    BlockPos playerPos = player.blockPosition();
                    double x = playerPos.getX() + 0.5;
                    double y = playerPos.getY() + 1.0; // Spawn above the player
                    double z = playerPos.getZ() + 0.5;

                    ItemEntity itemEntity = new ItemEntity(player.level(), x, y, z, boxItem);
                    // Add a slight upward velocity for a nice drop effect
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);

                    LOGGER.info("Dropped box item for collection '{}' at player position {}", collectionId, playerPos);
                } else {
                    LOGGER.warn("Could not find box block for collection: {}", collectionId);
                }
            } else {
                LOGGER.warn("Collection '{}' has no figures", collectionId);
            }
        });
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
                LOGGER.info("Guaranteed token logic: Selected undiscovered figure '{}' from {} options",
                        selected.getId(), undiscoveredFigures.size());
                return selected;
            } else {
                // Collection is complete, give a random duplicate as fallback
                LOGGER.info("Guaranteed token logic: Collection complete, giving random duplicate");
                return figures.get(random.nextInt(figures.size()));
            }
        } else {
            // REGULAR token: just pick any random figure
            return figures.get(random.nextInt(figures.size()));
        }
    }
}
```
</update_file>

### 4. Update Rendering Logic to Use Snapshots

Finally, we update the client-side figure models to check for and use a saved skin snapshot. If no snapshot exists (like in the claw machine UI), it will fall back to rendering the player's live, dynamic skin.

<update_file file="forge/src/main/java/com/theplumteam/client/model/FigureModel.java">
```java
package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeoModel for rendering figures dynamically based on collection data
 */
public class FigureModel extends GeoModel<BoxBlockEntity> {
    // Fallback texture when figure is not available (uses default Steve skin)
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");

    // Cache for GameProfiles created from skin snapshots to improve performance
    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();

    @Override
    public ResourceLocation getModelResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback texture instead of null to prevent crashes
            return FALLBACK_TEXTURE;
        }

        // Check for alternative skins
        int skinIndex = animatable.getAlternativeSkinIndex();

        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                // Return the alternative texture
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }

        // Check if this is a player figure (dynamic skin)
        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
            String skinSnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);

            // If a snapshot exists, use it for permanent skin
            if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueFigureId, id -> {
                    GameProfile newProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                    newProfile.getProperties().put("textures", new Property("textures", skinSnapshot));
                    return newProfile;
                });
                return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
            }

            // No snapshot, so we're showing the live skin (e.g., in claw machine UI)
            GameProfile gameProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            ResourceLocation playerSkin = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
            return playerSkin != null ? playerSkin : FALLBACK_TEXTURE;
        }

        // Static figure - use the predefined texture path
        ResourceLocation texturePath = figure.getTexturePath();
        return texturePath != null ? texturePath : FALLBACK_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(BoxBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            return null;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(BoxBlockEntity animatable, ResourceLocation texture) {
        // Use entityCutoutNoCull for proper rendering without culling issues
        ResourceLocation textureToUse = getTextureResource(animatable);
        // Safety check: use fallback if texture is somehow null
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}
```
</update_file>

<update_file file="forge/src/main/java/com/theplumteam/client/model/FigureBlockModel.java">
```java
package com.theplumteam.client.model;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeoModel for rendering figures in standalone figure blocks
 */
public class FigureBlockModel extends GeoModel<FigureBlockEntity> {
    // Fallback resources when figure is not available (prevents crashes during NBT sync)
    private static final ResourceLocation FALLBACK_MODEL = new ResourceLocation(BlockPopsMod.MOD_ID, "geo/block/box_block.geo.json");
    private static final ResourceLocation FALLBACK_TEXTURE = new ResourceLocation("minecraft", "textures/entity/steve.png");
    private static final ResourceLocation FALLBACK_ANIMATION = new ResourceLocation(BlockPopsMod.MOD_ID, "animations/block/box_block.animation.json");

    // Cache for GameProfiles created from skin snapshots to improve performance
    private static final Map<String, GameProfile> snapshotProfileCache = new ConcurrentHashMap<>();

    @Override
    public ResourceLocation getModelResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback to prevent crashes when NBT data hasn't synced yet
            return FALLBACK_MODEL;
        }
        return figure.getModelPath();
    }

    @Override
    public ResourceLocation getTextureResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback texture instead of null to prevent crashes
            return FALLBACK_TEXTURE;
        }

        // Check for alternative skins
        int skinIndex = animatable.getAlternativeSkinIndex();

        if (skinIndex > 0 && figure.hasAlternatives()) {
            int altListIndex = skinIndex - 1;
            if (altListIndex < figure.getAlternatives().size()) {
                // Return the alternative texture
                return figure.getAlternatives().get(altListIndex).texture();
            }
        }

        // Check if this is a player figure (dynamic skin)
        if (figure.getType() == FigureType.PLAYER && figure.getPlayerUUID() != null) {
            String uniqueFigureId = animatable.getCollectionId() + ":" + animatable.getFigureId();
            String skinSnapshot = ClientDiscoveryManager.getFigureSkin(uniqueFigureId);

            // If a snapshot exists, use it for permanent skin
            if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                GameProfile profile = snapshotProfileCache.computeIfAbsent(uniqueFigureId, id -> {
                    GameProfile newProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
                    newProfile.getProperties().put("textures", new Property("textures", skinSnapshot));
                    return newProfile;
                });
                return Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
            }

            // No snapshot, so we're showing the live skin
            GameProfile gameProfile = new GameProfile(figure.getPlayerUUID(), figure.getName());
            ResourceLocation playerSkin = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(gameProfile);
            return playerSkin != null ? playerSkin : FALLBACK_TEXTURE;
        }

        // Static figure - use the predefined texture path
        ResourceLocation texturePath = figure.getTexturePath();
        return texturePath != null ? texturePath : FALLBACK_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FigureBlockEntity animatable) {
        FigureDefinition figure = animatable.getFigureDefinition();
        if (figure == null) {
            // Return fallback to prevent crashes when NBT data hasn't synced yet
            return FALLBACK_ANIMATION;
        }
        return figure.getAnimationPath();
    }

    @Override
    public RenderType getRenderType(FigureBlockEntity animatable, ResourceLocation texture) {
        // Use entityCutoutNoCull for proper rendering without culling issues
        ResourceLocation textureToUse = getTextureResource(animatable);
        // Safety check: use fallback if texture is somehow null
        if (textureToUse == null) {
            textureToUse = FALLBACK_TEXTURE;
        }
        return RenderType.entityCutoutNoCull(textureToUse);
    }
}
```
</update_file>

With these changes, your "World Players" figures will now have their skins permanently snapshotted upon acquisition, making each one a true collectible that captures a moment in time.