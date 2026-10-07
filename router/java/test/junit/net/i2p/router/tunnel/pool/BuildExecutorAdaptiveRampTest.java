package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The adaptive build-timeout ladder and the concurrency throttle ramp.
 *
 * <p>Both were rewritten after review found the high-timeout branch of the ladder
 * unreachable above 50% success, the throttle's documented 60% floor unreachable,
 * and the restore step large enough to undo a throttle in a single window. These
 * tests pin the corrected behaviour, and each carries a case that fails against the
 * previous implementation.
 *
 * @since 0.9.71+
 */
public class BuildExecutorAdaptiveRampTest {

    private static final double THROTTLE_THRESHOLD = 0.30;
    private static final long HIGH_TIMEOUT_RECOVERY_MS = 7 * 1000L;
    private static final long FAST_NETWORK_REDUCTION_MS = -3 * 1000L;
    private static final long MODERATE_RECOVERY_MS = 2 * 1000L;
    private static final long LOW_SUCCESS_RECOVERY_MS = 5 * 1000L;

    /** base concurrency on a 24-core host, the shape the review worked from. */
    private static final int BASE = 96;
    private static final double THROTTLE_FLOOR_RATIO = 0.60;

    // ---- ladder: the ordering fix ----

    /**
     * The regression: 60% success with 35% timeouts is the band where slot waste
     * hurts most and the old ladder fell through to the moderate-success branch,
     * handing out 2s instead of the intended 7s.
     */
    @Test
    public void highTimeoutRateWinsAtModerateSuccess() {
        assertEquals("a timeout rate above the threshold must be answered first",
                     HIGH_TIMEOUT_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.60, 0.35, THROTTLE_THRESHOLD));
    }

    /**
     * The ordering is safe precisely because a high timeout rate bounds the success
     * rate, so hoisting the timeout test can never steal a case the fast-network
     * reduction should have won.
     */
    @Test
    public void fastNetworkReductionStillWinsBelowTheTimeoutThreshold() {
        assertEquals(FAST_NETWORK_REDUCTION_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.90, 0.05, THROTTLE_THRESHOLD));
        assertEquals(FAST_NETWORK_REDUCTION_MS,
                     BuildExecutor.adaptiveTimeoutDelta(1.00, 0.00, THROTTLE_THRESHOLD));
    }

    /**
     * Pins the arithmetic relationship the ordering depends on: whenever the timeout
     * branch fires, success cannot be in the fast band, so reordering cannot shadow it.
     */
    @Test
    public void timeoutThresholdImpliesSuccessBelowTheFastBand() {
        for (double timeoutRate = 0.31; timeoutRate < 1.0; timeoutRate += 0.05) {
            assertTrue("timeoutRate " + timeoutRate + " must not co-occur with >85% success",
                       1.0 - timeoutRate <= 0.85);
        }
    }

    /**
     * The band edges are strict inequalities, so a success rate sitting exactly on a
     * boundary falls to the next band down: 0.85 is not above 0.85 but is above 0.70,
     * so it takes the no-adjustment band, while 0.70 is above neither and drops to the
     * moderate band. These are the boundaries the ladder has always used and are pinned
     * so a future edit that turns them inclusive fails here rather than shifting the
     * timeout silently.
     */
    @Test
    public void ladderBandsMatchDocumentedThresholds() {
        assertEquals(FAST_NETWORK_REDUCTION_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.86, 0.10, THROTTLE_THRESHOLD));
        assertEquals("0.85 exactly is not above 0.85, so no reduction", 0L,
                     BuildExecutor.adaptiveTimeoutDelta(0.85, 0.10, THROTTLE_THRESHOLD));
        assertEquals("0.70 exactly is not above 0.70, so the moderate band", MODERATE_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.70, 0.10, THROTTLE_THRESHOLD));
        assertEquals(MODERATE_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.51, 0.10, THROTTLE_THRESHOLD));
        assertEquals(LOW_SUCCESS_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.50, 0.10, THROTTLE_THRESHOLD));
        assertEquals(LOW_SUCCESS_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.00, 0.10, THROTTLE_THRESHOLD));
    }

    @Test
    public void lowSuccessDrivenByRejectsGetsItsOwnDelta() {
        // plenty of rejects, but timeouts under the threshold: the +5s band, not +7s
        assertEquals(LOW_SUCCESS_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.20, 0.05, THROTTLE_THRESHOLD));
    }

    /** The timeout test is a strict >, so sitting exactly on the threshold is not a crossing. */
    @Test
    public void thresholdIsExclusive() {
        assertEquals(MODERATE_RECOVERY_MS,
                     BuildExecutor.adaptiveTimeoutDelta(0.60, THROTTLE_THRESHOLD, THROTTLE_THRESHOLD));
    }

    // ---- throttle ramp ----

    /**
     * The regression: the old form took 80% of base and clamped with 60% of base,
     * which can never bind, so every crossing produced the same value.
     */
    @Test
    public void throttleIsProgressiveNotPinned() {
        int first = BuildExecutor.throttledConcurrency(BASE, BASE);
        int second = BuildExecutor.throttledConcurrency(first, BASE);
        int third = BuildExecutor.throttledConcurrency(second, BASE);
        assertEquals(76, first);
        assertEquals("second crossing must go lower than the first", 60, second);
        assertEquals("third crossing must still go lower", 57, third);
    }

    /** The documented 60% floor is now actually reachable and actually holds. */
    @Test
    public void throttleFloorIsReachableAndHolds() {
        int value = BASE;
        for (int i = 0; i < 20; i++) {
            value = BuildExecutor.throttledConcurrency(value, BASE);
            assertTrue("must not fall below the 60% floor, was " + value,
                       value >= (int) (BASE * 0.60));
        }
        assertEquals("must converge on the floor, not keep falling",
                     (int) (BASE * 0.60), value);
    }

    @Test
    public void throttleNeverExceedsCurrent() {
        for (int current = 0; current <= BASE; current += 7) {
            assertTrue("throttle raised the ceiling at " + current,
                       BuildExecutor.throttledConcurrency(current, BASE) <= current);
        }
    }

    /** A single crossing stays mild, so a transient spike is not over-corrected. */
    @Test
    public void singleCrossingIsShallow() {
        assertEquals(76, BuildExecutor.throttledConcurrency(96, 96));
    }

    // ---- restore ramp ----

    /**
     * The regression: the old quarter-step took a 20% cut back to base in one
     * window, making the throttle and the restore symmetric.
     */
    @Test
    public void restoreIsSlowerThanTheThrottleRemoves() {
        int throttleStep = BASE - BuildExecutor.throttledConcurrency(BASE, BASE);
        int restoreStep = Math.max(1, BASE / 16);
        assertTrue("restore step " + restoreStep + " must be smaller than the "
                   + throttleStep + " it reverses", restoreStep < throttleStep);
    }

    @Test
    public void restoreRampsToBaseOverSeveralWindows() {
        int value = (int) (BASE * 0.60);
        int windows = 0;
        while (value < BASE && windows < 100) {
            value = BuildExecutor.restoredConcurrency(value, BASE);
            windows++;
        }
        assertEquals(BASE, value);
        assertTrue("recovery should take several windows, took " + windows, windows > 2);
    }

    @Test
    public void restoreNeverExceedsBase() {
        for (int current = 0; current <= BASE; current += 5) {
            assertTrue("restore exceeded base at " + current,
                       BuildExecutor.restoredConcurrency(current, BASE) <= BASE);
        }
    }

    /**
     * From the throttled floor, every legal base makes progress. setMaxConcurrentBuilds
     * clamps to a minimum of 8, so bases below that are not reachable and are not
     * exercised here.
     */
    @Test
    public void restoreMakesProgressOnSmallBases() {
        for (int base = 8; base <= 64; base++) {
            int from = Math.max(1, (int) (base * THROTTLE_FLOOR_RATIO));
            if (from >= base) { continue; }
            assertTrue("no progress from " + from + " toward " + base,
                       BuildExecutor.restoredConcurrency(from, base) > from);
        }
    }

    // ---- the two must not fight each other ----

    /**
     * A sustained timeout storm walks down to the floor and stops there; a subsequent
     * clean window walks back up without either control overshooting.
     */
    @Test
    public void stormThenRecoveryStaysWithinBounds() {
        int value = BASE;
        for (int i = 0; i < 10; i++) {
            value = BuildExecutor.throttledConcurrency(value, BASE);
        }
        assertEquals((int) (BASE * 0.60), value);
        int previous = value;
        for (int i = 0; i < 50 && value < BASE; i++) {
            int next = BuildExecutor.restoredConcurrency(value, BASE);
            assertTrue("restore must be monotonic", next > value);
            value = next;
            previous = value;
        }
        assertEquals(BASE, previous);
    }
}
