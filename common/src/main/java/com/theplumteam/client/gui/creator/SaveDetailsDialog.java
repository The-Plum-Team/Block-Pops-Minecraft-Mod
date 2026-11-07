package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.worldcontent.AutoSaver;
import com.theplumteam.client.worldcontent.CreatorSave;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The editor's dialogs about saving: the details of a save that failed or of changes that cannot
 * be saved as they are, each reason on a line of its own, with "Retry" for a refusal and "Show"
 * for the first held change; and the questions asked on leaving, while some of a draft's changes
 * cannot be saved, or while a published collection's changes are not published.
 */
final class SaveDetailsDialog {
    // Wide enough for a figure's name and the parts it misses on one line
    private static final int WIDTH = 320;
    // Wide enough for two or three buttons whose long labels stay legible
    private static final int WIDE = 380;
    // The most lines the reasons take, so the buttons stay on the smallest canvas
    private static final int MAX_LINES = 6;
    // The gap a dialog leaves between its buttons
    private static final int BUTTON_GAP = 8;

    private SaveDetailsDialog() {
    }

    /**
     * What the server said when it refused the save, or why it failed, and every change held
     * back: "Retry" sends them again at once, "Show" goes to the first change held.
     */
    static void open(CreatorEditorScreen editor, AutoSaver.Status status) {
        List<Component> lines = new ArrayList<>(status.failures());
        CreatorSave.Held first = null;
        for (CreatorSave.Held held : status.held()) {
            if (held.reason() != CreatorSave.Reason.REFUSED) {
                lines.add(held.detail());
            }
            if (first == null && (held.figureId() != null || held.reason() != CreatorSave.Reason.REFUSED)) {
                first = held;
            }
        }
        UiModal dialog = new UiModal(tr("autosave.details_title"), Component.empty()).width(WIDTH)
                .accent(DialogFrame.Accent.DANGER, Theme.ICON_SAVE);
        addLines(editor, dialog, lines, Theme.TEXT_SOFT);
        dialog.cancelButton(tr("done"));
        CreatorSave.Held shown = first;
        if (shown != null) {
            dialog.button(tr("autosave.show"), UiButton.Style.SECONDARY, () -> editor.revealHeld(shown));
        }
        dialog.button(tr("autosave.retry"), UiButton.Style.GREEN, () -> editor.saver().retry());
        editor.openModal(dialog);
    }

    /**
     * Asks before leaving a draft collection with changes that cannot be saved as they are: the
     * reasons, then "Keep editing", which Enter and Escape choose, and "Leave without them", which
     * puts them back as the server has them. The leave dialogs order their buttons alike: the
     * safe one first, then the one that discards, then the green one.
     */
    static void openLeave(CreatorEditorScreen editor, List<CreatorSave.Held> held, Runnable leaveWithout) {
        List<Component> lines = new ArrayList<>();
        for (CreatorSave.Held unit : held) {
            lines.add(unit.detail());
        }
        UiModal dialog = new UiModal(tr("autosave.leave_title"), Component.empty()).width(WIDE)
                .accent(DialogFrame.Accent.DANGER, Theme.ICON_WARNING);
        addLines(editor, dialog, lines, Theme.TEXT_SOFT, WIDE);
        UiButton keep = dialog.cancelButton(tr("autosave.leave_keep"));
        dialog.button(tr("autosave.leave_drop"), UiButton.Style.DANGER, leaveWithout);
        dialog.defaultButton(keep);
        editor.openModal(dialog);
    }

    /**
     * Asks before leaving a published or archived collection with changes that are not
     * published: "Keep editing" stays, as Enter and Escape do; "Discard" drops them and leaves;
     * "Publish changes" sends them and leaves once they are published, the dialog saying so
     * meanwhile. Every button keeps its whole label: the dialog widens, up to the screen's width,
     * until the row of three fits, and where it still does not, their labels are drawn in
     * Minecraft's narrower font, as the sidebar's foot draws Publish changes.
     *
     * @param refusal why the last try to publish them failed, in red under the question; or null
     * @param problem why they cannot be published now, which disables the button; or null
     */
    static void openLeaveUnpublished(CreatorEditorScreen editor, int changes, @Nullable Component refusal,
                                     @Nullable Component problem, Runnable publish, Runnable discard) {
        UiModal dialog = new UiModal(tr("editor.leave_title"),
                changes == 1 ? tr("editor.leave_body_one") : tr("editor.leave_body", changes))
                .accent(DialogFrame.Accent.DANGER, Theme.ICON_SAVE);
        UiButton keep = dialog.cancelButton(tr("autosave.leave_keep"));
        UiButton discardButton = dialog.button(tr("editor.discard"), UiButton.Style.DANGER, discard);
        UiButton publishButton = dialog.button(tr("publish.button_changes"), UiButton.Style.GREEN, () -> {
            openPublishing(editor);
            publish.run();
        });
        // The dialog's buttons are as wide as the widest: wide enough for three of those, or the screen
        UiButton[] row = {keep, discardButton, publishButton};
        int widest = 0;
        for (UiButton button : row) {
            widest = Math.max(widest, button.naturalWidth());
        }
        int needed = row.length * widest + (row.length - 1) * BUTTON_GAP + 2 * DialogFrame.PADDING;
        int most = Math.max(160, editor.width - 2 * Theme.SPACE_S);
        int width = Math.min(most, Math.max(WIDE, needed));
        dialog.width(width);
        if (needed > width) {
            for (UiButton button : row) {
                button.setPlainLabel(true);
            }
        }
        if (refusal != null) {
            addLines(editor, dialog, List.of(refusal), Theme.RED, width);
        }
        publishButton.disable(problem);
        dialog.defaultButton(keep);
        editor.openModal(dialog);
    }

    // Says the changes are being published until they are, after which the editor leaves; "Keep
    // editing" stays once they are published
    private static void openPublishing(CreatorEditorScreen editor) {
        UiModal dialog = new UiModal(tr("editor.leave_title"), tr("publish.publishing"))
                .accent(DialogFrame.Accent.CREATE, Theme.ICON_SAVE)
                .onCancel(editor::keepEditing);
        dialog.cancelButton(tr("autosave.leave_keep"));
        editor.openModal(dialog);
    }

    // The lines in a row of their own, as tall as they wrap to at the dialog's width, at most MAX_LINES
    private static void addLines(CreatorEditorScreen editor, UiModal dialog, List<Component> lines, int colour) {
        addLines(editor, dialog, lines, colour, WIDTH);
    }

    private static void addLines(CreatorEditorScreen editor, UiModal dialog, List<Component> lines, int colour,
                                 int dialogWidth) {
        if (lines.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int inner = Math.max(160, Math.min(dialogWidth, editor.width - 2 * Theme.SPACE_S)) - 2 * DialogFrame.PADDING;
        Lines node = new Lines(lines, colour);
        dialog.node(node, node.height(font, inner));
    }

    /** Sentences one under the other, each wrapped to the row, cut after {@value #MAX_LINES} lines. */
    private static final class Lines extends UiNode.Box {
        private final List<Component> lines;
        private final int colour;

        Lines(List<Component> lines, int colour) {
            this.lines = List.copyOf(lines);
            this.colour = colour;
        }

        int height(Font font, int forWidth) {
            int count = 0;
            for (Component line : this.lines) {
                count += Math.max(1, Draw.wrap(font, line.getString(), forWidth).size());
            }
            return Math.min(MAX_LINES, count) * Theme.LINE_HEIGHT - 2;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int top = y;
            int left = MAX_LINES;
            for (Component line : this.lines) {
                if (left <= 0) {
                    return;
                }
                int count = Math.min(left, Math.max(1, Draw.wrap(font, line.getString(), width).size()));
                Draw.lines(graphics, font, line.getString(), x, top, width, count, this.colour);
                top += count * Theme.LINE_HEIGHT;
                left -= count;
            }
        }
    }
}
