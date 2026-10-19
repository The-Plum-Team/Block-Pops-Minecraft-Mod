package com.theplumteam.client.gui;

import com.theplumteam.client.ClientSpecialAccess;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.client.gui.util.GuiScaleManager;
import com.theplumteam.client.gui.widget.FigureEntry;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The draw a token buys, played out on the machine's figure grid: a light wanders over the
 * figures it could have given, always to a cell next to the one it is on, never straight back
 * and towards the cells it has visited least, until it crawls to a stop on the one the server
 * drew, which is then shown. Moving only between neighbours, it can be followed and the list
 * can glide after it. The server has already handed the figure over; this only reveals it, so
 * a figure it unlocked stays undiscovered on the grid until the light stops.
 */
public final class FigureDraw {
    // The light runs about twice along the figures, within these bounds on its moves
    private static final int MIN_STEPS = 28;
    private static final int MAX_STEPS = 48;
    // It runs a cell a tick, then takes its last moves with these growing pauses
    private static final int[] SLOWDOWN = {2, 3, 4, 5, 6, 8, 10, 13};
    // How long the light pulses on the drawn figure before it is shown
    private static final int REVEAL_DELAY = 16;
    // The shown figure is put away on a click, or on its own after this
    private static final int RESULT_TICKS = 140;
    // A spent token whose draw has not arrived by then was refused; the server says why in chat
    private static final int RESULT_TIMEOUT = 100;

    @Nullable
    private static FigureDraw active;
    @Nullable
    private static String awaitedCollection;
    private static boolean awaitedGuaranteed;
    private static int awaitTicks;

    /**
     * A figure on the light's path: the list row it sits in, and its place along the row in
     * cells from the row's middle, since a row with fewer figures is centred.
     */
    private record Stop(FigureDefinition figure, int row, double column) {
        // Left, right, above or below; a cell of a centred row touches the two it sits between
        boolean touches(Stop other) {
            double across = Math.abs(column - other.column);
            int down = Math.abs(row - other.row);
            return down == 0 ? Math.abs(across - 1.0) < 1.0E-6 : down == 1 && across <= 0.5 + 1.0E-6;
        }

        double distance(Stop other) {
            return Math.hypot(column - other.column, row - other.row);
        }
    }

    private final FigureCollection collection;
    private final FigureDefinition winner;
    private final boolean isNew;
    private final boolean holographic;
    private final FigureListWidget list;
    private final List<Stop> path;
    private Stop lit;
    private int step;
    private int ticksToNext = 1;
    // Counts up from the moment the light stops on the drawn figure; -1 while it still moves
    private int stoppedTicks = -1;

    private FigureDraw(FigureCollection collection, FigureDefinition winner, boolean isNew,
                       boolean holographic, FigureListWidget list, List<Stop> path) {
        this.collection = collection;
        this.winner = winner;
        this.isNew = isNew;
        this.holographic = holographic;
        this.list = list;
        this.path = path;
        this.lit = path.get(0);
        list.followRow(lit.row());
    }

    /** A token was spent on this collection: the screen waits for its draw. */
    public static void await(String collectionId, boolean guaranteed) {
        finish();
        awaitedCollection = collectionId;
        awaitedGuaranteed = guaranteed;
        awaitTicks = RESULT_TIMEOUT;
    }

    /**
     * Called with the server's draw. Without a screen waiting for it, only its sound plays, with
     * the announcement of a holographic figure.
     */
    public static void onResult(String collectionId, String figureId, boolean isNew, boolean holographic) {
        boolean awaited = collectionId.equals(awaitedCollection);
        boolean guaranteed = awaitedGuaranteed;
        awaitedCollection = null;
        FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
        FigureDefinition winner = collection == null ? null : collection.getFigure(figureId).orElse(null);
        Minecraft minecraft = Minecraft.getInstance();
        FigureListWidget list = minecraft.screen instanceof CollectionSelectionScreen screen
                ? screen.figureList() : null;
        if (!awaited || winner == null || list == null || list.getCurrentCollection() == null
                || !collectionId.equals(list.getCurrentCollection().getId())) {
            playResultSound(isNew, holographic, winner);
            return;
        }
        List<Stop> path = path(cells(list, collection, figureId, guaranteed), figureId, winner);
        active = new FigureDraw(collection, winner, isNew, holographic, list, path);
    }

    /**
     * Plays a draw the client made up itself on a list, as the creator's test draw does: the
     * light runs over the figures a token could give and stops on the winner, which is then
     * shown as a real draw's is. No token is spent and nothing is awaited from the server, so a
     * real draw the machine is waiting for is left alone; one already playing is ended. A
     * holographic winner is shown and sounded as a real one is.
     */
    public static void preview(FigureListWidget list, FigureCollection collection, FigureDefinition winner,
                               boolean isNew, boolean holographic) {
        finish();
        active = new FigureDraw(collection, winner, isNew, holographic, list,
                path(cells(list, collection, winner.getId(), false), winner.getId(), winner));
    }

    /** The cells of the figures the token could have given, where the list shows them. */
    private static List<Stop> cells(FigureListWidget list, FigureCollection collection,
                                    String winnerId, boolean guaranteed) {
        List<FigureDefinition> pool = ClientSpecialAccess.figuresFor(collection);
        if (guaranteed) {
            List<FigureDefinition> undiscovered = new ArrayList<>();
            for (FigureDefinition figure : pool) {
                if (!list.isDiscovered(collection.getId() + ":" + figure.getId())) {
                    undiscovered.add(figure);
                }
            }
            if (!undiscovered.isEmpty()) {
                pool = undiscovered;
            }
        }
        List<Stop> cells = new ArrayList<>();
        List<List<FigureDefinition>> figureRows = list.figureRows();
        for (int row = 0; row < figureRows.size(); row++) {
            List<FigureDefinition> figures = figureRows.get(row);
            for (int i = 0; i < figures.size(); i++) {
                FigureDefinition figure = figures.get(i);
                if (figure.getId().equals(winnerId)
                        || pool.stream().anyMatch(candidate -> candidate.getId().equals(figure.getId()))) {
                    cells.add(new Stop(figure, row, i - (figures.size() - 1) / 2.0));
                }
            }
        }
        return cells;
    }

    /**
     * The light's whole path. It is walked backwards from the drawn figure and then reversed, so
     * it ends there. Each move goes to a touching cell, or to the nearest ones where the cells
     * the token could give leave a gap, never back to the cell just left while there is another
     * way, and to the least visited of the choices, so the light covers the grid.
     */
    private static List<Stop> path(List<Stop> cells, String winnerId, FigureDefinition winner) {
        Stop current = null;
        for (Stop cell : cells) {
            if (cell.figure().getId().equals(winnerId)) {
                current = cell;
            }
        }
        List<Stop> path = new ArrayList<>();
        if (current == null || cells.size() == 1) {
            // The drawn figure is the only one on the grid, or not on it: the light can only go there
            path.add(current == null ? new Stop(winner, 0, 0.0) : current);
            return path;
        }
        Random random = new Random();
        Map<Stop, Integer> visits = new HashMap<>();
        int steps = Math.max(MIN_STEPS, Math.min(MAX_STEPS, cells.size() * 2));
        Stop previous = null;
        path.add(current);
        visits.merge(current, 1, Integer::sum);
        for (int i = 1; i < steps; i++) {
            List<Stop> choices = neighbours(cells, current);
            if (choices.size() > 1) {
                choices.remove(previous);
            }
            int fewest = Integer.MAX_VALUE;
            for (Stop choice : choices) {
                fewest = Math.min(fewest, visits.getOrDefault(choice, 0));
            }
            List<Stop> leastVisited = new ArrayList<>();
            for (Stop choice : choices) {
                if (visits.getOrDefault(choice, 0) == fewest) {
                    leastVisited.add(choice);
                }
            }
            Stop next = leastVisited.get(random.nextInt(leastVisited.size()));
            visits.merge(next, 1, Integer::sum);
            path.add(next);
            previous = current;
            current = next;
        }
        Collections.reverse(path);
        return path;
    }

    // The cells touching this one, or the nearest others where nothing touches it
    private static List<Stop> neighbours(List<Stop> cells, Stop cell) {
        List<Stop> touching = new ArrayList<>();
        for (Stop other : cells) {
            if (other != cell && cell.touches(other)) {
                touching.add(other);
            }
        }
        if (!touching.isEmpty()) {
            return touching;
        }
        double nearest = Double.MAX_VALUE;
        for (Stop other : cells) {
            if (other != cell) {
                nearest = Math.min(nearest, cell.distance(other));
            }
        }
        List<Stop> closest = new ArrayList<>();
        for (Stop other : cells) {
            if (other != cell && cell.distance(other) <= nearest + 1.0E-6) {
                closest.add(other);
            }
        }
        return closest;
    }

    /** Advances the draw; the machine's screen calls this every tick. */
    public static void tick() {
        if (awaitedCollection != null && --awaitTicks <= 0) {
            awaitedCollection = null;
        }
        if (active != null) {
            active.advance();
        }
    }

    private void advance() {
        if (stoppedTicks >= 0) {
            stoppedTicks++;
            if (stoppedTicks == REVEAL_DELAY) {
                playResultSound(isNew, holographic, winner);
            } else if (stoppedTicks >= REVEAL_DELAY + RESULT_TICKS) {
                finish();
            }
            return;
        }
        if (--ticksToNext > 0) {
            return;
        }
        step++;
        if (step >= path.size() - 1) {
            lit = path.get(path.size() - 1);
            list.followRow(lit.row());
            stoppedTicks = 0;
            play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F, 0.6F);
            return;
        }
        lit = path.get(step);
        list.followRow(lit.row());
        // The move into the last cell takes the last pause of the slowdown
        int fromEnd = path.size() - 1 - step;
        int slowdown = SLOWDOWN.length - fromEnd;
        ticksToNext = slowdown >= 0 ? SLOWDOWN[slowdown] : 1;
        float progress = step / (float) (path.size() - 1);
        play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0F + progress, 0.4F);
    }

    /**
     * True while a spent token's draw is awaited or its light still moves or pulses; no longer
     * once it shows its figure, which leaves the rest of the screen usable.
     */
    public static boolean isBusy() {
        return awaitedCollection != null || (active != null && active.stoppedTicks < REVEAL_DELAY);
    }

    /** True for the figure the light is on. */
    public static boolean isLit(String collectionId, String figureId) {
        return active != null && active.collection.getId().equals(collectionId)
                && active.lit.figure().getId().equals(figureId);
    }

    /** How bright the light is, from 0 to 1: full while it moves, pulsing once it has stopped. */
    public static float glow(float partialTick) {
        if (active == null || active.stoppedTicks < 0) {
            return 1.0F;
        }
        return 0.6F + 0.4F * (float) Math.cos((active.stoppedTicks + partialTick) * 0.6F);
    }

    /** True for a figure this draw unlocked, which the grid keeps undiscovered until it is shown. */
    public static boolean hides(String collectionId, String figureId) {
        return active != null && active.isNew && active.stoppedTicks < REVEAL_DELAY
                && active.collection.getId().equals(collectionId) && active.winner.getId().equals(figureId);
    }

    /** True while a draw plays or shows its figure on this list. */
    public static boolean playsOn(FigureListWidget list) {
        return active != null && active.list == list;
    }

    /** The draw whose figure is being shown, if any. */
    @Nullable
    public static FigureDraw result() {
        return active != null && active.stoppedTicks >= REVEAL_DELAY ? active : null;
    }

    /**
     * Shows the figure a draw on this list stopped on, in a card above everything else on the
     * screen, centred over the list, which it alone dims; the rest of the screen stays usable.
     * Drawn in the list's own space, which is scaled up when the inverse GUI scale is in use.
     * Nothing is drawn while no draw on this list, or on its collection, shows its figure.
     *
     * @param stamp a line under the card's text in a red frame, such as the creator's note that
     *              a test draw spends nothing; null for none
     */
    public static void drawResultCard(GuiGraphics graphics, Font font, FigureListWidget list, float partialTick,
                                      @Nullable Component stamp) {
        FigureDraw draw = result();
        // The machine makes its list again when the window is resized, so a draw is also shown on
        // a list that shows its collection; a creator preview's collection is never on the machine
        FigureCollection shown = list.getCurrentCollection();
        if (draw == null || (draw.list != list
                && (shown == null || !shown.getId().equals(draw.collection.getId())))) {
            return;
        }
        FigureEntry row = list.rowOf(draw.winner().getId());
        if (row == null) {
            return;
        }
        // Everything drawn so far lands below the card: the list's models are drawn with depth,
        // so the card is also lifted in front of them
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);

        float scale = GuiScaleManager.isUsingInverseScale() ? GuiScaleManager.getRenderScaleFactor() : 1.0f;
        int left = (int) (list.left() * scale);
        int top = (int) (list.top() * scale);
        int right = (int) (list.right() * scale);
        int bottom = (int) (list.bottom() * scale);
        graphics.fill(left, top, right, bottom, 0xB0000000);

        String name = draw.winner().getName();
        String label = draw.isHolographic()
                ? (draw.isNew() ? "✦ NEW & HOLOGRAPHIC! ✦" : "✦ HOLOGRAPHIC! ✦")
                : draw.isNew() ? "NEW!" : "Already in your collection";
        String hint = "Click or press Esc to close";
        String stampText = stamp != null ? stamp.getString() : null;
        int line = font.lineHeight + 4;
        int lines = stampText != null ? 4 : 3;
        int size = (int) Math.max(40, Math.min(120 * scale, bottom - top - lines * line - 30));
        int cardWidth = Math.max(Math.max(size, font.width(hint)), Math.max(font.width(name), font.width(label))) + 40;
        if (stampText != null) {
            cardWidth = Math.max(cardWidth, font.width(stampText) + 24);
        }
        int cardHeight = size + lines * line + 20;
        int cardX = (left + right - cardWidth) / 2;
        int cardY = (top + bottom - cardHeight) / 2;
        // A holographic figure's frame and label run through the rainbow
        int holographicColor = 0xFF000000
                | net.minecraft.util.Mth.hsvToRgb((System.currentTimeMillis() % 2000L) / 2000.0F, 0.55F, 1.0F);
        int labelColor = draw.isHolographic() ? holographicColor : draw.isNew() ? 0xFFFFD700 : 0xFFAAAAAA;
        int frameColor = labelColor;
        graphics.fill(cardX - 2, cardY - 2, cardX + cardWidth + 2, cardY + cardHeight + 2, frameColor);
        graphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xFF181818);
        GuiPose.flush(graphics);

        int textY = cardY + 8;
        graphics.drawString(font, label, cardX + (cardWidth - font.width(label)) / 2, textY, labelColor, false);
        row.renderPreview(graphics, draw.winner(), cardX + (cardWidth - size) / 2, textY + line, size, partialTick,
                false, draw.isHolographic());
        textY += line + size + 4;
        graphics.drawString(font, name, cardX + (cardWidth - font.width(name)) / 2, textY, 0xFFFFFFFF, false);
        textY += line;
        graphics.drawString(font, hint, cardX + (cardWidth - font.width(hint)) / 2, textY, 0xFF888888, false);
        if (stampText != null) {
            textY += line;
            int stampWidth = font.width(stampText) + 8;
            int stampX = cardX + (cardWidth - stampWidth) / 2;
            int outline = 0xFFFF8A8A;
            int stampTop = textY - 3;
            int stampBottom = textY + font.lineHeight + 2;
            graphics.fill(stampX, stampTop, stampX + stampWidth, stampTop + 1, outline);
            graphics.fill(stampX, stampBottom - 1, stampX + stampWidth, stampBottom, outline);
            graphics.fill(stampX, stampTop + 1, stampX + 1, stampBottom - 1, outline);
            graphics.fill(stampX + stampWidth - 1, stampTop + 1, stampX + stampWidth, stampBottom - 1, outline);
            graphics.drawString(font, stampText, stampX + 4, textY, outline, false);
        }
        GuiPose.pop(graphics);
    }

    /**
     * Ends a draw on this list at once and without its result sound, as when the creator's
     * preview it plays on goes away; a real draw the machine waits for is left alone.
     */
    public static void stopOn(FigureListWidget list) {
        if (active != null && active.list == list) {
            finish();
        }
    }

    /** Puts the shown figure away. */
    public static void dismissResult() {
        if (result() != null) {
            finish();
        }
    }

    /** Forgets the draw when its screen closes, sounding a result the screen never got to show. */
    public static void reset() {
        if (active != null && active.stoppedTicks < REVEAL_DELAY) {
            playResultSound(active.isNew, active.holographic, active.winner);
        }
        finish();
        awaitedCollection = null;
    }

    // Ends the draw and hands the list's scrolling back to the player
    private static void finish() {
        if (active != null) {
            active.list.stopFollowing();
        }
        active = null;
    }

    public FigureCollection collection() {
        return collection;
    }

    public FigureDefinition winner() {
        return winner;
    }

    public boolean isNew() {
        return isNew;
    }

    public boolean isHolographic() {
        return holographic;
    }

    // A holographic figure rings out over the usual sound and is announced in shifting colours
    private static void playResultSound(boolean isNew, boolean holographic, @Nullable FigureDefinition winner) {
        play(isNew ? SoundEvents.PLAYER_LEVELUP : SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
        if (!holographic) {
            return;
        }
        play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.0F);
        play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.8F, 1.0F);
        play(SoundEvents.PLAYER_LEVELUP, 1.6F, 0.6F);
        Component message = winner == null
                ? Component.translatable("blockpops.draw.holographic_unknown")
                : Component.translatable("blockpops.draw.holographic", winner.getName());
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=26.2 {
        /*minecraft.gui.hud.setOverlayMessage(message, true);
        *///? } else {
        minecraft.gui.setOverlayMessage(message, true);
        //? }
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
