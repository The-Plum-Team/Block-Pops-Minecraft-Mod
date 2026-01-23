package com.theplumteam.command;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.TokenType;
import com.theplumteam.network.UnlockFigurePacket;
import com.theplumteam.registry.ModItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
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
 * Admin command to get a box from a specific collection.
 * Usage: /blockpops getbox <collection_id> [token_type]
 */
public class GetBoxCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetBoxCommand.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
                .then(Commands.literal("getbox")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("collection_id", StringArgumentType.string())
                                .suggests(COLLECTION_SUGGESTIONS)
                                .executes(context -> executeCommand(context, TokenType.REGULAR))
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
        return SharedSuggestionProvider.suggest(collectionIds.stream().filter(id -> !id.equals("default")), builder);
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

            // Use cross-platform PlayerDataManager
            IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
            processBoxDrop(player, collectionId, tokenType, discovery);
            PlayerDataManager.saveDiscovery(player);

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
            ProfileResult result = player.getServer().getSessionService().fetchProfile(figure.getPlayerUUID(), true);
            return result != null ? result.profile() : null;
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
            // Quick Skin not installed or error
        }
        return null;
    }

    private static void processBoxDrop(ServerPlayer player, String collectionId, TokenType tokenType, IPlayerDiscovery discovery) {
        CollectionRegistry.getCollection(collectionId).ifPresent(collection -> {
            List<FigureDefinition> figures = collection.getFigures();
            if (!figures.isEmpty()) {
                FigureDefinition selectedFigure = selectFigure(figures, tokenType, discovery, collectionId);

                ItemStack boxItem = null;
                if (collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
                    PopBlockColor color = selectedFigure.getFavoriteColor();
                    if (color == null) color = PopBlockColor.ORIGINAL;
                    boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
                } else if (ModItems.BOX_BLOCK_ITEMS.containsKey(collectionId)) {
                    boxItem = new ItemStack(ModItems.BOX_BLOCK_ITEMS.get(collectionId).get());
                } else {
                    boxItem = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(PopBlockColor.ORIGINAL).get());
                }

                if (boxItem != null) {
                    String uniqueFigureId = collectionId + ":" + selectedFigure.getId();
                    String skinSnapshot = null;
                    String quickSkinSnapshot = null;

                    if (selectedFigure.getType() == FigureType.PLAYER) {
                        GameProfile freshProfile = getFreshGameProfile(player, selectedFigure);
                        if (freshProfile != null && !freshProfile.getProperties().get("textures").isEmpty()) {
                            skinSnapshot = freshProfile.getProperties().get("textures").iterator().next().value();
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
                    }

                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", selectedFigure.getId());
                    blockEntityTag.putString("CollectionId", collectionId);

                    if (collectionId.equals(PlayerCollectionHelper.WORLD_PLAYERS_COLLECTION_ID)) {
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

                    // Required in 1.21+ - block entity type ID must be present for serialization
                    blockEntityTag.putString("id", "blockpops:box_block");
                    boxItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));

                    ItemEntity itemEntity = new ItemEntity(player.level(), player.getX(), player.getY() + 1.0, player.getZ(), boxItem);
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);
                }
            }
        });
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
                return undiscoveredFigures.get(random.nextInt(undiscoveredFigures.size()));
            } else {
                return figures.get(random.nextInt(figures.size()));
            }
        } else {
            return figures.get(random.nextInt(figures.size()));
        }
    }
}
