package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiCheckbox;
import com.theplumteam.client.gui.creator.ui.UiField;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.worldcontent.CreatorDraft;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * Who made the collection, edited in a dialog over the editor from the options' "Author" row: the
 * author's name, a box that fills it with the operator's own player name, and an optional link to
 * the author's page, which players can open from the collection. "Done" puts both into the draft
 * as one change, which Ctrl+Z takes back whole; "Cancel" leaves the draft as it was. Done is
 * disabled, and the red line under the fields says why, while the name holds control characters
 * or the link is not one the server takes ({@link PublishCheck#authorProblem}).
 */
final class AuthorDialog {
    private static final int WIDTH = 320;

    private final CreatorEditorScreen editor;
    private final UiModal modal;
    private final EditBox authorBox;
    // Made after the author's field, which ticks it as the name is typed
    @Nullable
    private UiCheckbox playerName;
    @Nullable
    private UiButton done;
    private String author;
    private String link;

    private AuthorDialog(CreatorEditorScreen editor) {
        this.editor = editor;
        CreatorDraft draft = editor.draft();
        this.author = draft.author();
        this.link = draft.authorUrl();
        this.modal = new UiModal(tr("author.title"), Component.empty())
                .width(Math.min(WIDTH, editor.width - 2 * Theme.SPACE_S))
                .accent(DialogFrame.Accent.EDIT, PixelIcon.PLAYER);
        this.authorBox = this.modal.field(tr("author"), WorldContentLimits.MAX_AUTHOR_LENGTH, this.author,
                tr("wizard.author_hint"), value -> {
                    this.author = value;
                    if (this.playerName != null) {
                        this.playerName.setChecked(isPlayerName(value));
                    }
                    check();
                });
        this.playerName = this.modal.node(new UiCheckbox(tr("identity.use_player_name"), isPlayerName(this.author),
                checked -> this.authorBox.setValue(checked ? playerName() : "")), UiCheckbox.BOX + 2);
        Font font = Minecraft.getInstance().font;
        UiField linkBox = new UiField(font, 0, 0, 100, UiField.HEIGHT, tr("identity.url"));
        linkBox.setMaxLength(WorldContentLimits.MAX_AUTHOR_URL_LENGTH);
        linkBox.setFittedHint(tr("identity.url_hint"));
        linkBox.setValue(this.link);
        // Its start shows until it has the keyboard, as the screen's own fields show theirs
        linkBox.showStart();
        linkBox.setResponder(value -> {
            this.link = value;
            check();
        });
        this.modal.ownField(linkBox);
        this.modal.node(new LabelledField(tr("identity.url"), linkBox), Theme.LINE_HEIGHT + UiField.HEIGHT);
        this.modal.node(new Problem(), Theme.LINE_HEIGHT);
        this.modal.cancelButton(tr("cancel"));
        this.done = this.modal.button(tr("author.done"), UiButton.Style.PRIMARY, this::apply);
        check();
    }

    /** Opens the dialog over the editor, with the draft's author and link. */
    static void open(CreatorEditorScreen editor) {
        AuthorDialog dialog = new AuthorDialog(editor);
        editor.openModal(dialog.modal);
    }

    /** Why the author or the link cannot be saved as typed, or null when both can. */
    @Nullable
    private Component problem() {
        if (!WorldCollectionRecord.isPlainText(this.author)) {
            return tr("wizard.author_rule");
        }
        if (!this.link.isEmpty() && !WorldCollectionRecord.isValidAuthorUrl(this.link)) {
            return tr("identity.url_rule");
        }
        return null;
    }

    private void check() {
        if (this.done != null) {
            this.done.disable(problem());
        }
    }

    // Both into the draft as one change, which a published collection keeps for Publish changes
    private void apply() {
        CreatorDraft draft = this.editor.draft();
        if (problem() != null || draft.author().equals(this.author) && draft.authorUrl().equals(this.link)) {
            return;
        }
        draft.change(CreatorDraft.AUTHOR, () -> {
            draft.setAuthor(this.author);
            draft.setAuthorUrl(this.link);
        });
        this.editor.changed();
        // Typed and confirmed: as a field the operator left, it is saved now rather than after a pause
        this.editor.saver().textLeft();
    }

    private static boolean isPlayerName(String author) {
        return !author.isEmpty() && author.equals(playerName());
    }

    private static String playerName() {
        return CreatorHomeScreen.cut(Minecraft.getInstance().getUser().getName(),
                WorldContentLimits.MAX_AUTHOR_LENGTH);
    }

    /** A text field of the dialog's own row, under its label, so it can follow the check box above it. */
    private static final class LabelledField extends UiNode.Box {
        private final Component label;
        private final UiField field;

        LabelledField(Component label, UiField field) {
            this.label = label;
            this.field = field;
        }

        @Override
        public Box at(int x, int y, int width, int height) {
            super.at(x, y, width, height);
            this.field.place(x, y + Theme.LINE_HEIGHT, width);
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            Draw.text(graphics, font, this.label.getString(), x, y + 1, width, Theme.MUTED);
            this.field.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Why "Done" is disabled, in red, or nothing. */
    private final class Problem extends UiNode.Box {
        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Component shown = problem();
            if (shown != null) {
                Draw.text(graphics, Minecraft.getInstance().font, shown.getString(), x, y + 1, width, Theme.RED);
            }
        }
    }
}
