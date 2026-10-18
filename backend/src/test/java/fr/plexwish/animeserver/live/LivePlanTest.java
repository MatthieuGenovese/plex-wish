package fr.plexwish.animeserver.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Découpage en segments (docs/LIVE-REMUX.md §2.1) et lecture de la sortie de ffprobe. */
class LivePlanTest {

    @Test
    void segmentsStartOnKeyframesAndLastAtLeastSixSeconds() {
        // Images clés irrégulières (comme Air Gear : écarts de 0,2 à 6 s).
        int[] kf = {0, 2000, 4000, 5900, 6100, 13600, 13900, 20000, 23866, 30000};
        LivePlan p = LivePlan.of(kf, 34_000);
        assertArrayEquals(new double[]{0, 6.1, 13.6, 20.0, 30.0}, p.bounds(), 1e-9);
        assertEquals(5, p.count());
        assertEquals(6.1, p.length(0), 1e-9);
        assertEquals(4.0, p.length(4), 1e-9);
        assertEquals(10, p.targetDuration()); // segment 20 → 30 s
        assertEquals(0, p.segmentAt(0));
        assertEquals(0, p.segmentAt(5.95));
        assertEquals(1, p.segmentAt(6.05)); // tolérance : horodatages arrondis à la milliseconde
        assertEquals(2, p.segmentAt(19.8));
        assertEquals(4, p.segmentAt(33));
    }

    @Test
    void seekAimsJustAfterTheKeyframe() {
        LivePlan p = LivePlan.of(new int[]{0, 6000, 6200, 12000, 20000}, 25_000);
        assertNull(p.seekFor(0));
        // Image clé suivante à 0,2 s : on vise au milieu (6,1 s) ; sinon 0,5 s après.
        assertEquals(6.1, p.seekFor(1), 1e-9);
        assertEquals(12.5, p.seekFor(2), 1e-9);
    }

    @Test
    void shortLastSegmentIsMerged() {
        LivePlan p = LivePlan.of(new int[]{0, 6000, 12000}, 12_400);
        assertArrayEquals(new double[]{0, 6.0}, p.bounds(), 1e-9);
        assertEquals(6.4, p.length(1), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> LivePlan.of(new int[0], 1000));
    }

    @Test
    void ffprobeOutputKeepsKeyframesOnlyWithDtsFallback() {
        String csv = """
                0.083000,0.000000,K__
                0.125000,0.041000,___
                N/A,661.600000,K_
                2.000000,1.958000,K_
                2.000000,1.958000,K_
                N/A,N/A,K_
                1392.600000
                """;
        KeyframeIndexService.Parsed p = KeyframeIndexService.parse(csv);
        assertArrayEquals(new int[]{83, 2000, 661_600}, p.timesMs());
        assertEquals(1_392_600, p.durationMs());
        assertEquals(0, KeyframeIndexService.parse("").timesMs().length);
    }
}
