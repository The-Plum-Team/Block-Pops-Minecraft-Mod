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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GetBoxCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetBoxCommand.class);

    // ... register() and suggestion providers are unchanged ...
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
    private static final SuggestionProvider<CommandSourceStack> COLLECTION_SUGGESTIONS = (context, builder) -> {
        Set<String> collectionIds = CollectionRegistry.getCollectionIds();
        return SharedSuggestionProvider.suggest(collectionIds, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> TOKEN_TYPE_SUGGESTIONS = (context, builder) -> {
        return SharedSuggestionProvider.suggest(new String[]{"regular", "guaranteed"}, builder);
    };
    private static TokenType parseTokenType(String tokenTypeStr) {
        if (tokenTypeStr.equalsIgnoreCase("regular")) {
            return TokenType.REGULAR;
        } else if (tokenTypeStr.equalsIgnoreCase("guaranteed")) {
            return TokenType.GUARANTEED;
        }
        return null;
    }

    private static int executeCommand(CommandContext<CommandSourceStack> context, TokenType tokenType) {
        String collectionId = StringArgumentType.getString(context, "collection_id");
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!CollectionRegistry.getCollection(collectionId).isPresent()) {
                source.sendFailure(Component.literal("Collection '" + collectionId + "' does not exist"));
                return 0;
            }
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

    private static void processBoxDrop(ServerPlayer player, String collectionId, TokenType tokenType, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();
            if (!figures.isEmpty()) {
                FigureDefinition selectedFigure = selectFigure(figures, tokenType, discovery, collectionId);

                Block boxBlock = null;
                if (collectionId.equals(PlayerCollectionGenerator.getCollectionId())) {
                    PopBlockColor color = selectedFigure.getFavoriteColor();
                    if (color == null) color = PopBlockColor.ORIGINAL;
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.get(color).get();
                } else if (ModBlocks.BOX_BLOCKS.containsKey(collectionId)) {
                    boxBlock = ModBlocks.BOX_BLOCKS.get(collectionId).get();
                } else {
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.get(PopBlockColor.ORIGINAL).get();
                }

                if (boxBlock != null) {
                    ItemStack boxItem = new ItemStack(boxBlock);
                    String uniqueFigureId = collectionId + ":" + selectedFigure.getId();
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
                    }

                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", selectedFigure.getId());
                    blockEntityTag.putString("CollectionId", collectionId);

                    if (skinSnapshot != null && !skinSnapshot.isEmpty()) {
                        blockEntityTag.putString("SkinSnapshot", skinSnapshot);
                    } else if (selectedFigure.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        String oldSnapshot = discovery.getFigureSkin(uniqueFigureId);
                        if (oldSnapshot != null && !oldSnapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", oldSnapshot);
                        }
                    }

                    boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                    ItemEntity itemEntity = new ItemEntity(player.level(), player.getX(), player.getY() + 1.0, player.getZ(), boxItem);
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);
                }
            }
        });
    }

    // ... selectFigure() is unchanged ...
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