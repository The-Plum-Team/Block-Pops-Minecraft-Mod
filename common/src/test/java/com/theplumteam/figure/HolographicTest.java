package com.theplumteam.figure;

import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.worldcontent.BlobInfo;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionMaterializer;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The server's draw roll: never holographic from a World collection that turned it off (ADR 0006). */
class HolographicTest {
    private static final int DRAWS = 5_000;

    private static FigureCollection collection(boolean holographic) {
        String skin = Sha256.of("skin".getBytes(StandardCharsets.UTF_8));
        WorldCollectionRecord record = new WorldCollectionRecord("w-col", "Col", "", 1, 2, 3, null, null, null,
                false, 1, List.of(new WorldCollectionRecord.Figure("zombi", "Zombi", WorldCollectionRecord.Kind.SKIN,
                skin, null, null, 1.0f, true))).withHolographic(holographic);
        return WorldCollectionMaterializer.materialize(record, Map.of(skin, BlobInfo.png(1_000, 64, 64)));
    }

    private static int holographicDraws(FigureCollection collection) {
        int count = 0;
        for (int i = 0; i < DRAWS; i++) {
            if (Holographic.roll(collection)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void aCollectionThatTurnedItOffNeverDrawsAHolographicFigure() {
        FigureCollection off = collection(false);
        assertFalse(off.allowsHolographic());
        assertEquals(0, holographicDraws(off));
    }

    @Test
    void aCollectionThatAllowsItDrawsOneNowAndThen() {
        assumeFalse(E2EDeterminism.ENABLED, "the packaged E2E never rolls holographic");
        FigureCollection on = collection(true);
        assertTrue(on.allowsHolographic());
        // One in 25: 5000 draws without one would happen less than once in 10^88 runs
        assertTrue(holographicDraws(on) > 0);
    }
}
