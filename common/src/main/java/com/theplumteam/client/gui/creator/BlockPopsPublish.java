package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.CreatorScreen;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.util.LinkOpening;
import com.theplumteam.client.remote.ClientCustomCollections;
import com.theplumteam.client.upload.ManageRun;
import com.theplumteam.client.upload.MojangJoin;
import com.theplumteam.client.upload.PlayerExport;
import com.theplumteam.client.upload.PublishRun;
import com.theplumteam.client.upload.SessionClient;
import com.theplumteam.client.upload.UploadApi;
import com.theplumteam.client.upload.UploadClientId;
import com.theplumteam.client.upload.UploadErrors;
import com.theplumteam.client.upload.UploadException;
import com.theplumteam.client.upload.UploadExecutor;
import com.theplumteam.client.worldcontent.CreatorSource;
import com.theplumteam.client.worldcontent.PersonalLinks;
import com.theplumteam.network.CustomCollectionRequestPacket;
import com.theplumteam.remote.player.ApiError;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Publishing one of the player's own drafts on BlockPops, from the creator: the terms the first
 * time, the account's proof, the upload with its progress, and then the code to share, or what
 * went wrong in the game's own words and what the player can do about it.
 *
 * <p>The upload runs on its own thread and goes on when the dialog or the creator is closed; it
 * then reports in a toast. One draft is published at a time: asking again while it is on its way
 * shows how far it is. Nothing here talks to the server the player is on, except when the
 * player asks to add the published collection to this world.
 */
final class BlockPopsPublish {
    /** The version of the publishing terms this build shows. */
    static final int TERMS = 1;
    private static final String DISCORD = "https://discord.gg/yGxdvA7qej";
    private static final int WIDTH = 340;
    private static final int WIDE = 440;
    private static final int LINES = 7;

    /** One upload on its way. */
    private static final class Run {
        final String name;
        volatile PublishRun.Step step = PublishRun.Step.CHECKING;
        volatile int done;
        volatile int total;
        @Nullable
        UiModal dialog;

        Run(String name) {
            this.name = name;
        }

        Component text() {
            return switch (step) {
                case CHECKING -> Component.translatable("blockpops.upload.progress.checking");
                case VERIFYING -> Component.translatable("blockpops.upload.progress.verifying");
                case FILES -> Component.translatable("blockpops.upload.progress.files", Math.min(done + 1, total), total);
                case PUBLISHING -> Component.translatable("blockpops.upload.progress.publishing");
            };
        }
    }

    private static final Map<String, Run> RUNNING = new HashMap<>();
    // What each draft was published as, read once and kept until a publish changes it
    @Nullable
    private static PersonalLinks links;

    /** What a draft was published as, for the creator to show its code; null when it was not. */
    @Nullable
    static PersonalLinks.Link linkOf(String draftId) {
        if (links == null) {
            links = PersonalLinks.load(Minecraft.getInstance().gameDirectory.toPath());
        }
        return links.link(draftId).orElse(null);
    }

    /** Copies a published collection's code, and says so. */
    static void copyCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        UiToast.show(Component.translatable("blockpops.upload.done.copied"));
    }

    private BlockPopsPublish() {
    }

    /** Whether a draft is being published right now. */
    static boolean isRunning(String draftId) {
        return RUNNING.containsKey(draftId);
    }

    /** Publishes the draft with that id, or shows how far its publish is. Client thread. */
    static void start(String draftId) {
        Minecraft minecraft = Minecraft.getInstance();
        Run running = RUNNING.get(draftId);
        if (running != null) {
            showProgress(running);
            return;
        }
        PersonalLinks links = PersonalLinks.load(minecraft.gameDirectory.toPath());
        if (links.terms() < TERMS) {
            UiModal terms = new UiModal(Component.translatable("blockpops.upload.terms.title"), Component.empty())
                    .width(WIDTH).accent(DialogFrame.Accent.CREATE, Theme.ICON_HELP);
            lines(terms, () -> Component.translatable("blockpops.upload.terms.body", minecraft.getUser().getName()),
                    Theme.TEXT_SOFT, 10);
            terms.cancelButton(Component.translatable("blockpops.upload.gate.later"));
            terms.button(Component.translatable("blockpops.upload.terms.accept"), UiButton.Style.GREEN, () -> {
                links.acceptTerms(TERMS);
                begin(draftId, links);
            });
            show(terms, null);
            return;
        }
        begin(draftId, links);
    }

    private static void begin(String draftId, PersonalLinks links) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldCollectionRecord record = CreatorSource.record(draftId);
        if (record == null || !CreatorSource.isPersonal() || RUNNING.containsKey(draftId)) {
            return;
        }
        PersonalLinks.Link link = links.link(draftId).orElse(null);
        PlayerExport.Export export;
        try {
            export = PlayerExport.of(record, CreatorSource.blobs(), CreatorSource::personalBytes,
                    link != null ? link.id() : null, link != null ? link.rev() : 0, TERMS, UploadClientId.current(),
                    minecraft.getUser().getName());
        } catch (WorldContentException e) {
            failed(Component.translatable(UploadErrors.key(ApiError.INVALID_FILE),
                    Component.translatable(UploadErrors.ruleKey(e.code()))), null, null);
            return;
        }
        Run run = new Run(record.name());
        RUNNING.put(draftId, run);
        showProgress(run);
        boolean changes = link != null;
        UploadExecutor.get().execute(() -> {
            UploadApi api = UploadApi.forGame();
            PublishRun publish = new PublishRun(api, new SessionClient(api, MojangJoin.forGame()), Thread::sleep);
            try {
                UploadApi.Published published = publish.run(export.pack(), export.packageBytes(), export::bytes,
                        (step, done, total) -> {
                            run.done = done;
                            run.total = total;
                            run.step = step;
                        });
                minecraft.execute(() -> published(draftId, run, links, published, changes));
            } catch (UploadException e) {
                minecraft.execute(() -> refused(draftId, run, links, e));
            } catch (RuntimeException e) {
                minecraft.execute(() -> refused(draftId, run, links, new UploadException(ApiError.INTERNAL)));
            }
        });
    }

    // ---- How it ended

    private static void published(String draftId, Run run, PersonalLinks links, UploadApi.Published published,
                                  boolean changes) {
        end(draftId, run);
        links.put(draftId, new PersonalLinks.Link(published.id(), published.code(), published.rev()));
        BlockPopsPublish.links = links;
        Minecraft minecraft = Minecraft.getInstance();
        Component summary = changes ? Component.translatable("blockpops.upload.done.updated", published.rev())
                : Component.translatable("blockpops.upload.done.code", published.code());
        // Wide enough for three buttons whose labels stay legible
        UiModal dialog = new UiModal(Component.translatable("blockpops.upload.done.title"), Component.empty())
                .width(WIDE).accent(DialogFrame.Accent.CREATE, Theme.ICON_SAVE);
        lines(dialog, () -> changes ? Component.translatable("blockpops.upload.done.updated", published.rev())
                .append(" ").append(Component.translatable("blockpops.upload.done.code", published.code()))
                : summary, Theme.TEXT_SOFT, 4);
        dialog.cancelButton(Component.translatable("blockpops.upload.done.close"));
        if (!changes && managesCodes(minecraft)) {
            dialog.button(Component.translatable("blockpops.upload.done.add_here"), UiButton.Style.SECONDARY,
                    () -> new CustomCollectionRequestPacket(true, published.code()).sendToServer());
        }
        dialog.button(Component.translatable("blockpops.upload.done.copy"), UiButton.Style.GREEN,
                () -> copyCode(published.code()));
        show(dialog, summary);
    }

    // Whether this player may add a code to the world they are in: an operator or the host, on a server that takes codes
    private static boolean managesCodes(Minecraft minecraft) {
        return minecraft.player != null && ClientCustomCollections.isSynced()
                && (ServerLevels.hasCommandLevel(minecraft.player, 2) || minecraft.getSingleplayerServer() != null);
    }

    private static void refused(String draftId, Run run, PersonalLinks links, UploadException e) {
        end(draftId, run);
        Component text = UploadErrors.message(e);
        switch (e.error()) {
            case NOT_SUPPORTER, SUPPORTER_LAPSED -> {
                UiModal gate = new UiModal(Component.translatable("blockpops.upload.gate.title"), Component.empty())
                        .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
                lines(gate, () -> text, Theme.TEXT_SOFT, LINES);
                gate.cancelButton(Component.translatable("blockpops.upload.gate.later"));
                gate.button(Component.translatable("blockpops.upload.gate.recheck"), UiButton.Style.GREEN,
                        () -> begin(draftId, links));
                show(gate, text);
            }
            case SUPPORTER_LINK_REQUIRED -> {
                UiModal link = new UiModal(Component.translatable("blockpops.upload.link.title"), Component.empty())
                        .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
                lines(link, () -> Component.translatable("blockpops.upload.link.body",
                        Minecraft.getInstance().getUser().getName()), Theme.TEXT_SOFT, LINES);
                link.cancelButton(Component.translatable("blockpops.upload.gate.later"));
                link.button(Component.translatable("blockpops.upload.link.start"), UiButton.Style.GREEN,
                        () -> startLink(draftId, links));
                show(link, text);
            }
            case CONFLICT -> {
                PersonalLinks.Link held = links.link(draftId).orElse(null);
                Runnable overwrite = held == null || e.detail() < 1 ? null : () -> {
                    // The draft is published over whatever is there: it builds on the revision that is there now
                    links.put(draftId, new PersonalLinks.Link(held.id(), held.code(), (int) e.detail()));
                    begin(draftId, links);
                };
                failed(text, overwrite == null ? null : Component.translatable("blockpops.upload.conflict.overwrite"),
                        overwrite);
            }
            case TERMS_OUTDATED, CLIENT_OUTDATED, BANNED, FROZEN, NOT_IN_BETA, NOT_OWNER, UNAVAILABLE, QUOTA_COLLECTIONS,
                 QUOTA_BYTES, NAME_REFUSED, CONTENT_BLOCKED, INVALID_FILE, SESSION_INVALID, MULTIPLAYER_DISABLED,
                 MOJANG_BANNED, NAME_CHANGE_REQUIRED -> failed(text, null, null);
            default -> failed(text, Component.translatable("blockpops.upload.retry"), () -> begin(draftId, links));
        }
    }

    // Asks for the code that ties a purchase made under this name to this account, and shows it
    private static void startLink(String draftId, PersonalLinks links) {
        Minecraft minecraft = Minecraft.getInstance();
        UploadExecutor.get().execute(() -> {
            UploadApi api = UploadApi.forGame();
            try {
                String code = new ManageRun(api, new SessionClient(api, MojangJoin.forGame())).link().code();
                minecraft.execute(() -> {
                    UiModal dialog = new UiModal(Component.translatable("blockpops.upload.link.title"), Component.empty())
                            .width(WIDTH).accent(DialogFrame.Accent.SETTINGS, Theme.ICON_HELP);
                    lines(dialog, () -> Component.translatable("blockpops.upload.link.code", code), Theme.TEXT_SOFT, 3);
                    dialog.cancelButton(Component.translatable("blockpops.upload.done.close"));
                    dialog.button(Component.translatable("blockpops.upload.link.discord"), UiButton.Style.SECONDARY, () -> {
                        minecraft.keyboardHandler.setClipboard(code);
                        openDiscord();
                    });
                    dialog.button(Component.translatable("blockpops.upload.done.copy"), UiButton.Style.SECONDARY, () -> {
                        minecraft.keyboardHandler.setClipboard(code);
                        UiToast.show(Component.translatable("blockpops.upload.done.copied"));
                    });
                    dialog.button(Component.translatable("blockpops.upload.link.recheck"), UiButton.Style.GREEN,
                            () -> begin(draftId, links));
                    show(dialog, Component.translatable("blockpops.upload.link.code", code));
                });
            } catch (UploadException e) {
                minecraft.execute(() -> failed(UploadErrors.message(e), null, null));
            }
        });
    }

    // The team's Discord, through the game's own question before any link is opened
    private static void openDiscord() {
        Minecraft minecraft = Minecraft.getInstance();
        URI target = URI.create(DISCORD);
        net.minecraft.client.gui.screens.Screen back = minecraft.screen;
        minecraft.setScreen(LinkOpening.confirm(target, confirmed -> {
            if (confirmed) {
                LinkOpening.open(target);
            }
            minecraft.setScreen(back);
        }));
    }

    private static void failed(Component text, @Nullable Component actionLabel, @Nullable Runnable action) {
        UiModal dialog = new UiModal(Component.translatable("blockpops.upload.failed.title"), Component.empty())
                .width(WIDTH).accent(DialogFrame.Accent.DANGER, Theme.ICON_WARNING);
        lines(dialog, () -> text, Theme.TEXT_SOFT, LINES);
        dialog.cancelButton(Component.translatable("blockpops.upload.done.close"));
        if (actionLabel != null && action != null) {
            dialog.button(actionLabel, UiButton.Style.GREEN, action);
        }
        show(dialog, text);
    }

    // ---- Showing it

    private static void showProgress(Run run) {
        UiModal dialog = new UiModal(Component.translatable("blockpops.upload.progress.title", run.name), Component.empty())
                .width(WIDTH).accent(DialogFrame.Accent.CREATE, Theme.ICON_SAVE);
        lines(dialog, run::text, Theme.TEXT_SOFT, 2);
        // The upload goes on without the dialog, and says how it ended in a toast
        dialog.cancelButton(Component.translatable("blockpops.upload.progress.hide"));
        run.dialog = dialog;
        show(dialog, null);
    }

    private static void end(String draftId, Run run) {
        RUNNING.remove(draftId, run);
        if (run.dialog != null) {
            run.dialog.close();
            run.dialog = null;
        }
    }

    // In the creator when it is open, as a dialog; anywhere else as a toast, when there is something to say
    private static void show(UiModal dialog, @Nullable Component otherwise) {
        // By this name the build finds the current screen on every version of the game
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CreatorScreen screen) {
            screen.openModal(dialog);
        } else if (otherwise != null) {
            UiToast.show(otherwise);
        }
    }

    // Text that may change while the dialog is open, wrapped to its width
    private static void lines(UiModal dialog, Supplier<Component> text, int colour, int most) {
        dialog.node(new UiNode.Box() {
            @Override
            public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                Font font = Minecraft.getInstance().font;
                Draw.lines(graphics, font, text.get().getString(), x, y, width, most, colour);
            }
        }, wrapped(text.get(), most) * Theme.LINE_HEIGHT);
    }

    private static int wrapped(Component text, int most) {
        Font font = Minecraft.getInstance().font;
        return Math.max(1, Math.min(most, Draw.wrap(font, text.getString(), WIDTH - 2 * DialogFrame.PADDING).size()));
    }
}
