package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.PlayerCollectionHelper;
import com.theplumteam.network.SyncDynamicCollectionsPacket;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.ServerLevels;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Command to set the default color for players in the World Players collection.
 * This affects players who haven't manually chosen a favorite color.
 * Usage: /blockpops setdefaultcolor <color>
 */
public class SetDefaultColorCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("setdefaultcolor")
                .requires(source -> ServerLevels.hasCommandLevel(source, 2)) // Admin permission (op level 2)
                .then(Commands.argument("color", StringArgumentType.word())
                    .suggests(COLOR_SUGGESTIONS)
                    .executes(SetDefaultColorCommand::executeCommand)
                )
            )
        );
    }

    private static final SuggestionProvider<CommandSourceStack> COLOR_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(
            Stream.of(PopBlockColor.values()).map(PopBlockColor::getSerializedName),
            builder
        );

    private static int executeCommand(CommandContext<CommandSourceStack> context) {
        String colorName = StringArgumentType.getString(context, "color");
        CommandSourceStack source = context.getSource();

        try {
            PopBlockColor color = PopBlockColor.valueOf(colorName.toUpperCase());

            // 1. Update Config
            ServerConfig.getInstance().setDefaultPlayerColor(color);
            BlockPopsMod.logDebug("Default player color set to: {}", color.getSerializedName());

            // 2. Regenerate World Players Collection using cross-platform helper
            if (source.getServer() != null) {
                FigureCollection updatedCollection = PlayerCollectionHelper.generate(source.getServer());
                CollectionRegistry.registerDynamicCollection(updatedCollection);

                // 3. Sync to all players using cross-platform networking
                List<FigureCollection> dynamicCollections = new ArrayList<>();
                dynamicCollections.add(updatedCollection);
                SyncDynamicCollectionsPacket.sendToAllPlayers(source.getServer(), dynamicCollections);
                BlockPopsMod.logDebug("Synced updated World Players collection to all players");
            }

            source.sendSuccess(() -> Component.literal("Set default player collection color to: " + color.getSerializedName()), true);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid color name: " + colorName + ". Valid colors: original, black, blue, brown, cyan, gray, green, light_blue, light_gray, lime, magenta, orange, pink, purple, red, yellow"));
            return 0;
        }
    }
}
