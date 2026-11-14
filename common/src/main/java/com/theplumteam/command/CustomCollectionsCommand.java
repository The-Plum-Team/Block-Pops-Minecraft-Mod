package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.server.ClientCapabilities;
import com.theplumteam.server.CustomCollectionSettings;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.server.PlayerCollectionEngine;
import com.theplumteam.server.PlayerCollections;
import com.theplumteam.server.PlayerMessages;
import com.theplumteam.server.ServerProfiles;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enables, disables and lists the Custom and the player collections of this world.
 * Usage: /blockpops customcollections add &lt;code&gt; | remove &lt;id or player code&gt; | list
 * | update &lt;player code&gt; | follow &lt;player code&gt; on|off | refresh
 *
 * <p>It is for operators at level 2 and for the host of their own world.
 */
public class CustomCollectionsCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("customcollections")
                .requires(ServerProfiles::managesCodes)
                .then(Commands.literal("add")
                    .then(Commands.argument("code", StringArgumentType.word())
                        .executes(CustomCollectionsCommand::add)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            List<String> names = new ArrayList<>(
                                    CustomCollectionSettings.get(context.getSource().getServer()).enabled());
                            names.addAll(playerCodes());
                            return SharedSuggestionProvider.suggest(names, builder);
                        })
                        .executes(CustomCollectionsCommand::remove)))
                .then(Commands.literal("list")
                    .executes(CustomCollectionsCommand::list))
                .then(Commands.literal("update")
                    .then(Commands.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(playerCodes(), builder))
                        .executes(CustomCollectionsCommand::update)))
                .then(Commands.literal("follow")
                    .then(Commands.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(playerCodes(), builder))
                        .then(Commands.literal("on").executes(context -> follow(context, true)))
                        .then(Commands.literal("off").executes(context -> follow(context, false)))))
                .then(Commands.literal("refresh")
                    .executes(CustomCollectionsCommand::refresh))
            )
        );
    }

    private static List<String> playerCodes() {
        return PlayerCollections.lines().stream().map(line -> line.entry().code()).toList();
    }

    private static int add(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("blockpops.custom_collections.looking_up"), false);
        CustomCollections.enableByCode(source.getServer(), StringArgumentType.getString(context, "code"),
                status -> reply(source, status));
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CustomCollections.disable(source.getServer(),
                StringArgumentType.getString(context, "id").toLowerCase(Locale.ROOT), status -> reply(source, status));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CustomCollections.Status> statuses = CustomCollections.list(source.getServer());
        List<PlayerCollectionEngine.Line> lines = PlayerCollections.lines();
        if (statuses.isEmpty() && lines.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("blockpops.custom_collections.none"), false);
        }
        for (CustomCollections.Status status : statuses) {
            source.sendSuccess(() -> Component.translatable(status.result() == CustomCollections.Result.ADDED
                    ? "blockpops.custom_collections.loaded" : "blockpops.custom_collections.not_loaded",
                    status.name()), false);
        }
        for (PlayerCollectionEngine.Line line : lines) {
            String code = line.entry().code();
            int rev = line.entry().rev();
            Component text;
            if (line.condition() != PlayerCollectionEngine.Condition.LOADED) {
                text = PlayerMessages.LIST_NOT_SHOWN.with(line.name(), code);
            } else if (line.newest() > rev) {
                text = PlayerMessages.LIST_NEWER.with(line.name(), code, rev, line.newest());
            } else {
                text = PlayerMessages.LIST_LOADED.with(line.name(), code, rev);
            }
            source.sendSuccess(() -> text, false);
        }
        return statuses.size() + lines.size();
    }

    private static int update(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String code = StringArgumentType.getString(context, "code").toLowerCase(Locale.ROOT);
        source.sendSuccess(() -> Component.translatable("blockpops.custom_collections.looking_up"), false);
        PlayerCollections.update(source.getServer(), code, result -> reply(source, result));
        return 1;
    }

    private static int follow(CommandContext<CommandSourceStack> context, boolean on) {
        CommandSourceStack source = context.getSource();
        String code = StringArgumentType.getString(context, "code").toLowerCase(Locale.ROOT);
        PlayerCollections.follow(source.getServer(), code, on, result -> reply(source, result));
        return 1;
    }

    private static int refresh(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlayerCollections.refresh(source.getServer(), ran -> {
            if (ran) {
                // The official collections are read again as well, so a changed one is not left stale
                CustomCollections.refreshNow(source.getServer());
                source.sendSuccess(() -> PlayerMessages.REFRESHED.with(), true);
            } else {
                source.sendFailure(PlayerMessages.REFRESH_WAIT.with());
            }
        });
        return 1;
    }

    private static void reply(CommandSourceStack source, CustomCollections.Status status) {
        // A client from before player collections has no words for their results
        ServerPlayer player = source.getPlayer();
        CustomCollections.Result result = player == null || ClientCapabilities.playerCollections(player)
                ? status.result() : status.result().forOlderClient();
        Component message = Component.translatable("blockpops.custom_collections."
                + result.name().toLowerCase(Locale.ROOT), status.name());
        boolean success = result == CustomCollections.Result.ADDED
                || result == CustomCollections.Result.PLAYER_ADDED
                || result == CustomCollections.Result.REMOVED;
        if (success) {
            source.sendSuccess(() -> message, true);
        } else {
            source.sendFailure(message);
        }
    }

    // What moving a pin or following came to, in the words of player collections
    private static void reply(CommandSourceStack source, PlayerCollectionEngine.Result result) {
        switch (result.outcome()) {
            case UPDATED -> source.sendSuccess(() -> PlayerMessages.UPDATED.with(result.name(), result.rev()), true);
            case UP_TO_DATE -> source.sendSuccess(() -> PlayerMessages.UP_TO_DATE.with(result.name(), result.rev()),
                    false);
            case FOLLOWING -> source.sendSuccess(() -> PlayerMessages.FOLLOWING.with(result.name()), true);
            case PINNED -> source.sendSuccess(() -> PlayerMessages.PINNED.with(result.name(), result.rev()), true);
            case NOT_ENABLED -> source.sendFailure(
                    Component.translatable("blockpops.custom_collections.not_enabled", result.name()));
            case UNAVAILABLE -> source.sendFailure(Component.translatableWithFallback(
                    "blockpops.custom_collections.unavailable", "That collection is no longer available."));
            case OFF -> source.sendFailure(Component.translatableWithFallback(
                    "blockpops.custom_collections.player_off", "Player collections are turned off right now."));
            case UNKNOWN_CODE -> source.sendFailure(
                    Component.translatable("blockpops.custom_collections.unknown_code"));
            default -> source.sendFailure(Component.translatable("blockpops.custom_collections.failed"));
        }
    }
}
