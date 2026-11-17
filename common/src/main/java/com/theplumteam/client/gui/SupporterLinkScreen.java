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
 * without leaving the game: they type the email they paid with, then the six-digit code the
 * supporter service emails to it ({@link KofiLinkClient}). While the service sends no codes the
 * second step is skipped. Once linked, the server is asked to check the player again, so the
 * special plushes unlock without waiting for its kept answer to run out.
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
    // The service sends a new code a minute after the last at the earliest
    private static final long RESEND_MILLIS = 60_000;
    private static final Executor IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops-KofiLink");
        thread.setDaemon(true);
        return thread;
    });

    private enum Step { EMAIL, CODE, DONE }

    // Kept across rebuilds, as the window may change size while the player types
    private Step step = Step.EMAIL;
    private String email = "";
    private String code = "";
    private boolean busy;
    private long codeSentAt;
    private Component status = Component.empty();
    private int statusColor = MUTED;
    @Nullable
    private KofiLinkClient.Linked linked;

    @Nullable
    private EditBox field;
    @Nullable
    private StyledButton resend;

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
        this.field = null;
        this.resend = null;
        if (step == Step.DONE) {
            addButton(Component.translatable("blockpops.support.link.done"), true, 0, 1, this::onClose);
            return;
        }
        boolean codeStep = step == Step.CODE;
        EditBox box = new EditBox(this.font, left(), fieldY(), inner(), FIELD_HEIGHT,
                Component.translatable(codeStep ? "blockpops.support.link.code" : "blockpops.support.link.email"));
        box.setMaxLength(codeStep ? KofiLinkClient.CODE_LENGTH : KofiLinkClient.MAX_EMAIL_LENGTH);
        box.setValue(codeStep ? this.code : this.email);
        box.setHint(Component.translatable(codeStep ? "blockpops.support.link.code_hint" : "blockpops.support.link.email_hint"));
        box.setResponder(value -> {
            if (step == Step.CODE) {
                this.code = value;
            } else {
                this.email = value;
            }
        });
        box.setEditable(!busy);
        this.field = box;
        this.addRenderableWidget(box);
        setFocused(box);

        if (codeStep) {
            addButton(Component.translatable("blockpops.support.link.back"), false, 0, 3, () -> {
                this.step = Step.EMAIL;
                this.code = "";
                show(Component.empty(), MUTED);
                rebuildWidgets();
            });
            this.resend = addButton(Component.translatable("blockpops.support.link.resend"), false, 1, 3, this::requestCode);
            this.resend.active = canResend();
            addButton(Component.translatable("blockpops.support.link.link"), true, 2, 3, this::link);
        } else {
            addButton(Component.translatable("blockpops.support.link.continue"), true, 0, 1, this::requestCode);
        }
    }

    // A button in the row at the dialog's foot: the slot-th of `slots` equal ones
    private StyledButton addButton(Component label, boolean primary, int slot, int slots, Runnable action) {
        int gap = 6;
        int width = slots == 1 ? Math.min(140, inner()) : (inner() - gap * (slots - 1)) / slots;
        int x = slots == 1 ? this.dialogX + (this.dialogWidth - width) / 2 : left() + slot * (width + gap);
        StyledButton button = DialogFrame.button(x, buttonY(), width, BUTTON_HEIGHT, label, pressed -> {
            if (!busy) {
                action.run();
            }
        });
        if (primary) {
            button.tone(StyledButton.Tone.PRIMARY);
        }
        button.active = !busy;
        this.addRenderableWidget(button);
        return button;
    }

    private boolean canResend() {
        return !busy && System.currentTimeMillis() - codeSentAt >= RESEND_MILLIS;
    }

    @Override
    public void tick() {
        super.tick();
        // "Send again" wakes up once the minute is over
        if (this.resend != null) {
            this.resend.active = canResend();
        }
    }

    // Enter takes the step's main action, as its green button does
    private boolean enter(int keyCode) {
        if ((keyCode != com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                && keyCode != com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) || step == Step.DONE) {
            return false;
        }
        if (!busy) {
            if (step == Step.CODE) {
                link();
            } else {
                requestCode();
            }
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

    private void requestCode() {
        String normalized = KofiLinkClient.normalizeEmail(this.email);
        if (normalized == null) {
            show(Component.translatable("blockpops.support.link.bad_email"), ERROR);
            return;
        }
        if (offline()) {
            show(Component.translatable("blockpops.support.link.error.network"), ERROR);
            return;
        }
        setBusy(true, Component.translatable("blockpops.support.link.checking"));
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        KofiLinkClient client = KofiLinkClient.forGame(MojangJoin.forGame());
        IO.execute(() -> {
            try {
                boolean codes = client.requestCode(normalized, lang == null ? "en_us" : lang);
                answer(() -> {
                    if (codes) {
                        this.step = Step.CODE;
                        this.code = "";
                        this.codeSentAt = System.currentTimeMillis();
                        this.busy = false;
                        show(Component.translatable("blockpops.support.link.sent", normalized), MUTED);
                        rebuildWidgets();
                    } else {
                        // The service sends no codes: the email alone links
                        this.busy = false;
                        linkWith(normalized, "");
                    }
                });
            } catch (KofiLinkClient.LinkException e) {
                answer(() -> failed(e.failure()));
            }
        });
    }

    private void link() {
        String normalized = KofiLinkClient.normalizeEmail(this.email);
        String typed = this.code.trim();
        if (normalized == null) {
            this.step = Step.EMAIL;
            show(Component.translatable("blockpops.support.link.bad_email"), ERROR);
            rebuildWidgets();
            return;
        }
        if (!KofiLinkClient.isCode(typed)) {
            show(Component.translatable("blockpops.support.link.bad_code"), ERROR);
            return;
        }
        linkWith(normalized, typed);
    }

    private void linkWith(String normalized, String typedCode) {
        setBusy(true, Component.translatable("blockpops.support.link.linking"));
        KofiLinkClient client = KofiLinkClient.forGame(MojangJoin.forGame());
        IO.execute(() -> {
            try {
                KofiLinkClient.Linked result = client.link(normalized, typedCode);
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
        if (failure == KofiLinkClient.Failure.CODE_EXPIRED || failure == KofiLinkClient.Failure.CODE_MISMATCH) {
            // A new code is needed: the button to ask for one works at once
            this.codeSentAt = 0;
        }
        show(Component.translatable("blockpops.support.link.error." + failure.name().toLowerCase(java.util.Locale.ROOT)), ERROR);
        rebuildWidgets();
    }

    @Override
    protected void renderDialog(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Component intro = switch (step) {
            case EMAIL -> Component.translatable("blockpops.support.link.intro", Minecraft.getInstance().getUser().getName());
            case CODE -> Component.translatable("blockpops.support.link.code_intro");
            case DONE -> Component.empty();
        };
        int bottom = step == Step.DONE ? buttonY() - 8 : labelY() - 4;
        int y = paragraph(graphics, intro.getString(), bodyTop() + DialogFrame.PADDING, bottom, TEXT);
        // What happened, under the explanation
        paragraph(graphics, status.getString(), y == bodyTop() + DialogFrame.PADDING ? y : y + 4, bottom, statusColor);
        if (step != Step.DONE) {
            Component label = Component.translatable(step == Step.CODE ? "blockpops.support.link.code" : "blockpops.support.link.email");
            UiText.drawFitted(graphics, this.font, label.getString(), left(), labelY(), inner(), MUTED);
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
