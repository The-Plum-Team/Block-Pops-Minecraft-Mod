package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.server.worldcollections.WorldCollectionImporter;
import com.theplumteam.server.worldcollections.WorldCollectionStore;
import com.theplumteam.server.worldcollections.WorldCollections;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Manages the World collections of this world without the editor. A collection created
 * here is published at once; publish makes a draft from the editor published.
 * Usage: /blockpops worldcollections list | quota | gc | create &lt;slug&gt; &lt;name&gt;
 * | rename &lt;id&gt; &lt;name&gt; | author &lt;id&gt; &lt;author&gt; | color &lt;id&gt; &lt;r&gt; &lt;g&gt; &lt;b&gt;
 * | boxcolor &lt;id&gt; &lt;color|none&gt; | archive &lt;id&gt; | restore &lt;id&gt; | publish &lt;id&gt;
 * | figure retire|restore &lt;id&gt; &lt;figure&gt; | importpack &lt;slug&gt; "&lt;namespace&gt;:&lt;path&gt;"
 * | purge &lt;id&gt; &lt;id-again&gt;
 */
public class WorldCollectionsCommand {
    private static final String KEY = "blockpops.world_collections.";
    private static final SuggestionProvider<CommandSourceStack> IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(WorldCollections.records().stream().map(WorldCollectionRecord::id), builder);
    private static final SuggestionProvider<CommandSourceStack> FIGURES = (context, builder) -> {
        WorldCollectionRecord record = WorldCollections.record(StringArgumentType.getString(context, "id"));
        return SharedSuggestionProvider.suggest(record == null ? List.<String>of()
                : record.figures().stream().map(WorldCollectionRecord.Figure::id).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> BOX_COLORS = (context, builder) ->
            SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(Arrays.stream(PopBlockColor.values())
                    .map(PopBlockColor::getSerializedName), java.util.stream.Stream.of("none")), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blockpops")
            .then(Commands.literal("worldcollections")
                .requires(source -> ServerLevels.hasCommandLevel(source, 2))
                .then(Commands.literal("list")
                    .executes(WorldCollectionsCommand::list))
                .then(Commands.literal("quota")
                    .executes(WorldCollectionsCommand::quota))
                .then(Commands.literal("gc")
                    .executes(WorldCollectionsCommand::collectGarbage))
                .then(Commands.literal("create")
                    .then(Commands.argument("slug", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(WorldCollectionsCommand::create))))
                .then(Commands.literal("rename")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> setCollection(context, record -> settings(record,
                                    StringArgumentType.getString(context, "name"), record.author()))))))
                .then(Commands.literal("author")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .then(Commands.argument("author", StringArgumentType.greedyString())
                            .executes(context -> setCollection(context, record -> settings(record,
                                    record.name(), StringArgumentType.getString(context, "author")))))))
                .then(Commands.literal("color")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .then(Commands.argument("r", IntegerArgumentType.integer(0, 255))
                            .then(Commands.argument("g", IntegerArgumentType.integer(0, 255))
                                .then(Commands.argument("b", IntegerArgumentType.integer(0, 255))
                                    .executes(context -> setCollection(context, record -> new WorldCollectionEdit.SetCollection(
                                            record.id(), record.name(), record.author(),
                                            IntegerArgumentType.getInteger(context, "r"),
                                            IntegerArgumentType.getInteger(context, "g"),
                                            IntegerArgumentType.getInteger(context, "b"),
                                            boxMode(record), box(record), logo(record)))))))))
                .then(Commands.literal("boxcolor")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .then(Commands.argument("color", StringArgumentType.word()).suggests(BOX_COLORS)
                            .executes(WorldCollectionsCommand::boxColor))))
                .then(Commands.literal("archive")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .executes(context -> setArchived(context, true))))
                .then(Commands.literal("restore")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .executes(context -> setArchived(context, false))))
                .then(Commands.literal("publish")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .executes(WorldCollectionsCommand::publish)))
                .then(Commands.literal("figure")
                    .then(Commands.literal("retire")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                            .then(Commands.argument("figure", StringArgumentType.word()).suggests(FIGURES)
                                .executes(context -> setFigureEnabled(context, false)))))
                    .then(Commands.literal("restore")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                            .then(Commands.argument("figure", StringArgumentType.word()).suggests(FIGURES)
                                .executes(context -> setFigureEnabled(context, true))))))
                .then(Commands.literal("importpack")
                    .then(Commands.argument("slug", StringArgumentType.word())
                        .then(Commands.argument("source", StringArgumentType.string())
                            .executes(WorldCollectionsCommand::importPack))))
                .then(Commands.literal("purge")
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .then(Commands.argument("again", StringArgumentType.word())
                            .executes(WorldCollectionsCommand::purge))))
            )
        );
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<WorldCollectionRecord> records = WorldCollections.records();
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "none"), false);
        }
        for (WorldCollectionRecord record : records) {
            long enabled = record.figures().stream().filter(WorldCollectionRecord.Figure::enabled).count();
            String key = record.archived() ? "list_entry_archived"
                    : record.draft() ? "list_entry_draft"
                    : WorldCollections.hasMissingFiles(record) ? "list_entry_missing" : "list_entry";
            source.sendSuccess(() -> Component.translatable(KEY + key, record.name(), record.id(),
                    Long.toString(enabled), Integer.toString(record.figures().size())), false);
        }
        if (WorldCollections.isDegraded()) {
            source.sendFailure(Component.translatable(KEY + "degraded"));
        }
        return records.size();
    }

    private static int quota(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        WorldCollectionStore.State state = WorldCollections.state();
        source.sendSuccess(() -> Component.translatable(KEY + "usage",
                kib(state.storedBytes()), kib(WorldContentLimits.MAX_STORED_BYTES),
                Integer.toString(state.blobs().size()), Integer.toString(WorldContentLimits.MAX_BLOBS),
                Integer.toString(state.collections().size()), Integer.toString(WorldContentLimits.MAX_COLLECTIONS)), false);
        if (WorldCollections.isDegraded()) {
            source.sendFailure(Component.translatable(KEY + "degraded"));
        }
        return 1;
    }

    private static int collectGarbage(CommandContext<CommandSourceStack> context) {
        WorldContentStatus status = WorldCollections.collectGarbage();
        return reply(context.getSource(), status, "collected");
    }

    private static int create(CommandContext<CommandSourceStack> context) {
        String slug = StringArgumentType.getString(context, "slug");
        WorldContentStatus status = WorldCollections.edit(
                new WorldCollectionEdit.Create(slug, StringArgumentType.getString(context, "name")),
                author(context.getSource()));
        return reply(context.getSource(), status, "created");
    }

    /** Applies an edit built from the collection's current record. */
    private static int setCollection(CommandContext<CommandSourceStack> context,
                                     Function<WorldCollectionRecord, WorldCollectionEdit> edit) {
        WorldCollectionRecord record = existing(context);
        if (record == null) {
            return 0;
        }
        return applied(context, record, WorldCollections.edit(edit.apply(record), author(context.getSource())), "updated");
    }

    private static int boxColor(CommandContext<CommandSourceStack> context) {
        String color = StringArgumentType.getString(context, "color");
        boolean none = "none".equals(color);
        return setCollection(context, record -> new WorldCollectionEdit.SetCollection(record.id(), record.name(),
                record.author(), record.red(), record.green(), record.blue(),
                none ? WorldCollectionEdit.BOX_NONE : WorldCollectionEdit.BOX_COLOR, none ? "" : color, logo(record)));
    }

    private static int setArchived(CommandContext<CommandSourceStack> context, boolean archived) {
        WorldCollectionRecord record = existing(context);
        if (record == null) {
            return 0;
        }
        return applied(context, record, WorldCollections.edit(new WorldCollectionEdit.SetArchived(record.id(), archived),
                author(context.getSource())), archived ? "archived" : "restored");
    }

    private static int publish(CommandContext<CommandSourceStack> context) {
        WorldCollectionRecord record = existing(context);
        if (record == null) {
            return 0;
        }
        return reply(context.getSource(), WorldCollections.edit(new WorldCollectionEdit.Publish(record.id()),
                author(context.getSource())), "published");
    }

    private static int setFigureEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        WorldCollectionRecord record = existing(context);
        if (record == null) {
            return 0;
        }
        String figure = StringArgumentType.getString(context, "figure");
        if (!WorldContentIds.isSlug(figure)) {
            context.getSource().sendFailure(Component.translatable(KEY + "unknown_figure", figure));
            return 0;
        }
        WorldContentStatus status = WorldCollections.edit(
                new WorldCollectionEdit.SetFigureEnabled(record.id(), figure, enabled), author(context.getSource()));
        if (status.result() == WorldContentStatus.Result.OK) {
            context.getSource().sendSuccess(() -> Component.translatable(KEY + (enabled ? "figure_restored" : "figure_retired"),
                    figure, record.name()), true);
            return 1;
        }
        return reply(context.getSource(), status, "updated");
    }

    private static int importPack(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String slug = StringArgumentType.getString(context, "slug");
        String sourceName = StringArgumentType.getString(context, "source");
        int colon = sourceName.indexOf(':');
        String namespace = colon < 0 ? "" : sourceName.substring(0, colon);
        String path = colon < 0 ? "" : sourceName.substring(colon + 1);
        if (!WorldContentIds.isSlug(slug) || !WorldCollectionImporter.isValidSource(namespace, path)) {
            source.sendFailure(Component.translatable(KEY + "invalid_source"));
            return 0;
        }
        WorldCollectionRecord before = WorldCollections.record(WorldContentIds.collectionId(slug));
        WorldContentStatus status = WorldCollections.importPack(source.getServer().getResourceManager(), slug, namespace, path,
                author(source));
        if (status.result() != WorldContentStatus.Result.OK) {
            return reply(source, status, "imported");
        }
        WorldCollectionRecord after = WorldCollections.record(WorldContentIds.collectionId(slug));
        boolean unchanged = before != null && before.equals(after);
        source.sendSuccess(() -> Component.translatable(KEY + (unchanged ? "unchanged" : "imported"), status.detail()), true);
        return 1;
    }

    private static int purge(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        if (!id.equals(StringArgumentType.getString(context, "again"))) {
            source.sendFailure(Component.translatable(KEY + "purge_mismatch"));
            return 0;
        }
        if (existing(context) == null) {
            return 0;
        }
        return reply(source, WorldCollections.purge(id), "purged");
    }

    /** The record the id argument names, or null after telling the source why there is none. */
    @Nullable
    private static WorldCollectionRecord existing(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        WorldCollectionRecord record = WorldContentIds.isWorldCollectionId(id) ? WorldCollections.record(id) : null;
        if (record == null) {
            context.getSource().sendFailure(Component.translatable(KEY + "unknown_collection", id));
        }
        return record;
    }

    /** Replies to an edit of an existing record, telling an edit that changed nothing apart. */
    private static int applied(CommandContext<CommandSourceStack> context, WorldCollectionRecord before,
                               WorldContentStatus status, String successKey) {
        if (status.result() == WorldContentStatus.Result.OK && before.equals(WorldCollections.record(before.id()))) {
            context.getSource().sendSuccess(() -> Component.translatable(KEY + "unchanged", before.name()), false);
            return 1;
        }
        return reply(context.getSource(), status, successKey);
    }

    private static int reply(CommandSourceStack source, WorldContentStatus status, String successKey) {
        if (status.result() == WorldContentStatus.Result.OK) {
            source.sendSuccess(() -> Component.translatable(KEY + successKey, status.detail()), true);
            return 1;
        }
        source.sendFailure(Component.translatable(KEY + status.result().name().toLowerCase(Locale.ROOT), status.detail()));
        return 0;
    }

    private static WorldCollectionEdit.SetCollection settings(WorldCollectionRecord record, String name, String author) {
        return new WorldCollectionEdit.SetCollection(record.id(), name, author, record.red(), record.green(),
                record.blue(), boxMode(record), box(record), logo(record));
    }

    private static int boxMode(WorldCollectionRecord record) {
        return record.boxTexture() != null ? WorldCollectionEdit.BOX_TEXTURE
                : record.boxColor() != null ? WorldCollectionEdit.BOX_COLOR : WorldCollectionEdit.BOX_NONE;
    }

    private static String box(WorldCollectionRecord record) {
        return record.boxTexture() != null ? record.boxTexture() : record.boxColor() != null ? record.boxColor() : "";
    }

    private static String logo(WorldCollectionRecord record) {
        return record.logo() != null ? record.logo() : "";
    }

    /** Who a change is recorded as made by: the player, or nobody for the console and functions. */
    private static UUID author(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : WorldCollectionStore.NOBODY;
    }

    private static String kib(long bytes) {
        return Long.toString((bytes + 1023) / 1024);
    }
}
