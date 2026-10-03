package net.i2p.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the timer watchdog's cadence-aware stall threshold.
 *
 * <p>The defect: the stall check reported any timer whose completed-task count had not
 * advanced for {@link SimpleTimer2#WATCHDOG_STALL_SAMPLES} samples. The
 * {@code RetransmissionTimer} shard timers are legitimately low-frequency, so they
 * produced a WARN roughly every 50s forever. Twelve of the thirteen "no timer event
 * completed" lines in one log were that false positive, which is exactly the sort of
 * noise that trains a reader to miss the single real report.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2StallCadenceTest {

    private static final int THRESHOLD = SimpleTimer2.WATCHDOG_STALL_SAMPLES;
    private static final int INTERVAL = SimpleTimer2.WATCHDOG_INTERVAL_MS;

    private static SimpleTimer2.WatchdogDecision decide(long completed, long lastCompleted,
                                                       int stalledSamples, boolean reported,
                                                       long meanIntervalMs) {
        return SimpleTimer2.evaluateStall(completed, lastCompleted, stalledSamples, THRESHOLD,
                                          reported, meanIntervalMs);
    }

    @Test
    public void progressEndsTheEpisode() {
        assertEquals(SimpleTimer2.WatchdogDecision.OK,
                     decide(50L, 49L, 10, false, 10L));
    }

    @Test
    public void aTimerThatNeverCompletedIsNotReported() {
        assertEquals("an idle new timer is not a fault",
                     SimpleTimer2.WatchdogDecision.OK, decide(0L, 0L, 10, false, 0L));
    }

    @Test
    public void belowTheSampleThresholdIsPending() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(50L, 50L, THRESHOLD - 1, false, 10L));
    }

    /**
     * A busy timer going quiet past the flat threshold still reports: its own mean
     * interval is tiny, so the cadence allowance does not extend the bar.
     */
    @Test
    public void busyTimerIsStillReportedAfterTheFlatThreshold() {
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(391066L, 391066L, THRESHOLD, false, 12L));
    }

    /**
     * The false positive: a low-frequency timer that has simply not run yet.
     */
    @Test
    public void lowFrequencyTimerIsNotReportedAtTheFlatThreshold() {
        // 13 completions over ~5000s is a ~385s mean interval, so 180s of quiet is
        // entirely normal for this timer.
        long meanInterval = 385_000L;
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(13L, 13L, THRESHOLD, false, meanInterval));
    }

    @Test
    public void lowFrequencyTimerIsReportedOnlyPastFourMeanIntervals() {
        long meanInterval = 385_000L;
        int samplesForFourIntervals = (int) ((4 * meanInterval) / INTERVAL) + 1;
        assertTrue("the test needs a sample count past four mean intervals",
                   samplesForFourIntervals > THRESHOLD);
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(13L, 13L, samplesForFourIntervals, false, meanInterval));
    }

    @Test
    public void anAlreadyReportedEpisodeStaysQuiet() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(391066L, 391066L, THRESHOLD * 10, true, 12L));
    }

    @Test
    public void cadenceAllowanceNeverShortensTheFlatThreshold() {
        // A pathological mean interval of 0 must not let it report early.
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(100L, 100L, THRESHOLD - 1, false, 0L));
    }

    @Test
    public void unknownCadenceFallsBackToTheFlatThreshold() {
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(100L, 100L, THRESHOLD, false, 0L));
    }

    @Test
    public void saturatedAndStalledChecksRemainIndependent() {
        // Saturation is a depth signal and must not be gated on cadence.
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     SimpleTimer2.evaluateSaturation(2, 2, 3001,
                                                     SimpleTimer2.WATCHDOG_SATURATION_SAMPLES,
                                                     SimpleTimer2.WATCHDOG_SATURATION_SAMPLES,
                                                     false));
    }
}