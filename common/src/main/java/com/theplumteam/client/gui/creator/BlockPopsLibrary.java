package com.theplumteam.client.gui.creator;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.client.remote.PlayerDownload;
import com.theplumteam.client.upload.ManageRun;
import com.theplumteam.client.upload.MojangJoin;
import com.theplumteam.client.upload.SessionClient;
import com.theplumteam.client.upload.UploadApi;
import com.theplumteam.client.upload.UploadErrors;
import com.theplumteam.client.upload.UploadException;
import com.theplumteam.client.upload.UploadExecutor;
import com.theplumteam.client.worldcontent.CreatorSave;
import com.theplumteam.client.worldcontent.CreatorSource;
import com.theplumteam.client.worldcontent.PersonalLinks;
import com.theplumteam.client.worldcontent.PersonalSession;
import com.theplumteam.client.worldcontent.Slugs;
import com.theplumteam.registry.ModSounds;
import com.theplumteam.remote.RemoteExecutor;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.server.PlayerCollections;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the player's account has published on BlockPops, from the creator over their own drafts:
 * the collections with their codes, each one's versions, going back to an earlier version or
 * withdrawing one, and bringing a published collection to this computer as a draft, to go on
 * editing it where its draft is not.
 *
 * <p>Every answer comes from the upload service for the account the game is signed in with,
 * which the service verifies each time; nothing here is asked of the server the player is on.
 * A collection that is brought here is read back as any game reads a player collection, through
 * its signed documents, and is taken in by the session as a draft is saved.
 */
final class BlockPopsLibrary {
    private static final int WIDTH = 380;
    private static final int WIDE = 440;
    private static final int ROW_HEIGHT = 2 * Theme.LINE_HEIGHT + 6;
    private static final int MOST_ROWS = 6;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /** One line of a list: what it is, more about it below, and what a click on it does. */
    private record Row(String title, String detail, int detailColour, @Nullable Runnable action) {
    }

    /** A list of rows, each clicked as a whole; the wheel moves through one longer than it shows. */
    private static final class Rows extends UiNode.Box {
        private final List<Row> rows;
        private int first;

        Rows(List<Row> rows) {
            this.rows = rows;
        }

        int naturalHeight() {
            return Math.max(1, Math.min(MOST_ROWS, rows.size())) * ROW_HEIGHT;
        }

        private int shownRows() {
            return Math.max(1, height / ROW_HEIGHT);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int shown = shownRows();
            boolean longer = rows.size() > shown;
            int textWidth = width - 8 - (longer ? 6 : 0);
            for (int i = 0; i < shown && first + i < rows.size(); i++) {
                Row row = rows.get(first + i);
                int top = y + i * ROW_HEIGHT;
                boolean over = row.action() != null && mouseX >= x && mouseX < x + width && mouseY >= top
                        && mouseY < top + ROW_HEIGHT;
                if (over) {
                    graphics.fill(x, top, x + width, top + ROW_HEIGHT - 1, Theme.HOVER);
                }
                Draw.string(graphics, font, Draw.cut(font, row.title(), textWidth), x + 4, top + 3,
                        row.action() != null ? Theme.TEXT : Theme.MUTED);
                Draw.string(graphics, font, Draw.cut(font, row.detail(), textWidth), x + 4, top + 3 + Theme.LINE_HEIGHT,
                        row.detailColour());
                graphics.fill(x, top + ROW_HEIGHT - 1, x + width, top + ROW_HEIGHT, Theme.SEPARATOR);
            }
            if (longer) {
                // Where the rows shown lie in the whole list
                int track = shown * ROW_HEIGHT;
                int thumb = Math.max(8, track * shown / rows.size());
                int offset = (track - thumb) * first / Math.max(1, rows.size() - shown);
                graphics.fill(x + width - 3, y, x + width - 1, y + track, Theme.TRACK);
                graphics.fill(x + width - 3, y + offset, x + width - 1, y + offset + thumb, Theme.HIGHLIGHT_BAR);
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            if (!click.left() || !isMouseOver(click.x(), click.y())) {
                return false;
            }
            int index = first + (int) (click.y() - y) / ROW_HEIGHT;
            if (index < 0 || index >= rows.size() || rows.get(index).action() == null) {
                return false;
            }
            UiSounds.play(ModSounds.CLICK);
            rows.get(index).action().run();
            return true;
        }

        @Override
        public boolean scroll(double mouseX, double mouseY, double amount) {
            int most = Math.max(0, rows.size() - shownRows());
            int next = Math.max(0, Math.min(most, first - (int) Math.signum(amount)));
            boolean moved = next != first;
            first = next;
            return moved;
        }
    }

    private BlockPopsLibrary() {
    }

    // ---- The account's collections

    /** Asks the service what this account has published and shows it. Client thread, in the creator. */
    static void open() {
        UiModal busy = new UiModal(Component.translatable("blockpops.published.title"), Component.empty())
                .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_ARCHIVE);
        BlockPopsPublish.lines(busy, () -> Component.translatable("blockpops.published.loading"), Theme.TEXT_SOFT, 2);
        busy.cancelButton(Component.translatable("blockpops.upload.done.close"));
        BlockPopsPublish.show(busy, null);
        ask(ManageRun::list, account -> {
            // Closed meanwhile: the answer is not shown over whatever the player went on to
            if (busy.isOpen()) {
                list(account);
            }
        }, e -> {
            if (busy.isOpen()) {
                failed(Component.translatable("blockpops.published.title"), UploadErrors.message(e));
            }
        });
    }

    private static void list(UploadApi.Account account) {
        UiModal dialog = new UiModal(Component.translatable("blockpops.published.title"), Component.empty())
                .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_ARCHIVE);
        List<Row> rows = new ArrayList<>();
        for (UploadApi.Mine mine : account.collections()) {
            if (mine.state().equals("removed")) {
                continue;
            }
            boolean live = mine.state().equals("live");
            String detail = live ? Component.translatable("blockpops.published.row", mine.code(), mine.rev()).getString()
                    : Component.translatable("blockpops.published.state.quarantined").getString();
            rows.add(new Row(mine.name(), detail, live ? Theme.MUTED : Theme.AMBER, () -> detail(account, mine)));
        }
        if (rows.isEmpty()) {
            BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.none"), Theme.TEXT_SOFT, 2);
        } else {
            Rows list = new Rows(rows);
            dialog.node(list, list.naturalHeight());
        }
        UploadApi.Quota quota = account.quota();
        BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.quota", quota.collections(),
                quota.collectionsLimit(), megabytes(quota.bytes()), megabytes(quota.bytesLimit())), Theme.MUTED, 2);
        dialog.cancelButton(Component.translatable("blockpops.upload.done.close"));
        BlockPopsPublish.show(dialog, null);
    }

    // One collection: its code, where its draft is, and what can be done with it
    private static void detail(UploadApi.Account account, UploadApi.Mine mine) {
        UiModal dialog = new UiModal(Component.literal(mine.name()), Component.empty())
                .width(WIDE).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_ARCHIVE);
        boolean live = mine.state().equals("live");
        String draft = draftOf(mine.id());
        WorldCollectionRecord held = draft != null ? CreatorSource.record(draft) : null;
        BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.detail.code", mine.code()),
                Theme.TEXT, 1);
        BlockPopsPublish.lines(dialog, () -> live
                ? Component.translatable("blockpops.published.detail.version", mine.rev(), day(mine.updatedAt()))
                : Component.translatable("blockpops.published.state.quarantined"), live ? Theme.TEXT_SOFT : Theme.AMBER, 2);
        if (held != null) {
            BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.detail.here", held.name()),
                    Theme.MUTED, 3);
        } else if (live) {
            // One under review cannot be read back for now, so nothing is said of bringing it here
            BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.detail.not_here"),
                    Theme.MUTED, 3);
        }
        dialog.button(Component.translatable("blockpops.published.back"), UiButton.Style.SECONDARY, () -> list(account));
        dialog.button(Component.translatable("blockpops.upload.done.copy"), UiButton.Style.SECONDARY,
                () -> BlockPopsPublish.copyCode(mine.code()));
        dialog.button(Component.translatable("blockpops.published.versions"), UiButton.Style.SECONDARY,
                () -> versions(account, mine));
        if (held == null && live) {
            dialog.button(Component.translatable("blockpops.published.edit_here"), UiButton.Style.GREEN, () -> download(mine));
        }
        BlockPopsPublish.show(dialog, null);
    }

    // The draft on this computer that was published as that collection; null when there is none
    @Nullable
    private static String draftOf(String publishedId) {
        PersonalLinks links = BlockPopsPublish.links();
        String draft = links.draftOf(publishedId).orElse(null);
        if (draft != null && CreatorSource.record(draft) == null) {
            // The draft was deleted here since: what it was published as is free for another
            links.remove(draft);
            return null;
        }
        return draft;
    }

    // ---- Its versions

    private static void versions(UploadApi.Account account, UploadApi.Mine mine) {
        UiModal dialog = new UiModal(Component.translatable("blockpops.published.versions.title", mine.name()),
                Component.empty()).width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_ARCHIVE);
        boolean live = mine.state().equals("live");
        List<Row> rows = new ArrayList<>();
        boolean earlier = false;
        for (UploadApi.RevisionRow revision : mine.revisions()) {
            boolean current = revision.rev() == mine.rev();
            boolean kept = revision.state().equals("kept");
            String title = Component.translatable("blockpops.published.versions.row", revision.rev(), day(revision.at()))
                    .getString();
            String state = current ? Component.translatable("blockpops.published.versions.current").getString()
                    : kept ? "" : Component.translatable(revision.state().equals("revoked")
                    ? "blockpops.published.versions.revoked" : "blockpops.published.versions.withdrawn").getString();
            String detail = Component.translatable("blockpops.published.versions.detail", revision.figures(),
                    megabytes(revision.bytes())).getString() + (state.isEmpty() ? "" : " · " + state);
            boolean acts = live && kept && !current;
            earlier |= acts;
            rows.add(new Row(title, detail, current ? Theme.GREEN : kept ? Theme.MUTED : Theme.AMBER,
                    acts ? () -> version(account, mine, revision) : null));
        }
        Rows list = new Rows(rows);
        dialog.node(list, list.naturalHeight());
        if (earlier) {
            BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.versions.hint"), Theme.MUTED, 2);
        }
        dialog.button(Component.translatable("blockpops.published.back"), UiButton.Style.SECONDARY,
                () -> detail(account, mine));
        BlockPopsPublish.show(dialog, null);
    }

    // One earlier version: going back to it, or withdrawing it
    private static void version(UploadApi.Account account, UploadApi.Mine mine, UploadApi.RevisionRow revision) {
        UiModal dialog = new UiModal(Component.translatable("blockpops.published.version.title", revision.rev()),
                Component.empty()).width(WIDE).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_UNDO);
        BlockPopsPublish.lines(dialog, () -> Component.translatable("blockpops.published.version.body"), Theme.TEXT_SOFT, 6);
        UiButton back = dialog.button(Component.translatable("blockpops.published.back"), UiButton.Style.SECONDARY,
                () -> versions(account, mine));
        dialog.button(Component.translatable("blockpops.published.version.withdraw"), UiButton.Style.DANGER,
                () -> ask(manage -> {
                    manage.withdraw(mine.id(), mine.rev(), revision.rev());
                    return revision.rev();
                }, rev -> UiToast.show(Component.translatable("blockpops.published.withdrawn", rev, mine.name())),
                        e -> refused(mine, e)));
        dialog.button(Component.translatable("blockpops.published.version.rollback"), UiButton.Style.GREEN,
                () -> ask(manage -> manage.rollback(mine.id(), mine.rev(), revision.rev()), rolled -> {
                    // The draft here, if any, builds on what is published now
                    String draft = draftOf(mine.id());
                    if (draft != null) {
                        BlockPopsPublish.links().put(draft, new PersonalLinks.Link(mine.id(), mine.code(), rolled.rev()));
                    }
                    UiToast.show(Component.translatable("blockpops.published.rolled", mine.name(), revision.rev(),
                            rolled.rev()));
                }, e -> refused(mine, e)));
        dialog.defaultButton(back);
        BlockPopsPublish.show(dialog, null);
    }

    private static void refused(UploadApi.Mine mine, UploadException e) {
        failed(Component.literal(mine.name()), UploadErrors.message(e));
    }

    // What went wrong, under the name of what it went wrong with
    private static void failed(Component title, Component text) {
        UiModal dialog = new UiModal(title, Component.empty()).width(WIDTH).accent(DialogFrame.Accent.DANGER,
                Theme.ICON_WARNING);
        BlockPopsPublish.lines(dialog, () -> text, Theme.TEXT_SOFT, 7);
        dialog.cancelButton(Component.translatable("blockpops.upload.done.close"));
        BlockPopsPublish.show(dialog, text);
    }

    // ---- Bringing one here

    private static void download(UploadApi.Mine mine) {
        Minecraft minecraft = Minecraft.getInstance();
        UiModal busy = new UiModal(Component.literal(mine.name()), Component.empty())
                .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_ARCHIVE);
        BlockPopsPublish.lines(busy, () -> Component.translatable("blockpops.published.downloading", mine.name()),
                Theme.TEXT_SOFT, 2);
        busy.cancelButton(Component.translatable("blockpops.upload.progress.hide"));
        BlockPopsPublish.show(busy, null);
        RemoteExecutor.get().execute(() -> {
            try {
                PlayerDownload.Download download = PlayerDownload.of(PlayerCollections.fetcher(), mine.code());
                minecraft.execute(() -> downloaded(download, busy));
            } catch (PlayerFetcher.Refused | IOException | RuntimeException e) {
                BlockPopsMod.LOGGER.warn("Could not read back the published collection {}: {}", mine.code(), e.toString());
                minecraft.execute(() -> {
                    busy.close();
                    failed(Component.literal(mine.name()), Component.translatable("blockpops.published.download_failed"));
                });
            }
        });
    }

    private static void downloaded(PlayerDownload.Download download, UiModal busy) {
        busy.close();
        // The creator was left meanwhile: its drafts are closed, and nothing is kept
        if (!CreatorSource.isPersonal() || draftOf(download.id()) != null) {
            return;
        }
        Map<String, PersonalSession.Taken> files = new HashMap<>();
        download.files().forEach((sha, file) -> files.put(sha, new PersonalSession.Taken(file.type(), file.bytes())));
        String slug = Slugs.unique(Slugs.collection(download.record().name()), Slugs::isCollectionTaken);
        WorldContentStatus status = PersonalSession.adopt(slug, download.record(), files);
        if (status.result() != WorldContentStatus.Result.OK) {
            failed(Component.literal(download.record().name()), CreatorSave.message(status));
            return;
        }
        BlockPopsPublish.links().put(status.detail(), new PersonalLinks.Link(download.id(), download.code(), download.rev()));
        UiToast.show(Component.translatable("blockpops.published.downloaded", download.record().name()));
    }

    // ---- Asking the service

    private interface Call<T> {
        T run(ManageRun manage) throws UploadException;
    }

    // One question to the service for this account, answered on the client thread
    private static <T> void ask(Call<T> call, Consumer<T> then, Consumer<UploadException> refused) {
        Minecraft minecraft = Minecraft.getInstance();
        UploadExecutor.get().execute(() -> {
            UploadApi api = UploadApi.forGame();
            try {
                T answer = call.run(new ManageRun(api, new SessionClient(api, MojangJoin.forGame())));
                minecraft.execute(() -> then.accept(answer));
            } catch (UploadException e) {
                minecraft.execute(() -> refused.accept(e));
            } catch (RuntimeException e) {
                minecraft.execute(() -> refused.accept(new UploadException(ApiError.INTERNAL)));
            }
        });
    }

    private static String day(long seconds) {
        return DAY.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
