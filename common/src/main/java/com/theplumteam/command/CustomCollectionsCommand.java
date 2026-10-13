package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.theplumteam.server.CustomCollectionSettings;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.util.ServerLevels;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Enables, disables and lists the Custom collections of this world.
 * Usage: /blockpops customcollections add &lt;code&gt; | remove &lt;id&gt; | list
 */
public class CustomCollectionsCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("customcollections")
                .requires(source -> ServerLevels.hasCommandLevel(source, 2))
                .then(Commands.literal("add")
                    .then(Commands.argument("code", StringArgumentType.word())
                        .executes(CustomCollectionsCommand::add)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                CustomCollectionSettings.get(context.getSource().getServer()).enabled(), builder))
                        .executes(CustomCollectionsCommand::remove)))
                .then(Commands.literal("list")
                    .executes(CustomCollectionsCommand::list))
            )
        );
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
        CustomCollections.disable(source.getServer(), StringArgumentType.getString(context, "id"),
                status -> reply(source, status));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CustomCollections.Status> statuses = CustomCollections.list(source.getServer());
        if (statuses.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("blockpops.custom_collections.none"), false);
        }
        for (CustomCollections.Status status : statuses) {
            source.sendSuccess(() -> Component.translatable(status.result() == CustomCollections.Result.ADDED
                    ? "blockpops.custom_collections.loaded" : "blockpops.custom_collections.not_loaded",
                    status.name()), false);
        }
        return statuses.size();
    }

    private static void reply(CommandSourceStack source, CustomCollections.Status status) {
        Component message = Component.translatable("blockpops.custom_collections."
                + status.result().name().toLowerCase(Locale.ROOT), status.name());
        boolean success = status.result() == CustomCollections.Result.ADDED
                || status.result() == CustomCollections.Result.REMOVED;
        if (success) {
            source.sendSuccess(() -> message, true);
        } else {
            source.sendFailure(message);
        }
    }
}
