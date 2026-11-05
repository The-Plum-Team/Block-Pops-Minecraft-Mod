package com.theplumteam.client.gui.creator;

import com.theplumteam.client.ClientServerSettings;
import com.theplumteam.client.gui.FigureDraw;
import com.theplumteam.client.gui.creator.ui.Draw;
import com.theplumteam.client.gui.creator.ui.SidebarScreen;
import com.theplumteam.client.gui.creator.ui.Theme;
import com.theplumteam.client.gui.creator.ui.UiButton;
import com.theplumteam.client.gui.creator.ui.UiFoldRow;
import com.theplumteam.client.gui.creator.ui.UiInput;
import com.theplumteam.client.gui.creator.ui.UiMachineReplica;
import com.theplumteam.client.gui.creator.ui.UiModal;
import com.theplumteam.client.gui.creator.ui.UiNode;
import com.theplumteam.client.gui.creator.ui.UiTooltip;
import com.theplumteam.client.gui.style.DialogFrame;
import com.theplumteam.client.gui.style.PixelIcon;
import com.theplumteam.client.gui.style.Surfaces;
import com.theplumteam.client.worldcontent.CreatorPreview;
import com.theplumteam.client.worldcontent.DrawSimulation;
import com.theplumteam.figure.DrawOdds;
import com.theplumteam.figure.DrawRules;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.Holographic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.theplumteam.client.gui.creator.ui.CreatorScreen.tr;

/**
 * The test draw: a dialog over the editor with the claw machine's own screen as it will show the
 * collection ({@link UiMachineReplica}), drawn from the editor's preview of the draft, where draws
 * are played as a regular token plays them. A draw runs as the dialog opens and again with
 * "Another pull": the machine's light runs over the figures and stops on one drawn by weight,
 * whose card is stamped as a test, and the figures it found show as discovered, as for a new
 * player. It comes out holographic as often as a real draw does, so never while the collection
 * turned that off. Nothing is spent, sent or handed out: the draws are made up on this computer.
 *
 * <p>"Odds" swaps the machine for how the draw works: every figure's chance along a bar, the
 * server's token rules, how many days of tokens completing the collection takes (worked out by
 * {@link DrawSimulation}), and how often each figure came out of a hundred draws. The replica is
 * never scaled or scrolled: it fits its grid to the dialog as the machine fits its own to a small
 * window (fewer, smaller cells, a narrower list of collections), and where even that has no room,
 * the dialog says so and keeps the odds. On a low window "Odds" goes into the row of buttons,
 * which leaves the machine the height of its row of cells.
 *
 * <p>Opened from the publish popover, its "Back" opens the popover again; opened alone, it has
 * "Close". While it is open the editor behind it draws none of its models, as the machine draws
 * its own ({@link SidebarScreen#coverModels}).
 */
final class TestDrawDialog {
    private static final int MAX_WIDTH = 560;
    private static final int MAX_HEIGHT = 300;
    // The dialog's header, border and lip, and its padding, around the one row it holds
    private static final int FRAME = DialogFrame.HEADER_HEIGHT + 2 + Surfaces.LIP_HEIGHT;
    private static final int GAP = 6;
    private static final int BAR_HEIGHT = 12;
    private static final int MIN_HISTOGRAM = 30;
    private static final int TEST_PULLS = 100;
    private static final int REGULAR = 0xFF5AA0FF;
    // Told apart along the chance bar; the colours repeat after eight figures
    private static final int[] SHARE_COLOURS = {0xFFF07C1E, 0xFF5A7CFF, 0xFF7AD06A, 0xFFC9A0E6, 0xFFA07AD0,
            0xFFFFB36A, 0xFF8FA6E6, 0xFFE6D35A};

    private final CreatorEditorScreen editor;
    @Nullable
    private final Runnable returnTo;
    private final UiModal modal;
    private final Body body = new Body();
    private final UiMachineReplica replica = new UiMachineReplica();
    private final UiFoldRow oddsRow;
    // "Odds" in the row of buttons, where a low dialog has no height for its row
    private final UiButton oddsButton;
    private final UiButton oddsBack;
    private final UiButton againButton;
    private final UiButton pullsButton;
    private final UiButton closeButton;
    private final ShareBar shareBar = new ShareBar();
    private final Histogram histogram = new Histogram();
    private final Random random = new Random();
    private final DrawSimulation simulation = new DrawSimulation();
    // The figures the test draws found, keyed as the grid asks: "collection:figure"
    private final Set<String> found = new HashSet<>();
    private boolean odds;
    private boolean pulled;
    private boolean closed;

    private TestDrawDialog(CreatorEditorScreen editor, @Nullable Runnable returnTo) {
        this.editor = editor;
        this.returnTo = returnTo;
        this.modal = new UiModal(tr("test_draw.title"), Component.empty())
                .width(Math.min(editor.width - 2 * Theme.SPACE_S, MAX_WIDTH))
                .accent(DialogFrame.Accent.COLLECTION, PixelIcon.STAR)
                .onCancel(this::closed);
        this.replica.setCollection(CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT));
        this.replica.setSourceId(editor.draft().collectionId());
        this.replica.setStamp(tr("test_draw.stamp"));
        this.replica.setDiscovered(this.found::contains);
        this.replica.setFittedGrid(true);
        this.oddsRow = new UiFoldRow(tr("publish.odds"), null, () -> showOdds(true)).lineAbove(true);
        this.oddsButton = new UiButton(Component.empty().append(tr("publish.odds")).append(" ›"), UiButton.Style.LINK,
                () -> showOdds(true));
        this.oddsBack = new UiButton(Component.literal("‹ ").append(tr("publish.back_to_pull")), UiButton.Style.LINK,
                () -> showOdds(false));
        this.againButton = new UiButton(tr("publish.again"), UiButton.Style.SECONDARY, this::pull);
        this.pullsButton = new UiButton(tr("publish.pulls", TEST_PULLS), UiButton.Style.SECONDARY, this::pullMany);
        this.closeButton = new UiButton(returnTo != null ? CommonComponents.GUI_BACK : tr("publish.close"),
                UiButton.Style.SECONDARY, this::back);
        // The one row: as high as the dialog may be, less its frame and padding
        int height = Math.min(editor.height - 2 * Theme.SPACE_S, MAX_HEIGHT) - FRAME - 2 * DialogFrame.PADDING;
        this.modal.node(this.body, Math.max(0, height));
    }

    /**
     * Opens the test draw over the editor, whose models it takes while it is open.
     *
     * @param returnTo what "Back" opens again once the dialog closes; null for "Close"
     */
    static TestDrawDialog open(CreatorEditorScreen editor, @Nullable Runnable returnTo) {
        TestDrawDialog dialog = new TestDrawDialog(editor, returnTo);
        editor.openModal(dialog.modal);
        editor.coverModels(true);
        return dialog;
    }

    boolean isOpen() {
        return this.modal.isOpen();
    }

    /**
     * The dialog closed, whatever closed it: a draw still playing stops and the editor draws its
     * models again. Safe to call more than once.
     */
    void closed() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.replica.close();
        this.editor.testDrawClosed(this);
    }

    /** Closes the dialog, as when the editor behind it closes. */
    void close() {
        this.modal.close();
        closed();
    }

    // "Back" closes the dialog and opens what it came from; "Close" only closes it
    private void back() {
        this.modal.close();
        closed();
        if (this.returnTo != null) {
            this.returnTo.run();
        }
    }

    private void showOdds(boolean shown) {
        if (shown == this.odds) {
            return;
        }
        this.odds = shown;
        if (shown) {
            // A draw still playing would run its light behind the odds
            this.replica.close();
            refreshShares();
            pullMany();
        }
        this.body.layout();
    }

    // The draw

    // The figures in the draw of the editor's preview, which are the draft's complete figures
    private static List<FigureDefinition> pool() {
        FigureCollection preview = CreatorPreview.collection(CreatorEditorScreen.PREVIEW_SLOT);
        return preview != null ? preview.getEnabledFigures() : List.of();
    }

    private static String keyOf(FigureDefinition figure) {
        return CreatorPreview.previewId(CreatorEditorScreen.PREVIEW_SLOT) + ":" + figure.getId();
    }

    // Whether the dialog has the room the machine's figure grid needs, as the replica was laid out
    private boolean replicaFits() {
        return this.replica.showsGrid();
    }

    // The machine's height: the body less the odds' row, unless that went into the buttons' row,
    // and the buttons
    private int replicaHeight(boolean oddsRowShown) {
        return this.body.bounds().height() - (oddsRowShown ? UiFoldRow.HEIGHT + GAP : 0) - UiButton.HEIGHT - GAP;
    }

    /**
     * Plays a test draw as a regular token would: the winner is drawn by weight here and counts
     * as found by the new player shown, so a run of test draws fills the grid. It comes out
     * holographic as often as a real draw does, so never when the collection turned that off.
     */
    private void pull() {
        List<FigureDefinition> pool = pool();
        if (pool.isEmpty()) {
            return;
        }
        FigureDefinition winner = DrawOdds.pick(pool, this.random);
        String key = keyOf(winner);
        boolean isNew = !this.found.contains(key);
        boolean holographic = this.editor.draft().holographic() && this.random.nextFloat() < Holographic.CHANCE;
        if (this.replica.testDraw(winner, isNew, holographic)) {
            this.found.add(key);
        }
    }

    /** Draws a hundred times by weight and counts how often each figure came out. */
    private void pullMany() {
        List<FigureDefinition> pool = pool();
        int[] counts = new int[pool.size()];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < pool.size(); i++) {
            index.put(pool.get(i).getId(), i);
        }
        for (int pull = 0; pull < TEST_PULLS && !pool.isEmpty(); pull++) {
            Integer at = index.get(DrawOdds.pick(pool, this.random).getId());
            if (at != null) {
                counts[at]++;
            }
        }
        Map<String, Double> chances = DrawOdds.chances(pool);
        List<Bar> bars = new ArrayList<>();
        int distinct = 0;
        for (int i = 0; i < pool.size(); i++) {
            FigureDefinition figure = pool.get(i);
            distinct += counts[i] > 0 ? 1 : 0;
            bars.add(new Bar(figure.getName(), counts[i], chances.getOrDefault(figure.getId(), 0.0),
                    SHARE_COLOURS[i % SHARE_COLOURS.length]));
        }
        this.histogram.setBars(bars, distinct, pool.size());
    }

    private void refreshShares() {
        List<FigureDefinition> pool = pool();
        Map<String, Double> chances = DrawOdds.chances(pool);
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            FigureDefinition figure = pool.get(i);
            Double chance = chances.get(figure.getId());
            if (chance != null) {
                shares.add(new Share(figure.getName(), chance, SHARE_COLOURS[i % SHARE_COLOURS.length]));
            }
        }
        this.shareBar.setShares(shares);
    }

    private static int[] weights(List<FigureDefinition> pool) {
        int[] weights = new int[pool.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = DrawOdds.weight(pool.get(i));
        }
        return weights;
    }

    private static Component regularRule() {
        return tr("publish.rule.regular", DrawRules.MAX_REGULAR_TOKENS,
                DrawRules.REGULAR_TOKEN_COOLDOWN_TICKS / (60 * 60 * 20));
    }

    private static Component guaranteedRule() {
        Integer hour = ClientServerSettings.getGuaranteedResetHour();
        return hour != null ? tr("publish.rule.guaranteed_at", String.format(Locale.ROOT, "%02d:00", hour))
                : tr("publish.rule.guaranteed");
    }

    private Component estimate() {
        if (pool().isEmpty()) {
            return tr("publish.estimate.none");
        }
        int days = this.simulation.days();
        if (days < 0) {
            return tr(this.simulation.isWorking() ? "publish.estimate.working" : "publish.estimate.none");
        }
        return days == 1 ? tr("publish.estimate.one") : tr("publish.estimate", days);
    }

    /**
     * The dialog's one row: the machine with its odds' row, or the odds with the way back, over a
     * row of buttons, the draw's on the left and the way out on the right.
     */
    private final class Body extends UiNode.Box {
        // Whether "Odds" is a button of the bottom row rather than a row of its own
        private boolean oddsInButtons;
        // Where the odds' texts go, as last laid out
        private int rulesY;
        private int estimateY;
        private int estimateLines;
        private int pullsY;
        private int pullsLines;
        private int buttonsY;
        @Nullable
        private UiNode pressed;
        // Whether the machine has been drawn once, which makes its grid, so a draw can play on it
        private boolean drawn;

        @Override
        public Box at(int x, int y, int width, int height) {
            super.at(x, y, width, height);
            layout();
            return this;
        }

        void layout() {
            this.buttonsY = y + height - UiButton.HEIGHT;
            int closeWidth = Math.max(70, closeButton.naturalWidth());
            closeButton.at(x + width - closeWidth, this.buttonsY, closeWidth, UiButton.HEIGHT);
            againButton.at(x, this.buttonsY, againButton.naturalWidth(), UiButton.HEIGHT);
            pullsButton.at(x, this.buttonsY, pullsButton.naturalWidth(), UiButton.HEIGHT);
            if (!odds) {
                // The odds' row under the machine, unless the machine's cells come out bigger with
                // "Odds" in the buttons' row, as on a low window where the height holds them back
                int replicaHeight = Math.max(0, replicaHeight(true));
                replica.at(x, y, width, Math.max(0, replicaHeight(false)));
                int lowCell = replica.gridCell();
                replica.at(x, y, width, replicaHeight);
                this.oddsInButtons = lowCell > replica.gridCell();
                if (this.oddsInButtons) {
                    replica.at(x, y, width, Math.max(0, replicaHeight(false)));
                }
                Font font = Minecraft.getInstance().font;
                int oddsWidth = font.width(oddsButton.label()) + 8;
                oddsButton.at(againButton.bounds().right() + 2 * GAP, this.buttonsY, oddsWidth, UiButton.HEIGHT);
                oddsRow.at(x, y + replicaHeight + GAP, width, UiFoldRow.HEIGHT);
                return;
            }
            Font font = Minecraft.getInstance().font;
            int top = y;
            oddsBack.at(x, top, font.width(oddsBack.label()), 10);
            top += 10 + GAP;
            shareBar.at(x, top, width, BAR_HEIGHT);
            top += BAR_HEIGHT + GAP;
            this.rulesY = top;
            int indent = 12;
            int ruleLines = Math.min(2, Draw.wrap(font, regularRule().getString(), width - indent).size())
                    + Math.min(2, Draw.wrap(font, guaranteedRule().getString(), width - indent).size());
            top += ruleLines * Theme.LINE_HEIGHT + 2 + GAP;
            // The estimate and the hundred draws' line take three lines each where the room is,
            // else one, cut with the whole of it in its tooltip
            int bottom = this.buttonsY - GAP;
            int roomy = 3 * Theme.LINE_HEIGHT * 2 + 2 * GAP + MIN_HISTOGRAM;
            int lines = bottom - top >= roomy ? 3 : 1;
            this.estimateY = top;
            this.estimateLines = lines;
            top += lines * Theme.LINE_HEIGHT + GAP;
            this.pullsY = top;
            this.pullsLines = lines;
            top += lines * Theme.LINE_HEIGHT + 2;
            histogram.at(x, top, width, Math.max(0, bottom - top));
            histogram.setVisible(bottom - top >= MIN_HISTOGRAM);
        }

        // The nodes of the view shown, which take its input
        private List<UiNode.Box> shownNodes() {
            List<UiNode.Box> nodes = new ArrayList<>();
            if (odds) {
                nodes.add(oddsBack);
                nodes.add(shareBar);
                nodes.add(histogram);
                nodes.add(pullsButton);
            } else {
                if (replicaFits()) {
                    nodes.add(replica);
                    nodes.add(againButton);
                }
                nodes.add(this.oddsInButtons && replicaFits() ? oddsButton : oddsRow);
            }
            nodes.add(closeButton);
            return nodes;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            if (!odds && !replicaFits()) {
                // No room for the grid: only the odds
                int middle = y + Math.max(0, replicaHeight(true)) / 2 - Theme.LINE_HEIGHT;
                Draw.lines(graphics, font, tr("publish.too_narrow").getString(), x + 8, middle, width - 16, 2,
                        Theme.MUTED);
            }
            for (UiNode.Box node : shownNodes()) {
                if (node.visible()) {
                    node.draw(graphics, mouseX, mouseY, partialTick);
                }
            }
            if (!odds) {
                this.drawn = this.drawn || replicaFits();
                return;
            }
            int indent = 12;
            int top = this.rulesY;
            graphics.fill(x, top + 1, x + 7, top + 8, REGULAR);
            top = Draw.lines(graphics, font, regularRule().getString(), x + indent, top, width - indent, 2,
                    Theme.TEXT_SOFT);
            graphics.fill(x, top + 1, x + 7, top + 8, Theme.GOLD);
            Draw.lines(graphics, font, guaranteedRule().getString(), x + indent, top, width - indent, 2,
                    Theme.TEXT_SOFT);
            drawText(graphics, font, estimate(), this.estimateY, this.estimateLines, Theme.MUTED, mouseX, mouseY);
            drawText(graphics, font, histogram.summary(), this.pullsY, this.pullsLines, Theme.MUTED, mouseX, mouseY);
        }

        // A text over a number of lines, with the whole of it in its tooltip when that cut it
        private void drawText(GuiGraphics graphics, Font font, Component text, int top, int maxLines, int colour,
                              int mouseX, int mouseY) {
            String whole = text.getString();
            Draw.lines(graphics, font, whole, x, top, width, maxLines, colour);
            boolean cut = Draw.wrap(font, whole, width).size() > maxLines;
            int bottom = top + maxLines * Theme.LINE_HEIGHT;
            if (cut && mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < bottom) {
                UiTooltip.set(SidebarScreen.tooltipLines(text));
            }
        }

        @Override
        public boolean click(UiInput.Click click) {
            this.pressed = null;
            List<UiNode.Box> nodes = shownNodes();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                UiNode.Box node = nodes.get(i);
                if (node.visible() && node.click(click)) {
                    this.pressed = node;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean drag(UiInput.Click click, double dragX, double dragY) {
            return this.pressed != null && this.pressed.drag(click, dragX, dragY);
        }

        @Override
        public boolean release(UiInput.Click click) {
            UiNode node = this.pressed;
            this.pressed = null;
            return node != null && node.release(click);
        }

        @Override
        public boolean scroll(double mouseX, double mouseY, double amount) {
            return !odds && replicaFits() && replica.isMouseOver(mouseX, mouseY)
                    && replica.scroll(mouseX, mouseY, amount);
        }

        @Override
        public void tick() {
            List<FigureDefinition> pool = pool();
            if (!odds && replicaFits()) {
                replica.tick();
                if (!pulled && this.drawn) {
                    // The first draw plays as the dialog opens, once the machine has made its grid
                    pulled = true;
                    pull();
                }
            }
            Component none = pool.isEmpty() ? tr("publish.no_pool") : null;
            againButton.disable(none != null ? none : FigureDraw.isBusy() ? tr("publish.drawing") : null);
            pullsButton.disable(none);
            if (odds) {
                simulation.request(weights(pool));
                simulation.tick();
            }
        }
    }

    /** One figure's share of the draw, for the chance bar. */
    private record Share(String name, double chance, int colour) {
    }

    /**
     * Every figure's chance as a bar split into pieces as wide as the chances, each named in a
     * tooltip, and labelled with the name's start where a piece is wide enough.
     */
    private static final class ShareBar extends UiNode.Box {
        private List<Share> shares = List.of();

        void setShares(List<Share> next) {
            this.shares = List.copyOf(next);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            if (this.shares.isEmpty()) {
                Draw.text(graphics, font, tr("publish.chance.none").getString(), x, y + 2, width, Theme.MUTED);
                return;
            }
            // A gap between pieces only while every piece keeps a few pixels
            int gap = width / this.shares.size() >= 6 ? 1 : 0;
            double total = 0;
            for (Share share : this.shares) {
                total += share.chance();
            }
            double at = 0;
            for (int i = 0; i < this.shares.size(); i++) {
                Share share = this.shares.get(i);
                int from = x + (int) Math.round(width * at / total);
                at += share.chance();
                int to = i == this.shares.size() - 1 ? x + width : x + (int) Math.round(width * at / total) - gap;
                if (to <= from) {
                    to = from + 1;
                }
                graphics.fill(from, y, to, y + height, share.colour());
                String label = Draw.cut(font, share.name(), to - from - 2);
                if (to - from >= 16 && !label.endsWith("...") && font.width(label) <= to - from - 2) {
                    graphics.drawString(font, label, (from + to) / 2 - font.width(label) / 2, y + 2, 0xFF000000, false);
                }
                if (mouseX >= from && mouseX < Math.max(to, from + 1) + gap && mouseY >= y && mouseY < y + height) {
                    UiTooltip.set(tr("publish.share", share.name(), OptionsPage.percent(share.chance())));
                }
            }
        }
    }

    /** One figure's count out of the test draws, with its chance. */
    private record Bar(String name, int count, double chance, int colour) {
    }

    /**
     * How often each figure came out of a hundred test draws: a column per figure as tall as its
     * count, with a tick where its chance says it should reach, and the figure's name, count and
     * chance in a tooltip.
     */
    private static final class Histogram extends UiNode.Box {
        private List<Bar> bars = List.of();
        private int distinct;
        private int figures;

        void setBars(List<Bar> next, int found, int total) {
            this.bars = List.copyOf(next);
            this.distinct = found;
            this.figures = total;
        }

        /** What the hundred draws found, over the chart. */
        Component summary() {
            return tr("publish.pulls_body", this.distinct, this.figures);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible() || this.bars.isEmpty() || width <= 0) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int top = y + Theme.LINE_HEIGHT;
            int bottom = y + height - 2;
            int most = 1;
            for (Bar bar : this.bars) {
                most = Math.max(most, Math.max(bar.count(), (int) Math.ceil(bar.chance() * TEST_PULLS)));
            }
            Draw.shrunk(graphics, font, tr("publish.pulls_axis", most).getString(), x, y, width, Theme.MUTED);
            graphics.fill(x, bottom, x + width, bottom + 1, Theme.SEPARATOR);
            int slot = Math.max(1, width / this.bars.size());
            int gap = slot >= 6 ? Math.max(1, slot / 5) : 0;
            int chartHeight = Math.max(1, bottom - top);
            for (int i = 0; i < this.bars.size(); i++) {
                Bar bar = this.bars.get(i);
                int left = x + i * slot;
                if (left >= x + width) {
                    break;
                }
                int right = Math.min(x + width, left + slot - gap);
                int barTop = bottom - Math.round(chartHeight * bar.count() / (float) most);
                if (bar.count() > 0) {
                    graphics.fill(left, barTop, Math.max(left + 1, right), bottom, bar.colour());
                }
                int expectedY = bottom - (int) Math.round(chartHeight * bar.chance() * TEST_PULLS / most);
                graphics.fill(left, expectedY, Math.max(left + 1, right), expectedY + 1, Theme.TEXT);
                if (mouseX >= left && mouseX < left + slot && mouseY >= top && mouseY <= bottom) {
                    UiTooltip.set(List.of(Component.literal(bar.name()),
                            tr("publish.pulls_bar", bar.count(), TEST_PULLS,
                                    String.format(Locale.ROOT, "%.1f", bar.chance() * TEST_PULLS))));
                }
            }
        }
    }
}
