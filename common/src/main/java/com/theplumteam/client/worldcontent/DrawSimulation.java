package com.theplumteam.client.worldcontent;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.figure.DrawRules;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Estimates how many days of tokens a player needs to complete a collection, for the creator's
 * review tab. It plays 2000 players with this mod's token rules and gives the median number of
 * days they took.
 *
 * <p>Each simulated player plays once a day. That day they spend the daily guaranteed token,
 * which gives a figure they do not have yet, chosen by weight among those, and then the regular
 * tokens they banked since the day before, each a draw by weight over every figure. A regular
 * token comes every few hours up to a small stock, so playing once a day finds the stock full:
 * {@link #regularPerDay()} tokens. Extra tickets an operator hands out are not counted. Since the
 * guaranteed token finds a new figure every day, no player takes more days than the collection
 * has figures.
 *
 * <p>An instance belongs to one screen. {@link #request} asks for the estimate of a set of
 * weights; the work starts once the weights have stood still for 300 ms, so a weight being typed
 * is not simulated at every keystroke, and runs off the render thread. The result comes back on
 * the render thread, and a result for weights that changed in the meantime is dropped. The draw
 * itself is the game's: a weighted pick in proportion to each figure's weight, as
 * {@link com.theplumteam.figure.DrawOdds} draws.
 */
public final class DrawSimulation {
    /** How many players are simulated. */
    public static final int PLAYERS = 2000;
    // How long the weights must stay the same before they are simulated
    private static final long DEBOUNCE_MS = 300;
    // The same players every time, so the same collection always gets the same estimate
    private static final long SEED = 0x5EED_B10CL;
    private static final long TICKS_PER_DAY = 24L * 60 * 60 * 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops Draw Simulation");
        thread.setDaemon(true);
        return thread;
    });

    // The weights last asked for, and when they last changed
    private int[] wanted = new int[0];
    // Whether any weights were asked for yet, so a run that failed is not started again every tick
    private boolean asked;
    private long wantedAt;
    private boolean pending;
    // Counts every request, so a result for older weights is told apart and dropped
    private int generation;
    private int days = -1;
    private boolean running;

    /** How many regular tokens a player who plays once a day finds banked. */
    public static int regularPerDay() {
        long earned = TICKS_PER_DAY / DrawRules.REGULAR_TOKEN_COOLDOWN_TICKS;
        return (int) Math.min(DrawRules.MAX_REGULAR_TOKENS, earned);
    }

    /**
     * Asks for the estimate of a collection whose figures in the draw have these weights.
     * Asking again for the same weights keeps the estimate already worked out, or the lack of
     * one when working it out failed.
     */
    public void request(int[] weights) {
        if (asked && Arrays.equals(weights, wanted)) {
            return;
        }
        asked = true;
        wanted = weights.clone();
        wantedAt = Util.getMillis();
        generation++;
        days = -1;
        pending = weights.length > 0;
        running = false;
    }

    /** Starts the simulation once the weights have stood still long enough; call once a tick. */
    public void tick() {
        if (!pending || Util.getMillis() - wantedAt < DEBOUNCE_MS) {
            return;
        }
        pending = false;
        running = true;
        int asked = generation;
        int[] weights = wanted.clone();
        try {
            WORKER.execute(() -> {
                int result;
                try {
                    result = medianDays(weights, PLAYERS, SEED);
                } catch (RuntimeException e) {
                    BlockPopsMod.LOGGER.warn("The draw simulation failed", e);
                    result = -1;
                }
                int found = result;
                Minecraft.getInstance().execute(() -> finished(asked, found));
            });
        } catch (RejectedExecutionException e) {
            running = false;
        }
    }

    private void finished(int asked, int result) {
        if (asked != generation) {
            return;
        }
        running = false;
        days = result;
    }

    /** Whether an estimate is being worked out, or waits for the weights to stand still. */
    public boolean isWorking() {
        return pending || running;
    }

    /** The median number of days, or -1 while there is none. */
    public int days() {
        return days;
    }

    /**
     * Plays the players and returns the median number of days they took to find every figure,
     * or 0 for no figures. Each weight is at least 1.
     */
    public static int medianDays(int[] weights, int players, long seed) {
        int count = weights.length;
        if (count == 0 || players <= 0) {
            return 0;
        }
        // Running totals for the regular draw, which picks by binary search
        long[] cumulative = new long[count];
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += Math.max(1, weights[i]);
            cumulative[i] = total;
        }
        int regular = regularPerDay();
        SplittableRandom random = new SplittableRandom(seed);
        int[] taken = new int[players];
        boolean[] found = new boolean[count];
        for (int player = 0; player < players; player++) {
            Arrays.fill(found, false);
            int missing = count;
            long missingWeight = total;
            int day = 0;
            while (missing > 0) {
                day++;
                // The guaranteed token: by weight among the figures still to find
                long roll = random.nextLong(missingWeight);
                for (int i = 0; i < count; i++) {
                    if (found[i]) {
                        continue;
                    }
                    roll -= Math.max(1, weights[i]);
                    if (roll < 0) {
                        found[i] = true;
                        missing--;
                        missingWeight -= Math.max(1, weights[i]);
                        break;
                    }
                }
                // Then the banked regular tokens, each over every figure
                for (int token = 0; token < regular && missing > 0; token++) {
                    int pick = pick(cumulative, random.nextLong(total));
                    if (!found[pick]) {
                        found[pick] = true;
                        missing--;
                        missingWeight -= Math.max(1, weights[pick]);
                    }
                }
            }
            taken[player] = day;
        }
        Arrays.sort(taken);
        return taken[players / 2];
    }

    // The first figure whose running total is above the roll
    private static int pick(long[] cumulative, long roll) {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] > roll) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
