package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiPopover;
import com.theplumteam.client.gui.creator.ui.UiToast;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.AutoSaver;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.client.worldcontent.CreatorDraft.FigureDraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * Publish's popover, standing on the button at the foot of the editor's sidebar
 * ({@link UiPopover}): the one place a collection goes to the players from.
 *
 * <p>Its body says, live, whether the collection can be published: each thing that holds it back
 * in red, a click on it closing the popover and going to its fix ({@link CreatorEditorScreen#publishBlockers}),
 * or else one line counting the figures in the draw. A line follows with the figures a draft
 * leaves out for a missing file, or with what a published collection's changes are ("4 changes:
 * name, 2 figures, order"), then where players will find it. While a draft's last changes are still being saved, which opening
 * the popover asks for, it says so and waits.
 *
 * <p>"Test pull" on the left closes it and opens the test draw ({@link TestDrawDialog}), whose
 * "Back" opens it again; "Not now" and a click outside close it. "Yes, publish" (a published
 * collection's "Publish changes") does not close it: the popover holds itself open saying it is
 * saving, then publishing, and closes once the server has it, a toast saying so; a refusal shows
 * in red in the popover, which waits for the operator again.
 */
final class PublishPopover {
    private final CreatorEditorScreen editor;
    private final UiPopover popover;
    private final UiButton tryButton;
    private final UiButton laterButton;
    private final UiButton confirmButton;
    // Whether the publish was asked for and has not ended, and what the server said last time
    private boolean sending;
    @Nullable
    private Component refusal;

    /**
     * @param anchor where Publish is, which the popover stands on
     */
    PublishPopover(CreatorEditorScreen editor, Supplier<UiNode.Rect> anchor) {
        this.editor = editor;
        this.popover = new UiPopover(title(), anchor).accent(DialogFrame.Accent.CREATE, PixelIcon.UPLOAD);
        this.tryButton = new UiButton(tr("publish.test_pull"), UiButton.Style.SECONDARY, this::tryDraw);
        this.laterButton = new UiButton(tr("publish.not_now"), UiButton.Style.LINK, this.popover::cancel);
        this.confirmButton = new UiButton(confirmLabel(), UiButton.Style.PRIMARY, this::confirm);
        this.popover.leftButton(this.tryButton);
        this.popover.button(this.laterButton);
        this.popover.button(this.confirmButton);
        this.popover.primary(this.confirmButton);
        this.popover.content(shown -> fill());
        this.popover.onClose(editor::publishClosed);
    }

    UiPopover popover() {
        return this.popover;
    }

    boolean isOpen() {
        return this.popover.isOpen();
    }

    private boolean changesOnly() {
        return !this.editor.saver().isAuto();
    }

    private Component title() {
        String name = this.editor.displayName();
        return changesOnly() ? tr("publish.popover_title_changes", name) : tr("publish.popover_title", name);
    }

    private Component confirmLabel() {
        return changesOnly() ? tr("publish.button_changes") : tr("publish.confirm");
    }

    /**
     * Whether a draft's changes are still being saved: what waits, the save on its way or the
     * sync it waits for. The check is only shown once they are, as the publish would wait anyway.
     */
    private boolean draftSaving() {
        AutoSaver saver = this.editor.saver();
        return saver.isAuto() && !saver.hasFailed() && saver.isSaving();
    }

    // The body, laid out again on every tick while the popover is open

    private void fill() {
        UiPopover shown = this.popover;
        shown.setTitle(title());
        CreatorDraft draft = this.editor.draft();
        boolean hold = changesOnly();
        boolean saving = !this.sending && draftSaving();
        List<CreatorEditorScreen.Blocker> blockers = this.sending || saving ? List.of() : this.editor.publishBlockers();

        // 1. The check: what holds it back, each a link to its fix, or one line once nothing does
        if (saving) {
            shown.line(tr("autosave.saving"), Theme.MUTED);
        } else if (!blockers.isEmpty()) {
            for (CreatorEditorScreen.Blocker blocker : blockers) {
                shown.line(blocker.text(), Theme.RED, () -> {
                    shown.close();
                    blocker.fix().run();
                }).glyph(PixelIcon.CLOSE);
            }
        } else {
            int inDraw = inDraw(draft);
            shown.line(inDraw == 1 ? tr("publish.ready_one") : tr("publish.ready", inDraw), Theme.MUTED)
                    .glyph(PixelIcon.CHECK);
        }

        // 2. What a draft leaves out, or where a published collection's changes are
        if (hold) {
            Component summary = changesSummary();
            if (summary != null) {
                shown.line(summary, Theme.MUTED);
            }
        } else {
            Component unfinished = unfinished(draft);
            if (unfinished != null) {
                shown.line(unfinished, Theme.MUTED);
            }
        }

        // 3. Where players find it
        shown.line(hold ? tr("publish.where.live") : tr(draft.plush() ? "publish.where.gacha" : "publish.where.claw"),
                Theme.MUTED);

        // 4. The publish on its way, or what the server said
        if (this.sending) {
            boolean publishing = hold || this.editor.saver().isPublishSent();
            shown.line(publishing ? tr("publish.publishing") : tr("autosave.saving"), Theme.MUTED);
        } else if (this.refusal != null) {
            shown.line(this.refusal, Theme.RED);
        }

        this.confirmButton.setLabel(confirmLabel());
        // Words too wide for the row in the pixel font, as a long translation's, go in Minecraft's
        // font rather than shrinking out of sight
        int room = this.confirmButton.bounds().width();
        this.confirmButton.setPlainLabel(room > 0 && this.confirmButton.pixelWidth() > room);
        // The blockers listed above decide it, worked out once for this layout
        this.confirmButton.disable(this.sending ? tr("editor.saving") : saving ? tr("autosave.saving")
                : this.editor.publishProblem(blockers));
        Component busy = this.sending ? tr("editor.saving") : null;
        this.tryButton.disable(busy);
        this.laterButton.disable(busy);
        shown.hold(this.sending);
    }

    // The figures in the draw: complete and not retired
    private static int inDraw(CreatorDraft draft) {
        int count = 0;
        for (FigureDraft figure : draft.figures()) {
            if (figure.enabled() && figure.isComplete()) {
                count++;
            }
        }
        return count;
    }

    // "Calabacín still misses a file and is left out", or null while every figure is complete
    @Nullable
    private static Component unfinished(CreatorDraft draft) {
        FigureDraft first = null;
        int count = 0;
        for (FigureDraft figure : draft.figures()) {
            if (!figure.isComplete()) {
                count++;
                first = first != null ? first : figure;
            }
        }
        if (first == null) {
            return null;
        }
        String name = first.name().isBlank() ? first.id() : first.name();
        return count == 1 ? tr("publish.check.unfinished_one", name) : tr("publish.check.unfinished", count);
    }

    /**
     * "4 changes: name, 2 figures, order": what a published collection's changes are, each
     * collection field once, the figures touched counted once each whatever changed in them, and
     * the order; the count is of those. A change with no word of its own is named by its tab.
     */
    @Nullable
    private Component changesSummary() {
        List<String> changes = this.editor.changes();
        if (changes.isEmpty()) {
            return null;
        }
        Set<String> words = new LinkedHashSet<>();
        Set<String> figures = new HashSet<>();
        boolean order = false;
        for (String key : changes) {
            if (key.startsWith(CreatorDraft.FIGURE)) {
                int end = key.indexOf('/', CreatorDraft.FIGURE.length());
                figures.add(end < 0 ? key : key.substring(0, end));
                continue;
            }
            if (key.equals(CreatorDraft.ORDER)) {
                order = true;
                continue;
            }
            words.add(switch (key) {
                case CreatorDraft.NAME -> "publish.change.name";
                case CreatorDraft.AUTHOR -> "publish.change.author";
                case CreatorDraft.AUTHOR_URL -> "publish.change.link";
                case CreatorDraft.COLOUR -> "publish.change.colour";
                case CreatorDraft.BOX -> "publish.change.box";
                case CreatorDraft.LOGO, CreatorDraft.LOGO_LAYOUT -> "publish.change.logo";
                case CreatorDraft.PLUSH -> "publish.change.plush";
                case CreatorDraft.HOLOGRAPHIC -> "publish.change.holographic";
                case CreatorDraft.RARITY_WEIGHTS -> "publish.change.rarity";
                default -> switch (CreatorEditorScreen.tabOf(key)) {
                    case FIGURES -> "side.tab.figures";
                    case LOOK -> "side.tab.look";
                    case OPTIONS -> "side.tab.options";
                };
            });
        }
        List<Component> parts = new ArrayList<>();
        for (String word : words) {
            parts.add(tr(word));
        }
        if (!figures.isEmpty()) {
            parts.add(figures.size() == 1 ? tr("publish.change.figures_one")
                    : tr("publish.change.figures", figures.size()));
        }
        if (order) {
            parts.add(tr("publish.change.order"));
        }
        Component what = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                // Each language's own list separator, such as "、" in Chinese and Japanese
                what = Component.empty().append(what).append(tr("publish.change_separator"));
            }
            what = Component.empty().append(what).append(parts.get(i));
        }
        int count = words.size() + figures.size() + (order ? 1 : 0);
        return count == 1 ? tr("publish.changes_summary_one", what) : tr("publish.changes_summary", count, what);
    }

    // The buttons

    private void tryDraw() {
        this.popover.close();
        this.editor.openTestDraw(this.editor::openPublish);
    }

    private void confirm() {
        this.refusal = null;
        this.sending = true;
        boolean hold = changesOnly();
        String name = this.editor.draft().name();
        if (hold) {
            this.editor.publishChanges(false, failure -> landed(failure, true, name));
        } else {
            this.editor.publish(failure -> landed(failure, false, name));
        }
        this.popover.refresh();
    }

    // How the publish ended: closed with a toast once published, else the refusal in red
    private void landed(@Nullable Component failure, boolean changesOnly, String name) {
        this.sending = false;
        this.popover.hold(false);
        if (failure == null) {
            this.popover.close();
            this.editor.published(changesOnly, name);
            return;
        }
        this.refusal = failure;
        if (this.popover.isOpen()) {
            this.popover.refresh();
        } else {
            UiToast.show(failure);
        }
    }
}
