package com.theplumteam.figure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks the figure a draw gives from a pool, and tells how likely each figure is.
 *
 * Each figure is drawn in proportion to its draw weight. Bundled and Custom figures all weigh 1,
 * and so do the figures of a World collection whose figures are all common, so for them a draw is
 * uniform and consumes the random source exactly as it always has: one {@code nextInt} over the
 * pool size. That keeps the seeded draw the packaged E2E photographs unchanged. Only a World
 * collection with rarities, whose figures weigh their rarity's weight, draws by weight.
 */
public final class DrawOdds {
    private DrawOdds() {
    }

    /** The figure's weight in a draw; at least 1. */
    public static int weight(FigureDefinition figure) {
        return Math.max(1, figure.getDrawWeight());
    }

    /**
     * Picks one figure of a non-empty pool. A pool whose figures all weigh the same is drawn
     * with {@code pool.get(random.nextInt(pool.size()))}.
     */
    public static FigureDefinition pick(List<FigureDefinition> pool, Random random) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("A draw needs at least one figure");
        }
        if (allEqual(pool)) {
            return pool.get(random.nextInt(pool.size()));
        }
        // Weights are at most 1000 and a pool at most a few hundred figures, so the total fits
        int roll = random.nextInt(Math.toIntExact(total(pool)));
        for (FigureDefinition figure : pool) {
            roll -= weight(figure);
            if (roll < 0) {
                return figure;
            }
        }
        // Unreachable: the roll is below the total of the weights it is reduced by
        return pool.get(pool.size() - 1);
    }

    /**
     * The chance of each figure of the pool, from 0 to 1, keyed by figure id in pool order.
     * An empty pool has no chances.
     */
    public static Map<String, Double> chances(List<FigureDefinition> pool) {
        if (pool.isEmpty()) {
            return Map.of();
        }
        double total = total(pool);
        Map<String, Double> chances = new LinkedHashMap<>();
        for (FigureDefinition figure : pool) {
            // A pool should not repeat an id; if it does, the chances of both add up
            chances.merge(figure.getId(), weight(figure) / total, Double::sum);
        }
        return Collections.unmodifiableMap(chances);
    }

    private static boolean allEqual(List<FigureDefinition> pool) {
        int first = weight(pool.get(0));
        for (FigureDefinition figure : pool) {
            if (weight(figure) != first) {
                return false;
            }
        }
        return true;
    }

    private static long total(List<FigureDefinition> pool) {
        long total = 0;
        for (FigureDefinition figure : pool) {
            total += weight(figure);
        }
        return total;
    }
}
