package com.theplumteam.client.gui;

import com.theplumteam.client.ClientPacketNetworking;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.DialogScreen;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.StyledButton;
import com.theplumteam.client.gui.style.UiText;
import com.theplumteam.client.support.KofiLinkClient;
import com.theplumteam.client.upload.MojangJoin;
import com.theplumteam.network.RecheckSupporterPacket;
import com.theplumteam.util.E2EDeterminism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The dialog where a player who paid on Ko-fi links that payment to their Minecraft account,
 * without leaving the game: they type the email they paid with ({@link KofiLinkClient}). Once
 * linked, the server is asked to check the player again, so the special plushes unlock without
 * waiting for its kept answer to run out.
 *
 * <p>It opens over the machine menu after the supporter button opened Ko-fi, and from the chat's
 * "Link my account" ({@code /blockpops supporter link}).
 */
public class SupporterLinkScreen extends DialogScreen {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;
    // The last step has only its message and one button
    private static final int DONE_HEIGHT = 130;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TEXT = 0xFFE8E4EE;
    private static final int MUTED = 0xFFB6B0C2;
    private static final int ERROR = 0xFFF2A0A8;
    private static final int OK = 0xFF8FE0A0;
    private static final Executor IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops-KofiLink");
        thread.setDaemon(true);
        return thread;
    });

    private enum Step { EMAIL, DONE }

    // Kept across rebuilds, as the window may change size while the player types
    private Step step = Step.EMAIL;
    private String email = "";
    private boolean busy;
    private Component status = Component.empty();
    private int statusColor = MUTED;
    @Nullable
    private KofiLinkClient.Linked linked;

    public SupporterLinkScreen(@Nullable Screen parent) {
        super(Component.translatable("blockpops.support.link.title"), parent);
    }

    /** Opens the dialog over the screen shown now, if that is a menu of this mod or none at all. */
    public static void openOverCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current instanceof SupporterLinkScreen) {
            return;
        }
        if (current == null || current instanceof net.minecraft.client.gui.screens.ChatScreen) {
            minecraft.setScreen(new SupporterLinkScreen(null));
        } else if (current instanceof CollectionSelectionScreen) {
            minecraft.setScreen(new SupporterLinkScreen(current));
        }
    }

    @Override
    protected DialogFrame.Accent accent() {
        return DialogFrame.Accent.COLLECTION;
    }

    @Override
    protected String[] icon() {
        return PixelIcon.STAR;
    }

    @Override
    protected int preferredWidth() {
        return WIDTH;
    }

    @Override
    protected int preferredHeight() {
        return step == Step.DONE ? DONE_HEIGHT : HEIGHT;
    }

    private int left() {
        return this.dialogX + DialogFrame.PADDING;
    }

    private int inner() {
        return this.dialogWidth - 2 * DialogFrame.PADDING;
    }

    private int buttonY() {
        return bodyBottom() - DialogFrame.PADDING - BUTTON_HEIGHT;
    }

    private int fieldY() {
        return buttonY() - 10 - FIELD_HEIGHT;
    }

    private int labelY() {
        return fieldY() - this.font.lineHeight - 3;
    }

    @Override
    protected void initDialog() {
        if (step == Step.DONE) {
            addButton(Component.translatable("blockpops.support.link.done"), this::onClose);
            return;
        }
        EditBox box = new EditBox(this.font, left(), fieldY(), inner(), FIELD_HEIGHT,
                Component.translatable("blockpops.support.link.email"));
        box.setMaxLength(KofiLinkClient.MAX_EMAIL_LENGTH);
        box.setValue(this.email);
        box.setHint(Component.translatable("blockpops.support.link.email_hint"));
        box.setResponder(value -> this.email = value);
        box.setEditable(!busy);
        this.addRenderableWidget(box);
        setFocused(box);
        addButton(Component.translatable("blockpops.support.link.link"), this::link);
    }

    // The green button at the dialog's foot
    private void addButton(Component label, Runnable action) {
        int width = Math.min(140, inner());
        StyledButton button = DialogFrame.button(this.dialogX + (this.dialogWidth - width) / 2, buttonY(), width,
                BUTTON_HEIGHT, label, pressed -> {
                    if (!busy) {
                        action.run();
                    }
                });
        button.tone(StyledButton.Tone.PRIMARY);
        button.active = !busy;
        this.addRenderableWidget(button);
    }

    // Enter links, as the green button does
    private boolean enter(int keyCode) {
        if ((keyCode != com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                && keyCode != com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) || step == Step.DONE) {
            return false;
        }
        if (!busy) {
            link();
        }
        return true;
    }

    @Override
    //? if >=26 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent bpEvent) {
        return enter(bpEvent.key()) || super.keyPressed(bpEvent);
    }
    *///? } else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return enter(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }
    //? }

    private void show(Component text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    private void setBusy(boolean busy, Component text) {
        this.busy = busy;
        show(text, MUTED);
        rebuildWidgets();
    }

    private static boolean offline() {
        return E2EDeterminism.ENABLED;
    }

    private void link() {
        String normalized = KofiLinkClient.normalizeEmail(this.email);
        if (normalized == null) {
            show(Component.translatable("blockpops.support.link.bad_email"), ERROR);
            return;
        }
        if (offline()) {
            show(Component.translatable("blockpops.support.link.error.network"), ERROR);
            return;
        }
        setBusy(true, Component.translatable("blockpops.support.link.linking"));
        KofiLinkClient client = KofiLinkClient.forGame(MojangJoin.forGame());
        IO.execute(() -> {
            try {
                KofiLinkClient.Linked result = client.link(normalized);
                // Asked whether the dialog is still open or not: the plushes should unlock either way
                Minecraft.getInstance().execute(SupporterLinkScreen::askServerToCheck);
                answer(() -> {
                    this.linked = result;
                    this.step = Step.DONE;
                    this.busy = false;
                    show(Component.translatable(result.supporter() ? "blockpops.support.link.linked"
                            : "blockpops.support.link.linked_expired", result.name()), result.supporter() ? OK : MUTED);
                    rebuildWidgets();
                });
            } catch (KofiLinkClient.LinkException e) {
                answer(() -> failed(e.failure()));
            }
        });
    }

    private static void askServerToCheck() {
        if (Minecraft.getInstance().getConnection() != null
                && ClientPacketNetworking.canServerReceive(RecheckSupporterPacket.ID)) {
            new RecheckSupporterPacket().sendToServer();
        }
    }

    // Runs on the client thread, and only while this dialog is still the screen shown
    private void answer(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen == this) {
                action.run();
            }
        });
    }

    private void failed(KofiLinkClient.Failure failure) {
        this.busy = false;
        show(Component.translatable("blockpops.support.link.error." + failure.name().toLowerCase(java.util.Locale.ROOT)), ERROR);
        rebuildWidgets();
    }

    @Override
    protected void renderDialog(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Component intro = step == Step.EMAIL
                ? Component.translatable("blockpops.support.link.intro", Minecraft.getInstance().getUser().getName())
                : Component.empty();
        int bottom = step == Step.DONE ? buttonY() - 8 : labelY() - 4;
        int y = paragraph(graphics, intro.getString(), bodyTop() + DialogFrame.PADDING, bottom, TEXT);
        // What happened, under the explanation
        paragraph(graphics, status.getString(), y == bodyTop() + DialogFrame.PADDING ? y : y + 4, bottom, statusColor);
        if (step != Step.DONE) {
            UiText.drawFitted(graphics, this.font, Component.translatable("blockpops.support.link.email").getString(),
                    left(), labelY(), inner(), MUTED);
        }
    }

    // Wrapped lines from y down to bottom at most; returns where the next text goes
    private int paragraph(GuiGraphics graphics, String text, int y, int bottom, int color) {
        if (text.isEmpty()) {
            return y;
        }
        List<String> lines = UiText.wrap(this.font, text, inner());
        for (String line : lines) {
            if (y + this.font.lineHeight > bottom) {
                break;
            }
            UiText.draw(graphics, this.font, line, left(), y, color);
            y += this.font.lineHeight + 2;
        }
        return y;
    }
}
