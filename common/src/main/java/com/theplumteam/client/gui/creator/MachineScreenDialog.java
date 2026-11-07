package com.theplumteam.client.gui.creator;

import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMachineReplica;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.worldcontent.CreatorPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The claw machine's own screen as players will see the collection, in a dialog over the editor:
 * the look's "View" menu opens it, since the look's preview is far too narrow for the machine. It
 * shows the editor's preview of the draft through a {@link UiMachineReplica} with every figure
 * discovered, its grid fitted to the dialog as the machine fits its own to a small window, never
 * scaled or scrolled; where even that has no room, the dialog says so. While it is open the editor
 * behind it draws none of its models, as the machine draws its own.
 */
final class MachineScreenDialog {
    private static final int MAX_WIDTH = 560;
    private static final int MAX_HEIGHT = 360;
    // The dialog's header, border and lip, its padding, and its row of buttons, around the one row it holds
    private static final int FRAME = DialogFrame.HEADER_HEIGHT + 2 + Surfaces.LIP_HEIGHT;
    private static final int BUTTONS = 10 + 20;

    private final CreatorEditorScreen editor;
    private final UiModal modal;
    private final UiMachineReplica replica = new UiMachineReplica();
    private boolean closed;

    private MachineScreenDialog(CreatorEditorScreen editor) {
        this.editor = editor;
        int width = Math.min(editor.width - 2 * Theme.SPACE_S, MAX_WIDTH);
        int height = Math.min(editor.height - 2 * Theme.SPACE_S, MAX_HEIGHT);
        this.modal = new UiModal(tr("preview.screen"), Component.empty()).width(width)
                .accent(DialogFrame.Accent.SETTINGS, PixelIcon.PALETTE)
                .onCancel(this::closed);
        this.replica.setCollection(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT));
        this.replica.setSourceId(editor.draft().collectionId());
        this.replica.setDiscovered(key -> true);
        this.replica.setFittedGrid(true);
        this.modal.node(new Body(), Math.max(0, height - FRAME - 2 * DialogFrame.PADDING - BUTTONS));
        this.modal.cancelButton(tr("publish.close"));
    }

    /** Opens the machine's screen over the editor, which draws none of its models meanwhile. */
    static MachineScreenDialog open(CreatorEditorScreen editor) {
        MachineScreenDialog dialog = new MachineScreenDialog(editor);
        editor.openModal(dialog.modal);
        editor.coverModels(true);
        return dialog;
    }

    boolean isOpen() {
        return this.modal.isOpen();
    }

    /**
     * The dialog closed, whatever closed it, its button, Escape or another dialog opened over it:
     * the editor draws its models again. Safe to call more than once.
     */
    void closed() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.replica.close();
        this.editor.coverModels(false);
    }

    /** Closes the dialog, as when the editor behind it closes. */
    void close() {
        this.modal.close();
        closed();
    }

    /** The machine's screen, or why it cannot show here. */
    private final class Body extends UiNode.Box {
        @Override
        public Box at(int x, int y, int width, int height) {
            super.at(x, y, width, height);
            replica.at(x, y, width, height);
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (replica.showsGrid()) {
                replica.draw(graphics, mouseX, mouseY, partialTick);
                return;
            }
            Draw.lines(graphics, Minecraft.getInstance().font, tr("publish.too_narrow").getString(), x + 8,
                    y + height / 2 - Theme.LINE_HEIGHT, width - 16, 2, Theme.MUTED);
        }

        @Override
        public boolean click(UiInput.Click click) {
            return replica.showsGrid() && replica.click(click);
        }

        @Override
        public boolean drag(UiInput.Click click, double dragX, double dragY) {
            return replica.showsGrid() && replica.drag(click, dragX, dragY);
        }

        @Override
        public boolean release(UiInput.Click click) {
            return replica.release(click);
        }

        @Override
        public boolean scroll(double mouseX, double mouseY, double amount) {
            return replica.showsGrid() && replica.isMouseOver(mouseX, mouseY) && replica.scroll(mouseX, mouseY, amount);
        }

        @Override
        public void tick() {
            replica.tick();
        }
    }
}
