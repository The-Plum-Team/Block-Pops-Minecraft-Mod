package com.theplumteam.client.gui.style;

import com.mojang.blaze3d.systems.RenderSystem;
import com.theplumteam.client.gui.util.GuiPose;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A dialog drawn over the screen it opens from, as Quick Skin's dialogs are: the screen behind
 * stays visible under a dark veil, and the dialog is a {@link DialogFrame} centred on it, with an
 * X at the right end of its header that closes it back to that screen, as Escape does.
 *
 * <p>Over a menu the dialog takes the menu's own scale ({@link MenuGuiScale#follow}), so the menu
 * behind keeps its size; opened on its own it is drawn at the player's scale. The dialog is as
 * large as {@link #preferredWidth} and {@link #preferredHeight} ask, within the canvas.
 */
public abstract class DialogScreen extends Screen {
    /** Room the dialog leaves at the canvas's edges. */
    private static final int SCREEN_MARGIN = 8;
    private static final int VEIL = 0x80000000;

    @Nullable
    protected final Screen parent;
    protected int dialogX;
    protected int dialogY;
    protected int dialogWidth;
    protected int dialogHeight;

    protected DialogScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** The header's colour, which says what the dialog does. */
    protected abstract DialogFrame.Accent accent();

    /** The pixel icon before the title. */
    protected abstract String[] icon();

    protected int preferredWidth() {
        return 440;
    }

    protected int preferredHeight() {
        return 280;
    }

    /** The label of the header's X, which closes the dialog. */
    protected Component closeLabel() {
        return Component.translatable("blockpops.dialog.close");
    }

    /** Adds the dialog's widgets, once its bounds are known. */
    protected abstract void initDialog();

    /**
     * Draws the fills of the dialog's body (panels, hairlines, wells) over its frame and under its
     * widgets. Text goes in {@link #renderDialog}: before 1.21.6 text drawn under the widgets'
     * fills can end up behind them.
     */
    protected void renderBody(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Draws the dialog's own labels, after its widgets. */
    protected void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        if (this.minecraft != null) {
            MenuGuiScale.follow(this.minecraft, this, this.parent);
        }
        super.init();
        dialogWidth = Math.min(preferredWidth(), this.width - 2 * SCREEN_MARGIN);
        dialogHeight = Math.min(preferredHeight(), this.height - 2 * SCREEN_MARGIN);
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;
        addRenderableWidget(DialogCloseButton.forDialog(dialogX, dialogY, dialogWidth, closeLabel(),
                button -> onClose()));
        initDialog();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft != null && MenuGuiScale.reapply(this.minecraft, this)) {
            rebuildWidgets();
        }
    }

    /** The first row of the dialog's body, below its header. */
    protected int bodyTop() {
        return DialogFrame.bodyTop(dialogY);
    }

    /** The last row of the dialog's body, above its lip. */
    protected int bodyBottom() {
        return DialogFrame.bodyBottom(dialogY, dialogHeight);
    }

    //? if >=1.21 && <1.21.6 {
    /*@Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The screen behind is drawn in render(); 1.21 to 1.21.5 would blur it a second time
    }
    *///? }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The screen behind, without the mouse so nothing on it lights up
        if (this.parent != null) {
            this.parent.render(graphics, -1, -1, partialTick);
        }
        GuiPose.flush(graphics);

        // The dialog above it. 1.21.5 removed the global depth state, so the dialog is pushed
        // forward in the pose instead; 1.21.6's pose is two-dimensional and orders by stratum.
        //? if >=1.21.6 {
        /*GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        *///? } elif >=1.21.5 {
        /*graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        *///? } elif >=1.21.2 {
        /*RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256);
        *///? } else {
        RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.clear(256, false);
        //? }

        graphics.fill(0, 0, this.width, this.height, VEIL);
        DialogFrame.draw(graphics, dialogX, dialogY, dialogWidth, dialogHeight, accent(), icon(), this.title);
        renderBody(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderDialog(graphics, mouseX, mouseY, partialTick);

        //? if >=1.21.5 {
        /*GuiPose.pop(graphics);
        *///? }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (this.minecraft != null) {
            MenuGuiScale.leave(this.minecraft, this);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
