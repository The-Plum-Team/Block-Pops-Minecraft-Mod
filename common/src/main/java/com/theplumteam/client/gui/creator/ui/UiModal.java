package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.gui.style.DialogCloseButton;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A dialog centred over a dimmed creator screen, drawn as the menus' dialogs are
 * ({@link DialogFrame}): a header strip in an accent that says what the dialog does, with a pixel
 * icon, the title in the pixel font and an X that cancels it; then a wrapped body, optional text
 * fields and other nodes, and a centred row of buttons with pixel labels. Unless told otherwise
 * ({@link #accent}), a dialog with a red button is a red one with a bin, and any other the
 * collections' purple. A node in a row gets the drags and the release of a press it took, so it
 * can be a slider or a colour square. Escape, the X, a click outside the card and a cancel button
 * all cancel it; Enter presses its last button, the main action. While it is open nothing below
 * it takes input, and {@link CreatorScreen} disables the screen's own vanilla widgets. Choosing a
 * button closes the dialog before its action runs, so the action may open another one.
 */
public class UiModal implements UiNode {
    private static final int PADDING = DialogFrame.PADDING;
    private static final int BUTTON_GAP = 8;
    private static final int MIN_BUTTON_WIDTH = 70;
    private static final int FIELD_HEIGHT = UiField.HEIGHT;
    private static final int MAX_BODY_LINES = 12;
    // The room a title leaves on the header's left, for the icon, and on its right, for the X
    private static final int TITLE_LEFT = 10;
    private static final int TITLE_RIGHT = 6 + DialogCloseButton.SIZE + 6;

    /** A text field with the label above it. */
    private record Field(Component label, EditBox box) {
    }

    /** A node the dialog places in its own row. */
    private record Row(UiNode.Box node, int height) {
    }

    private final Component title;
    private final Component body;
    private final List<Field> fields = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<UiButton> buttons = new ArrayList<>();
    // Text fields a row node places and draws itself, such as the colour picker's hex field
    private final List<EditBox> ownFields = new ArrayList<>();
    // The row node that took the last press, which gets its drags and its release
    @Nullable
    private UiNode.Box pressedRow;
    @Nullable
    private UiButton cancelButton;
    @Nullable
    private Runnable onCancel;
    private int preferredWidth = 300;
    @Nullable
    private DialogFrame.Accent accent;
    private String[] icon = PixelIcon.BOX;
    @Nullable
    private DialogCloseButton closeButton;
    @Nullable
    private CreatorScreen host;
    private int x;
    private int y;
    private int width;
    private int height;
    private List<String> bodyLines = List.of();

    public UiModal(Component title, Component body) {
        this.title = title;
        this.body = body;
    }

    /** The width the card would like, kept within the screen. */
    public UiModal width(int width) {
        this.preferredWidth = width;
        return this;
    }

    /**
     * The header's colour and icon, which say what the dialog does: DANGER to delete or archive,
     * EDIT to change something, CREATE to add or upload, SETTINGS for help or settings, and
     * COLLECTION for the collection itself.
     */
    public UiModal accent(DialogFrame.Accent accent, String[] icon) {
        this.accent = accent;
        this.icon = icon;
        return this;
    }

    /** What runs when the dialog is cancelled, by Escape, the X, a click outside or a cancel button. */
    public UiModal onCancel(Runnable onCancel) {
        this.onCancel = onCancel;
        return this;
    }

    /** Adds a button that closes the dialog and then runs its action. */
    public UiButton button(Component label, UiButton.Style style, Runnable action) {
        UiButton button = new UiButton(label, style, () -> {
            close();
            action.run();
        }).pixelLabel();
        buttons.add(button);
        return button;
    }

    /** Adds a button that cancels the dialog. */
    public UiButton cancelButton(Component label) {
        UiButton button = new UiButton(label, UiButton.Style.SECONDARY, this::cancel).pixelLabel();
        buttons.add(button);
        cancelButton = button;
        return button;
    }

    /**
     * Adds a text field under its label. Its maximum length is always set, because the
     * vanilla default is 32; the value goes in before the responder, which would otherwise see
     * it as an edit.
     */
    public EditBox field(Component label, int maxLength, String value, Component hint, Consumer<String> responder) {
        Font font = Minecraft.getInstance().font;
        EditBox box = new UiField(font, 0, 0, 100, FIELD_HEIGHT, label);
        box.setMaxLength(maxLength);
        box.setHint(hint);
        box.setValue(value);
        box.setResponder(responder);
        fields.add(new Field(label, box));
        return box;
    }

    /** Adds a node, such as a check box, in a row of its own of the given height. */
    public <T extends UiNode.Box> T node(T node, int height) {
        rows.add(new Row(node, height));
        return node;
    }

    /**
     * Adds a text field that a row node places and draws itself. The dialog only hands it
     * to the screen with its own fields, so it gets the keyboard and clicks.
     */
    public EditBox ownField(EditBox box) {
        ownFields.add(box);
        return box;
    }

    /** The dialog's text fields, which the screen adds as widgets while it is open. */
    List<EditBox> fields() {
        List<EditBox> boxes = new ArrayList<>();
        for (Field field : fields) {
            boxes.add(field.box());
        }
        boxes.addAll(ownFields);
        return boxes;
    }

    void attach(@Nullable CreatorScreen host) {
        this.host = host;
    }

    /** Closes the dialog without running its cancel action. */
    public void close() {
        if (host != null) {
            host.closeModal(this);
        }
    }

    /** Closes the dialog and runs its cancel action. */
    public void cancel() {
        close();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    /** The header's accent: the one asked for, else red when a button is, else purple. */
    private DialogFrame.Accent shownAccent() {
        if (accent != null) {
            return accent;
        }
        for (UiButton button : buttons) {
            if (button.style() == UiButton.Style.DANGER) {
                return DialogFrame.Accent.DANGER;
            }
        }
        return DialogFrame.Accent.COLLECTION;
    }

    private String[] shownIcon() {
        return accent == null && shownAccent() == DialogFrame.Accent.DANGER ? PixelIcon.BIN : icon;
    }

    /** Lays the card out in the middle of a screen of this size. */
    void place(int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        this.width = Math.max(160, Math.min(preferredWidth, screenWidth - 2 * Theme.SPACE_S));
        int inner = width - 2 * PADDING;
        String text = body.getString();
        this.bodyLines = text.isEmpty() ? List.of() : Draw.fit(font, text, inner, MAX_BODY_LINES);
        int contentHeight = bodyLines.size() * Theme.LINE_HEIGHT;
        for (Field ignored : fields) {
            contentHeight += 6 + Theme.LINE_HEIGHT + FIELD_HEIGHT;
        }
        for (Row row : rows) {
            contentHeight += 6 + row.height();
        }
        if (bodyLines.isEmpty() && (!fields.isEmpty() || !rows.isEmpty())) {
            // The first field or row starts the body, without the gap under a text
            contentHeight -= 6;
        }
        contentHeight += buttons.isEmpty() ? 0 : 10 + UiButton.HEIGHT;
        // The header above the body, and the border and lip below it
        int frame = DialogFrame.HEADER_HEIGHT + 2 + Surfaces.LIP_HEIGHT;
        this.height = Math.min(frame + contentHeight + 2 * PADDING, screenHeight - 2 * Theme.SPACE_S);
        this.x = (screenWidth - width) / 2;
        this.y = Math.max(Theme.SPACE_S, (screenHeight - height) / 2);
        this.closeButton = DialogCloseButton.forDialog(x, y, width, CommonComponents.GUI_CANCEL, button -> cancel());
        // Its tooltip is the creator's own, so it is drawn above the dialog
        this.closeButton.setTooltip(null);

        int top = DialogFrame.bodyTop(y) + PADDING + bodyLines.size() * Theme.LINE_HEIGHT;
        if (bodyLines.isEmpty()) {
            top -= 6;
        }
        for (Field field : fields) {
            top += 6 + Theme.LINE_HEIGHT;
            if (field.box() instanceof UiField well) {
                well.place(x + PADDING, top, inner);
            } else {
                field.box().setX(x + PADDING);
                field.box().setY(top);
                field.box().setWidth(inner);
            }
            top += FIELD_HEIGHT;
        }
        for (Row row : rows) {
            top += 6;
            row.node().at(x + PADDING, top, inner, row.height());
            top += row.height();
        }
        // The buttons side by side, as wide as the widest needs, centred under the body
        if (buttons.isEmpty()) {
            return;
        }
        int buttonWidth = MIN_BUTTON_WIDTH;
        for (UiButton button : buttons) {
            buttonWidth = Math.max(buttonWidth, button.naturalWidth());
        }
        int gaps = BUTTON_GAP * (buttons.size() - 1);
        buttonWidth = Math.max(20, Math.min(buttonWidth, (inner - gaps) / buttons.size()));
        int rowWidth = buttonWidth * buttons.size() + gaps;
        int left = x + (width - rowWidth) / 2;
        int buttonY = DialogFrame.bodyBottom(y, height) - PADDING - UiButton.HEIGHT;
        for (UiButton button : buttons) {
            button.at(left, buttonY, buttonWidth, UiButton.HEIGHT);
            left += buttonWidth + BUTTON_GAP;
        }
    }

    @Override
    public Rect bounds() {
        return new Rect(x, y, width, height);
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        graphics.fill(0, 0, screenWidth, screenHeight, Theme.MODAL_DIM);
        String[] shown = shownIcon();
        DialogFrame.draw(graphics, x, y, width, height, shownAccent(), shown, Component.empty());
        // The title, cut to the room between the icon and the X
        int titleX = x + TITLE_LEFT + PixelIcon.width(shown) + 6;
        float middle = (y + 2 + y + DialogFrame.HEADER_HEIGHT - 1) / 2 + 0.5f;
        Draw.heading(graphics, title, titleX, middle, Math.max(0, x + width - TITLE_RIGHT - titleX), 1.0f, Theme.TEXT);
        if (closeButton != null) {
            closeButton.render(graphics, mouseX, mouseY, partialTick);
            if (closeButton.isMouseOver(mouseX, mouseY)) {
                UiTooltip.set(closeButton.getMessage());
            }
        }

        int inner = width - 2 * PADDING;
        int top = DialogFrame.bodyTop(y) + PADDING;
        for (String line : bodyLines) {
            Draw.string(graphics, font, line, x + PADDING, top, Theme.TEXT_SOFT);
            top += Theme.LINE_HEIGHT;
        }
        if (bodyLines.isEmpty()) {
            top -= 6;
        }
        for (Field field : fields) {
            top += 6;
            Draw.text(graphics, font, field.label().getString(), x + PADDING, top + 1, inner, Theme.MUTED);
            top += Theme.LINE_HEIGHT + FIELD_HEIGHT;
            field.box().render(graphics, mouseX, mouseY, partialTick);
        }
        for (Row row : rows) {
            row.node().draw(graphics, mouseX, mouseY, partialTick);
        }
        for (UiButton button : buttons) {
            button.draw(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean click(UiInput.Click click) {
        pressedRow = null;
        if (!bounds().contains(click.x(), click.y())) {
            cancel();
            return true;
        }
        if (closeButton != null && click.left() && closeButton.isMouseOver(click.x(), click.y())) {
            UiSounds.play(ModSounds.CLICK);
            cancel();
            return true;
        }
        for (UiButton button : buttons) {
            if (button.click(click)) {
                return true;
            }
        }
        for (Row row : rows) {
            if (row.node().click(click)) {
                pressedRow = row.node();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean drag(UiInput.Click click, double dragX, double dragY) {
        return pressedRow != null && pressedRow.drag(click, dragX, dragY);
    }

    @Override
    public boolean release(UiInput.Click click) {
        UiNode.Box pressed = pressedRow;
        pressedRow = null;
        return pressed != null && pressed.release(click);
    }

    @Override
    public boolean key(UiInput.Key key) {
        if (key.escape()) {
            cancel();
            return true;
        }
        if (key.enter()) {
            for (int i = buttons.size() - 1; i >= 0; i--) {
                UiButton button = buttons.get(i);
                if (button != cancelButton) {
                    button.press();
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        for (Row row : rows) {
            row.node().tick();
        }
    }
}
