package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the tunnel test failure window ({@link TestJob#computeTestPeriod}).
 *
 * The window is how long a tunnel test may run before the tunnel is declared
 * failed. Measured successful tests (~2.3s on a typical router) must not be
 * answered within tens of seconds: a window of 2x the measured success RTT
 * detects dead tunnels in ~4.6s instead of ~23s, which directly cuts the
 * build timeout rate by letting pools fail and rebuild quickly.
 *
 * <p>That only holds while most rounds pass. The success average is a
 * survivor-biased sample — it holds nothing but rounds that finished inside the
 * window — so sizing the window from it while most rounds fail converges on the
 * smallest window consistent with the rounds that already fit. Measured on a
 * live router: 84 outbound failures a minute against 29 passes, the window
 * pinned at 1.4s, and every failure recorded at the deadline. Once the failure
 * share passes {@link TestJob#UNREPRESENTATIVE_FAILURE_RATIO} the window is
 * sized from the rounds that missed it instead.
 *
 * <p>That second rule needs a guard of its own. A round that never gets an
 * answer is timed out at the window, so its recorded duration is the window,
 * and 2x the window is 2x the value being computed: the window walks to
 * maxTestPeriod and stays there, quoting itself. The discriminator is the
 * window in force - a failure average at or above it says only "no answer by
 * the deadline", while a failure average below it was recorded before the
 * deadline and is a real measurement.
 *
 * <p>The rounds that missed it are recorded under two statistics - one for
 * client pools, one for exploratory - while the passes are recorded under one
 * for every pool, so the failure side has to fold both populations back
 * together before it is comparable with the success side. The fold is by event
 * count, and the last group of cases pins that.
 *
 * @since 0.9.70+
 */
public class TestJobPeriodTest {

    /** A failure share below the bar, so the success sample is trusted. */
    private static final double PASSING = 0.1d;

    /** A failure share above the bar, so the failing population sizes the window. */
    private static final double FAILING = 0.49d;

    /** No window on record yet, so nothing can be read as the deadline quoting itself. */
    private static final double NO_WINDOW = Double.NaN;

    /**
     * Runs the tunnel test window rules over a fixed set of samples.
     *
     * @since 0.9.72+
     */
    public TestJobPeriodTest() {
        super();
    }

    // =====================================================================
    // Measured-success path: window = 2x average success time
    // =====================================================================

    /** A measured network sizes the window at 2x the successful-test average. */
    @Test
    public void testWindowIsTwoTimesMeasuredSuccess() {
        // observed avg ~2.3s -> ~4.6s window, within the default [3s, 15s] range
        int period = TestJob.computeTestPeriod(2258, 2400, NO_WINDOW, PASSING, 1241, 4, 4,
                                               3000, 15000);
        assertEquals(4516, period);
    }

    /** A fractional average is rounded to a whole millisecond, not truncated. */
    @Test
    public void testWindowRoundsToNearestMs() {
        assertEquals(2001, TestJob.computeTestPeriod(1000.4, Double.NaN, NO_WINDOW,
                                                      PASSING, 500, 3, 3, 1000, 15000));
    }

    /** A slow measured network is capped by maxTestPeriod, not widened forever. */
    @Test
    public void testWindowScalesUpOnSlowNetwork() {
        // 8s measured success -> 16s window, capped by the 15s max
        assertEquals(15000, TestJob.computeTestPeriod(8000, 8200, NO_WINDOW,
                                                      PASSING, 1241, 4, 4,
                                                      3000, 15000));
    }

    /** A fast measured network is held at minTestPeriod, not collapsed further. */
    @Test
    public void testWindowFlooredByMinPeriod() {
        // 1.2s measured success -> 2.4s target, below the 3s floor
        assertEquals(3000, TestJob.computeTestPeriod(1200, 1300, NO_WINDOW,
                                                     PASSING, 1241, 4, 4,
                                                     3000, 15000));
    }

    /**
     * An unmeasured ratio is not a failing cycle, it is no ratio at all, and the
     * success average is the only signal there is.
     */
    @Test
    public void testUnknownRatioStillUsesSuccessAverage() {
        assertEquals(4516, TestJob.computeTestPeriod(2258, Double.NaN, NO_WINDOW,
                                                     Double.NaN, 1241, 4, 4, 3000, 15000));
    }

    /** The bar is inclusive: exactly 25% failing is still a passing cycle. */
    @Test
    public void testFailureRatioAtTheBarIsStillRepresentative() {
        assertTrue(TestJob.representativeSample(2258, TestJob.UNREPRESENTATIVE_FAILURE_RATIO));
        assertEquals(4516, TestJob.computeTestPeriod(2258, 9000, NO_WINDOW,
                                                     TestJob.UNREPRESENTATIVE_FAILURE_RATIO,
                                                     1241, 4, 4, 3000, 15000));
    }

    // =====================================================================
    // Failing path: the rounds that missed the window size it
    // =====================================================================

    /**
     * The live signature that motivated this branch: successes at 373ms, 49% of
     * rounds failing, and failures recorded at 2.1s - under the 3s floor those
     * rounds ran under, so the duration is a measured latency and not the
     * deadline quoting itself. Sizing from the success mean keeps the window at
     * its floor; sizing from the failures gives the failing population the room
     * it missed.
     */
    @Test
    public void testFailingRoundsSizeTheWindow() {
        int period = TestJob.computeTestPeriod(373, 2144, 3000, FAILING,
                                               1241, 3, 3, 3000, 15000);
        assertEquals(4288, period);
        assertTrue("the window must leave the deadline it was missing", period > 2144);
    }

    /** The failing population cannot widen the window past maxTestPeriod. */
    @Test
    public void testFailingPathStillCappedByMaxPeriod() {
        assertEquals(15000, TestJob.computeTestPeriod(373, 9000, 15000, FAILING,
                                                      1241, 3, 3, 3000, 15000));
    }

    /**
     * A failing cycle with no usable failure average cannot say how much room
     * the failures need, so the configured floor is what applies — not the
     * survivor mean, and not the hop formula, which is for an unmeasured network.
     */
    @Test
    public void testFailingWithoutFailureAverageKeepsTheFloor() {
        assertEquals(3000, TestJob.computeTestPeriod(373, Double.NaN, NO_WINDOW,
                                                     FAILING, 1241, 3, 3, 3000, 15000));
    }

    /**
     * The window quoting itself, the live signature of 2026-11-25: passes at
     * 2.0s, failures recorded at 19.3s, and the window in force at its 15s
     * ceiling - so every failure was timed out at the deadline and its duration
     * is the deadline, not a reply time. Sizing from it asks for 38.5s, the
     * clamp pins it at 15s, and the next minute measures 15s again: the window
     * has no way back down. Sizing from the rounds that did answer puts it at
     * 2x the measured 2.0s round.
     */
    @Test
    public void testFailureAtTheWindowIsTheWindowTalking() {
        int period = TestJob.computeTestPeriod(1972, 19291, 15000, FAILING,
                                               94, 4, 4, 3000, 15000);
        assertEquals(3944, period);
        assertTrue("the ceiling must not be self-sustaining", period < 15000);
    }

    /**
     * The other side of the same bar: a failure average below the window in
     * force was recorded before its deadline, so it is a measured latency and
     * the failing population does get a wider window.
     */
    @Test
    public void testFailureBelowTheWindowIsAMeasurement() {
        assertTrue(TestJob.timeoutArtifact(19291, 15000));
        assertFalse(TestJob.timeoutArtifact(2144, 3000));
        assertFalse("no window on record is no deadline to quote",
                    TestJob.timeoutArtifact(19291, NO_WINDOW));
        assertFalse("a zero stat is not a measurement",
                    TestJob.timeoutArtifact(0, 15000));
    }

    /**
     * A failing cycle whose failures carry no usable duration cannot fall back
     * on the survivor mean either: the configured floor is what applies.
     */
    @Test
    public void testArtifactNeedsAFailureAverageToFire() {
        assertEquals(3000, TestJob.computeTestPeriod(373, Double.NaN, 15000, FAILING,
                                                     1241, 3, 3, 3000, 15000));
    }

    /** The window is sized from whichever population is slower, not the faster. */
    @Test
    public void testWorseLatencyPicksTheFailingPopulation() {
        assertEquals(2144d, TestJob.worseLatency(373, 2144), 0.001);
        // while the cycle is passing, the success average is the larger one
        assertEquals(2258d, TestJob.worseLatency(2258, 1200), 0.001);
        assertEquals(0d, TestJob.worseLatency(Double.NaN, Double.NaN), 0.001);
        assertEquals(1200d, TestJob.worseLatency(-1, 1200), 0.001);
    }

    /** A failing or unusable success sample is never trusted to size the window. */
    @Test
    public void testUnrepresentativeSampleIsNotTrusted() {
        assertFalse(TestJob.representativeSample(373, FAILING));
        assertFalse(TestJob.representativeSample(Double.NaN, Double.NaN));
        assertFalse(TestJob.representativeSample(0, Double.NaN));
        assertTrue(TestJob.representativeSample(373, PASSING));
    }

    // =====================================================================
    // The pass/fail ratio
    // =====================================================================

    /** The ratio the live router was running at: 102 failures against 106 passes. */
    @Test
    public void testFailureRatioOfTheLivePopulation() {
        assertEquals(0.49d, TestJob.testFailureRatio(106, 102), 0.001);
    }

    /** Too few rounds to judge is NaN, not a ratio that widens the window on noise. */
    @Test
    public void testFailureRatioNeedsEnoughRounds() {
        assertTrue(Double.isNaN(TestJob.testFailureRatio(2, 17)));
        assertEquals(0.5d, TestJob.testFailureRatio(TestJob.MIN_RATIO_SAMPLES / 2,
                                                    TestJob.MIN_RATIO_SAMPLES / 2), 0.001);
        assertTrue(Double.isNaN(TestJob.testFailureRatio(0, 0)));
    }

    // =====================================================================
    // Fallback path: no measurements yet
    // =====================================================================

    /** Before any test has completed, mainline's transport formula applies. */
    @Test
    public void testFallbackUsesTransportFormula() {
        // mainline formula: 3x sendProcessing + 2.5s per hop
        // 3*1241 + 2500*8 = 23723, clamped to the 15s max
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, Double.NaN, NO_WINDOW,
                                                      Double.NaN, 1241, 4, 4, 3000, 15000));
    }

    /** With neither a test measurement nor a transport stat, the 15s base applies. */
    @Test
    public void testFallbackWithoutTransportStat() {
        // no transport stat -> 15s base + per-hop allowance, clamped to max
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, Double.NaN, NO_WINDOW,
                                                      Double.NaN,
                                                      Double.NaN, 4, 4, 3000, 15000));
    }

    /** A short tunnel pair on a fast transport lands inside the configured range. */
    @Test
    public void testFallbackWithinRangeOnShortTunnel() {
        // 3*100 + 2500*2 = 5300, inside [3000, 15000]
        assertEquals(5300, TestJob.computeTestPeriod(Double.NaN, Double.NaN, NO_WINDOW,
                                                     Double.NaN, 100, 1, 1, 3000, 15000));
    }

    // =====================================================================
    // No-tunnel path and robust clamps
    // =====================================================================

    /** A round with no tunnel pair to measure is not timed by a measured window. */
    @Test
    public void testNoTunnelsUsesFifteenSeconds() {
        assertEquals(15000, TestJob.computeTestPeriod(2258, 2400, NO_WINDOW,
                                                      PASSING, 1241, 0, 0,
                                                      3000, 15000));
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, Double.NaN, NO_WINDOW,
                                                      Double.NaN,
                                                      Double.NaN, -1, 0, 3000, 15000));
    }

    /** A crossed min/max config cannot force a fast net into a 20s window. */
    @Test
    public void testCrossedConfigFloorsAtSmallerValue() {
        // min(20000) > max(5000): effective floor is the smaller value, so a
        // fast net is not forced into a 20s window by the crossed config
        assertEquals(5000, TestJob.computeTestPeriod(2258, 2400, NO_WINDOW,
                                                     PASSING, 1241, 4, 4,
                                                     20000, 5000));
    }

    /** A crossed min/max config still cannot exceed the larger of the two. */
    @Test
    public void testCrossedConfigCapsAtLargerValue() {
        // min(10000) > max(7000): effective range [7000, 10000], floor wins here
        assertEquals(7000, TestJob.computeTestPeriod(2258, 2400, NO_WINDOW,
                                                     PASSING, 1241, 4, 4,
                                                     10000, 7000));
    }

    /** A stat that reads 0 is not a measurement, and must not collapse the window. */
    @Test
    public void testZeroSuccessTimeFallsBack() {
        // measured stat exists but is 0 -> fall back to the transport formula
        assertEquals(15000, TestJob.computeTestPeriod(0, 0, NO_WINDOW, Double.NaN, 1241, 4, 4,
                                                      3000, 15000));
    }

    /** A negative average (a cross-clock artifact) is discarded, not used. */
    @Test
    public void testNegativeSuccessTimeFallsBack() {
        assertEquals(15000, TestJob.computeTestPeriod(-1, -1, NO_WINDOW,
                                                      Double.NaN, 1241, 4, 4,
                                                      3000, 15000));
    }

    // =====================================================================
    // The clamp the adaptive multiplier is held inside
    // =====================================================================

    /**
     * The pool multiplier is applied after {@link TestJob#computeTestPeriod}
     * clamps, so without a second clamp the window in use leaves the configured
     * range — at the 2.0 ceiling, twice the maxTestPeriod.
     */
    @Test
    public void testAdaptedWindowHeldInsideRange() {
        assertEquals(15000, TestJob.clampTestPeriod(30000, 3000, 15000));
        assertEquals(3000, TestJob.clampTestPeriod(1448, 3000, 15000));
        assertEquals(4500, TestJob.clampTestPeriod(4500, 3000, 15000));
    }

    /** A crossed config gives the range [smaller, larger], as in computeTestPeriod. */
    @Test
    public void testAdaptedClampHandlesCrossedConfig() {
        assertEquals(20000, TestJob.clampTestPeriod(30000, 20000, 5000));
        assertEquals(9000, TestJob.clampTestPeriod(9000, 20000, 5000));
        assertEquals(7000, TestJob.clampTestPeriod(1000, 10000, 7000));
    }

    // =====================================================================
    // Folding the two test-failure populations into one
    // =====================================================================

    /**
     * With no exploratory rounds the client mean is used as measured, so the
     * common case does not move.
     */
    @Test
    public void testFailureAverageUnchangedWithoutExploratoryRounds() {
        assertEquals(17897d,
                     TestJob.combineTestFailureAverage(21, 17897d, 0, Double.NaN), 0.0001d);
    }

    /**
     * The fold is by event count, not an average of the two means: 90 rounds at
     * 6s beside 2 rounds at 1s is a population of 92 rounds at 5.9s, and
     * averaging the means would report 3.5s - a window half the size the
     * measured rounds need.
     */
    @Test
    public void testFailureAverageFoldsBothPopulationsByCount() {
        assertEquals(5891.3d, TestJob.combineTestFailureAverage(90, 6000d, 2, 1000d), 0.1d);
    }

    /** A side with rounds but no usable mean contributes nothing, and vice versa. */
    @Test
    public void testFailureAverageIgnoresAnUnmeasuredSide() {
        assertEquals(20000d,
                     TestJob.combineTestFailureAverage(0, Double.NaN, 5, 20000d), 0.0001d);
        assertEquals(6000d,
                     TestJob.combineTestFailureAverage(7, 6000d, 3, Double.NaN), 0.0001d);
    }

    /** No failed rounds is not a measurement, and must not read as a zero mean. */
    @Test
    public void testNoFailedRoundsIsNotAMeasurement() {
        double none = TestJob.combineTestFailureAverage(0, Double.NaN, 0, Double.NaN);
        assertTrue(Double.isNaN(none));
        assertTrue(Double.isNaN(TestJob.combineTestFailureAverage(-1, 5000d, 0, 5000d)));
    }

    /**
     * The defect the fold closes: passes are counted for every pool while
     * failures are filed under two statistics, so comparing all the passes
     * against only the client failures can keep the ratio under the threshold
     * that makes the sample worth sizing from, with the exploratory rounds
     * failing unseen.
     */
    @Test
    public void testExploratoryRoundsMoveTheRatioAcrossTheThreshold() {
        double clientOnly = TestJob.testFailureRatio(40, 12);
        double bothPools = TestJob.testFailureRatio(40, 12 + 12);
        assertTrue("client-only ratio " + clientOnly + " should sit under the threshold",
                   clientOnly < TestJob.UNREPRESENTATIVE_FAILURE_RATIO);
        assertTrue("folded ratio " + bothPools + " should sit above it",
                   bothPools > TestJob.UNREPRESENTATIVE_FAILURE_RATIO);
    }
}
