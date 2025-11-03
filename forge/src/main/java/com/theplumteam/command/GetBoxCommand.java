package com.theplumteam.command;

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
