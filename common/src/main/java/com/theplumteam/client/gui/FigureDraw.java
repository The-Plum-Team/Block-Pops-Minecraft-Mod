package com.theplumteam.client.gui;

import com.theplumteam.client.ClientSpecialAccess;
import com.theplumteam.client.discovery.ClientDiscoveryManager;
import com.theplumteam.client.gui.widget.FigureListWidget;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
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
    private final FigureListWidget list;
    private final List<Stop> path;
    private Stop lit;
    private int step;
    private int ticksToNext = 1;
    // Counts up from the moment the light stops on the drawn figure; -1 while it still moves
    private int stoppedTicks = -1;

    private FigureDraw(FigureCollection collection, FigureDefinition winner, boolean isNew,
                       FigureListWidget list, List<Stop> path) {
        this.collection = collection;
        this.winner = winner;
        this.isNew = isNew;
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

    /** Called with the server's draw. Without a screen waiting for it, only its sound plays. */
    public static void onResult(String collectionId, String figureId, boolean isNew) {
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
            playResultSound(isNew);
            return;
        }
        List<Stop> path = path(cells(list, collection, figureId, guaranteed), figureId, winner);
        active = new FigureDraw(collection, winner, isNew, list, path);
    }

    /** The cells of the figures the token could have given, where the list shows them. */
    private static List<Stop> cells(FigureListWidget list, FigureCollection collection,
                                    String winnerId, boolean guaranteed) {
        List<FigureDefinition> pool = ClientSpecialAccess.figuresFor(collection);
        if (guaranteed) {
            List<FigureDefinition> undiscovered = new ArrayList<>();
            for (FigureDefinition figure : pool) {
                if (!ClientDiscoveryManager.isDiscovered(collection.getId() + ":" + figure.getId())) {
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
                playResultSound(isNew);
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

    /** The draw whose figure is being shown, if any. */
    @Nullable
    public static FigureDraw result() {
        return active != null && active.stoppedTicks >= REVEAL_DELAY ? active : null;
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
            playResultSound(active.isNew);
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

    private static void playResultSound(boolean isNew) {
        play(isNew ? SoundEvents.PLAYER_LEVELUP : SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
