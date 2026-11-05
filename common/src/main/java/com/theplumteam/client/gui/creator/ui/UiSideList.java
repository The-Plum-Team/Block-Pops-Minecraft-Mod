package com.theplumteam.client.gui.creator.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.UiSounds;
import com.theplumteam.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The rows of a creator sidebar ({@link UiSideRow}), 20 high on a step of 22, in an area that
 * scrolls when they do not fit. It keeps which row is selected, by the rows' keys, so the screen
 * can build its rows again and the selection, the folds and the scroll stay; a row is drawn only
 * while it is in sight, so a long list costs no more than a short one. The area holds whole rows,
 * so the list at rest never ends on half a row, and a row the scrolling cuts at either edge fades
 * into the sidebar's face, which says there is more that way.
 *
 * <p>A {@link Fold} groups rows under a row of its own, as the archived collections under
 * "Archived": a click on that row, or Enter on it, folds and unfolds them, and its chevron
 * follows. While the list has the keyboard, Up, Down, Home and End move through the rows in
 * sight, selecting each with the tab sound (on a fold's row they only rest on it), Enter is the
 * row's activate, and the menu key or Shift+F10 opens the row's menu beside it. A locked list
 * draws every row in the disabled grey with the reason in its tooltip, and takes no clicks or keys.
 *
 * <p>Its boxes take the sidebar's share of the screen's models: the rows in sight get them from
 * the top, and the rest draw their colour's swatch.
 */
public class UiSideList extends UiScrollPane implements SidebarScreen.Budgeted {
    /** The distance from one row's top to the next. */
    public static final int STEP = 22;
    // The keys that open a row's menu, found by name: their numbers differ from 26.3, whose
    // keyboard calls the menu key "application"
    private static final List<Integer> MENU_KEYS = menuKeys();
    // How far a cut row fades into the sidebar's face, at most
    private static final int FADE = 8;
    private static final int FADE_FACE = Surfaces.PANEL_FACE;
    // The face with almost no alpha: from 1.21.6 a colour of alpha 0 draws nothing at all
    private static final int FADE_CLEAR = 0x01000000 | (Surfaces.PANEL_FACE & 0x00FFFFFF);

    /** A row and the fold it is in, if any; a fold's own row is its header. */
    private record Entry(UiSideRow row, @Nullable Fold fold, boolean header) {
    }

    /** Rows folded under a row of their own, as the archived collections under "Archived". */
    public final class Fold {
        private final UiSideRow header;
        @Nullable
        private final Consumer<Boolean> onToggle;
        private boolean open;

        private Fold(UiSideRow header, boolean open, @Nullable Consumer<Boolean> onToggle) {
            this.header = header;
            this.open = open;
            this.onToggle = onToggle;
            header.glyph(open ? UiSideRow.CHEVRON_DOWN : UiSideRow.CHEVRON_RIGHT);
        }

        /** Adds a row to the fold, after those added to it before. */
        public UiSideRow add(UiSideRow row) {
            entries.add(new Entry(row, this, false));
            adopt(row);
            return row;
        }

        public boolean isOpen() {
            return open;
        }

        /** Folds or unfolds the rows without telling the screen, as when it builds the list again. */
        public void setOpen(boolean open) {
            this.open = open;
            header.glyph(open ? UiSideRow.CHEVRON_DOWN : UiSideRow.CHEVRON_RIGHT);
        }

        private void toggle(boolean opened) {
            setOpen(opened);
            if (onToggle != null) {
                onToggle.accept(opened);
            }
        }
    }

    private static List<Integer> menuKeys() {
        List<Integer> keys = new ArrayList<>();
        for (String name : new String[]{"key.keyboard.menu", "key.keyboard.application"}) {
            try {
                InputConstants.Key key = InputConstants.getKey(name);
                if (key != InputConstants.UNKNOWN) {
                    keys.add(key.getValue());
                }
            } catch (RuntimeException unknown) {
                // A name the version's keyboard does not have
            }
        }
        return List.copyOf(keys);
    }

    private final List<Entry> entries = new ArrayList<>();
    // The rows laid out at the last layout, in order: the folded ones are left out
    private final List<UiSideRow> shown = new ArrayList<>();
    @Nullable
    private Object selectedKey;
    // The row the arrow keys move from, among those shown, which a fold's row can be
    @Nullable
    private UiSideRow cursorRow;
    @Nullable
    private Component lockReason;
    @Nullable
    private Component emptyText;
    private int grantedModels = Integer.MAX_VALUE;

    // Building

    /** Places the list, keeping only as much of the height as whole rows fill. */
    @Override
    public Box at(int x, int y, int width, int height) {
        int rows = Math.max(1, (height + STEP - UiSideRow.HEIGHT) / STEP);
        return super.at(x, y, width, Math.min(height, rows * STEP - (STEP - UiSideRow.HEIGHT)));
    }

    /** Adds a row after those added before it. */
    public UiSideRow add(UiSideRow row) {
        entries.add(new Entry(row, null, false));
        adopt(row);
        return row;
    }

    /**
     * Adds a row that folds the rows added to the fold it returns, open or not; {@code onToggle}
     * is told each time the operator opens or closes it, so the screen can keep that.
     */
    public Fold fold(UiSideRow header, boolean open, @Nullable Consumer<Boolean> onToggle) {
        Fold fold = new Fold(header, open, onToggle);
        entries.add(new Entry(header, fold, true));
        adopt(header);
        return fold;
    }

    private void adopt(UiSideRow row) {
        row.list = this;
        super.add(row);
    }

    /** Removes every row, keeping the selection and the scroll for the rows added next. */
    public void clearRows() {
        entries.clear();
        shown.clear();
        cursorRow = null;
        super.clear();
    }

    /** Whether the list shows no row now, every one folded away or none added. */
    public boolean isEmpty() {
        layout();
        return shown.isEmpty();
    }

    /** A faint line the list shows while it has no row in sight, as when a search matches none. */
    public void setEmptyText(@Nullable Component text) {
        this.emptyText = text;
    }

    // Selection

    /** Selects the row with this key without telling the screen; null selects none. */
    public void select(@Nullable Object key) {
        this.selectedKey = key;
        this.cursorRow = null;
    }

    /** The key of the row selected now, or null. */
    @Nullable
    public Object selectedKey() {
        return selectedKey;
    }

    /**
     * Shows a row: unfolds its fold, telling the screen, selects it without telling the screen,
     * and scrolls it into sight.
     */
    public void reveal(Object key) {
        for (Entry entry : entries) {
            if (!entry.header() && Objects.equals(entry.row().key(), key)) {
                if (entry.fold() != null && !entry.fold().isOpen()) {
                    entry.fold().toggle(true);
                }
                select(key);
                layout();
                reveal(entry.row().bounds().y(), entry.row().bounds().bottom());
                return;
            }
        }
    }

    /** Locks every row, saying why in their tooltips, or unlocks them again with null. */
    public void lock(@Nullable Component reason) {
        this.lockReason = reason;
    }

    public boolean locked() {
        return lockReason != null;
    }

    @Nullable
    Component lockReason() {
        return lockReason;
    }

    // A click or Enter on a row: a fold's row folds or unfolds, any other is selected
    void choose(UiSideRow row) {
        if (locked() || row.locked()) {
            return;
        }
        Entry entry = entryOf(row);
        UiSounds.play(ModSounds.TAB);
        if (entry != null && entry.header() && entry.fold() != null) {
            entry.fold().toggle(!entry.fold().isOpen());
            cursorRow = row;
            layout();
            return;
        }
        selectedKey = row.key();
        cursorRow = null;
        layout();
        row.runSelect();
    }

    // A double click or Enter on a row; a fold's row has nothing to open
    void activateRow(UiSideRow row) {
        Entry entry = entryOf(row);
        if (locked() || entry == null || entry.header()) {
            return;
        }
        row.activate();
    }

    @Nullable
    private Entry entryOf(UiSideRow row) {
        for (Entry entry : entries) {
            if (entry.row() == row) {
                return entry;
            }
        }
        return null;
    }

    // Layout

    // Places the rows in sight one step apart, narrower by the bar while they scroll
    private void layout() {
        shown.clear();
        for (Entry entry : entries) {
            boolean folded = entry.fold() != null && !entry.header() && !entry.fold().isOpen();
            entry.row().setVisible(!folded);
            if (!folded) {
                shown.add(entry.row());
            }
        }
        int rowWidth = shownHeight() > height ? contentWidth() : width;
        for (int i = 0; i < shown.size(); i++) {
            UiSideRow row = shown.get(i);
            row.at(x, y + i * STEP, rowWidth, UiSideRow.HEIGHT);
            boolean header = isHeader(row);
            row.setSelected(!header && selectedKey != null && Objects.equals(row.key(), selectedKey));
            row.setCursor(row == cursorRow);
        }
    }

    private int shownHeight() {
        return shown.isEmpty() ? 0 : shown.size() * STEP - (STEP - UiSideRow.HEIGHT);
    }

    private boolean isHeader(UiSideRow row) {
        Entry entry = entryOf(row);
        return entry != null && entry.header();
    }

    @Override
    protected int contentHeight() {
        return shownHeight();
    }

    // Drawing

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        super.draw(graphics, mouseX, mouseY, partialTick);
        if (visible()) {
            drawFades(graphics);
        }
    }

    // A row the scrolling cuts at either edge fades out over its part in sight, above its box: a
    // later stratum from 1.21.6, depth before it
    private void drawFades(GuiGraphics graphics) {
        int max = maxScroll();
        if (max == 0) {
            return;
        }
        int scrolled = scrollY();
        // How much of a row each edge cuts into: none when the edge falls in the gap between rows
        int topCut = scrolled % STEP;
        int topFade = topCut > 0 && topCut < UiSideRow.HEIGHT ? Math.min(FADE, UiSideRow.HEIGHT - topCut) : 0;
        int bottomCut = (scrolled + height) % STEP;
        int bottomFade = scrolled < max && bottomCut > 0 && bottomCut < UiSideRow.HEIGHT ? Math.min(FADE, bottomCut) : 0;
        if (topFade == 0 && bottomFade == 0) {
            return;
        }
        int right = x + contentWidth();
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        try {
            if (topFade > 0) {
                graphics.fillGradient(x, y, right, y + topFade, FADE_FACE, FADE_CLEAR);
            }
            if (bottomFade > 0) {
                graphics.fillGradient(x, y + height - bottomFade, right, y + height, FADE_CLEAR, FADE_FACE);
            }
        } finally {
            GuiPose.pop(graphics);
        }
    }

    @Override
    protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int top = y + scrollY();
        int bottom = top + height;
        int models = grantedModels;
        for (UiSideRow row : shown) {
            Rect at = row.bounds();
            if (at.bottom() <= top || at.y() >= bottom) {
                continue;
            }
            if (row.hasBox()) {
                row.allowModel(models > 0);
                models--;
            }
            row.draw(graphics, mouseX, mouseY, partialTick);
        }
        if (shown.isEmpty() && emptyText != null) {
            Font font = Minecraft.getInstance().font;
            Draw.lines(graphics, font, emptyText.getString(), x + 6, y + 6, Math.max(1, width - 12), 3, Theme.FAINT);
        }
    }

    @Override
    public void requestModels(SidebarScreen.ModelBudget budget) {
        // Folded rows count too, so unfolding them needs no new share of the budget
        int boxes = 0;
        for (Entry entry : entries) {
            if (entry.row().hasBox()) {
                boxes++;
            }
        }
        // No more than can be in sight at once, partly cut at both ends
        int inSight = height / STEP + 2;
        budget.request(SidebarScreen.ModelBudget.Rank.SIDEBAR, Math.min(boxes, inSight),
                granted -> this.grantedModels = granted);
    }

    // Input

    @Override
    public boolean click(UiInput.Click click) {
        layout();
        return super.click(click);
    }

    @Override
    protected boolean keyContent(UiInput.Key key) {
        layout();
        // Only the list that has the keyboard, so arrows meant for another control move no row
        if (shown.isEmpty() || locked() || !CreatorScreen.hasKeyboard(this)) {
            return super.keyContent(key);
        }
        UiSideRow at = cursor();
        int index = at == null ? -1 : shown.indexOf(at);
        int next = -1;
        if (key.is(InputConstants.KEY_DOWN)) {
            next = index < 0 ? 0 : Math.min(shown.size() - 1, index + 1);
        } else if (key.is(InputConstants.KEY_UP)) {
            next = index < 0 ? 0 : Math.max(0, index - 1);
        } else if (key.is(InputConstants.KEY_HOME)) {
            next = 0;
        } else if (key.is(InputConstants.KEY_END)) {
            next = shown.size() - 1;
        }
        if (next >= 0) {
            if (next != index) {
                moveTo(shown.get(next));
            }
            return true;
        }
        if (at == null) {
            return super.keyContent(key);
        }
        if (key.enter()) {
            if (isHeader(at)) {
                choose(at);
            } else {
                activateRow(at);
            }
            return true;
        }
        if (MENU_KEYS.contains(key.key()) || key.shift() && key.is(InputConstants.KEY_F10)) {
            if (!isHeader(at)) {
                Rect bounds = at.bounds();
                at.openMenu(UiScrollPane.onScreen(new Rect(bounds.x() + 8, bounds.bottom() - scrollY(), 0, 0)));
            }
            return true;
        }
        return super.keyContent(key);
    }

    // The row the keys move from: the one they rest on, else the selected one
    @Nullable
    private UiSideRow cursor() {
        if (cursorRow != null && shown.contains(cursorRow)) {
            return cursorRow;
        }
        for (UiSideRow row : shown) {
            if (row.selected()) {
                return row;
            }
        }
        return null;
    }

    // An arrow key onto a row selects it, as a click does; onto a fold's row it only rests there
    private void moveTo(UiSideRow row) {
        if (isHeader(row)) {
            cursorRow = row;
            layout();
        } else {
            choose(row);
        }
        reveal(row.bounds().y(), row.bounds().bottom());
    }
}
