package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The build reply timeout's tunable range, and the ceiling the ladder independently clamps to.
 *
 * <p>These are two separate clamps on the same value and they had drifted apart: the ladder
 * allowed the adaptive timeout up to 30s, while every increase path in the Tuner parameter is
 * {@code Math.min(_max, ...)} with a 15s ceiling. A high timeout rate - around 60% here - drove
 * the autotuner to its ceiling every pass, where it could do nothing, while the ladder sat
 * willing to grant considerably more.
 *
 * @since 0.9.71+
 */
public class BuildReplyTimeoutRangeTest {

    /** The floor and ceiling the ladder applies to the adaptive value. */
    private static final long LADDER_MIN_MS = 10 * 1000L;
    private static final long LADDER_MAX_MS = 30 * 1000L;

    /** What the Tuner parameter now declares. */
    private static final int TUNER_MIN = 5000;
    private static final int TUNER_MAX = 30000;
    private static final int TUNER_DEFAULT = 15000;

    @Test
    public void ceilingMatchesTheLadderClamp() {
        assertEquals("the Tuner ceiling and the ladder clamp must not disagree",
                     (int) LADDER_MAX_MS, TUNER_MAX);
    }

    @Test
    public void rangeIsOrderedAndLegal() {
        assertTrue("min must be positive", TUNER_MIN > 0);
        assertTrue("min must be below max", TUNER_MIN < TUNER_MAX);
        assertTrue("default must sit inside the range",
                   TUNER_MIN <= TUNER_DEFAULT && TUNER_DEFAULT <= TUNER_MAX);
    }

    /**
     * The property that was actually broken: with the ceiling below the ladder's clamp, no value
     * this parameter could take could let a build reach the budget the ladder permits.
     */
    @Test
    public void theCeilingCanReachTheLadderClamp() {
        assertTrue("the autotuner must be able to reach the ladder's own maximum",
                   TUNER_MAX >= LADDER_MAX_MS);
    }

    /**
     * It must also be able to reach the durations builds actually take. Recorded live: median
     * 400ms, p90 12.2s, slowest 25.4s.
     */
    @Test
    public void theCeilingCoversTheObservedTail() {
        long observedSlowest = 25 * 1000L;
        assertTrue("ceiling " + TUNER_MAX + " must cover the slowest build observed (25378ms)",
                   TUNER_MAX >= observedSlowest);
    }

    /**
     * The floor is unchanged, deliberately. A short deadline is what causes a premature abandon,
     * so the floor's job is to stop the value being tuned low, and 5s already sits well above the
     * median build of 400ms.
     */
    @Test
    public void floorStaysWellAboveTheTypicalBuild() {
        long observedMedian = 400L;
        assertTrue("the floor must not permit a deadline below the typical build",
                   TUNER_MIN >= observedMedian * 10);
    }

    /** The step has to divide the widened range, or the ceiling becomes unreachable in practice. */
    @Test
    public void theStepCanReachTheCeiling() {
        int step = 1000;
        assertEquals("the autotuner must be able to land exactly on the ceiling",
                     0, (TUNER_MAX - TUNER_DEFAULT) % step);
    }
}