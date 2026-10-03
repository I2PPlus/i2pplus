package net.i2p.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the timer watchdog's self-calibrating stall threshold.
 *
 * <p>Two defects are covered here.
 *
 * <p>The original stall check reported any timer whose completed-task count had not
 * advanced for {@link SimpleTimer2#WATCHDOG_STALL_SAMPLES} samples. The
 * {@code RetransmissionTimer} shard timers are legitimately low-frequency, so they
 * produced a WARN roughly every 50s forever. Twelve of the thirteen "no timer event
 * completed" lines in one log were that false positive, which is exactly the sort of
 * noise that trains a reader to miss the single real report.
 *
 * <p>The fix for that - scaling the bar by the timer's mean interval - was itself
 * wrong, because a mean is dominated by the busy stretches of a bursty timer. The
 * {@code StreamTimer} shards run in dense bursts; with a 1.7s mean they were reported
 * for a three-minute lull that they had recovered from all along. The threshold is now
 * calibrated against the longest gap the timer has actually come back from.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2StallCadenceTest {

    private static final int THRESHOLD = SimpleTimer2.WATCHDOG_STALL_SAMPLES;
    private static final int INTERVAL = SimpleTimer2.WATCHDOG_INTERVAL_MS;

    private static SimpleTimer2.WatchdogDecision decide(long completed, long lastCompleted,
                                                       int stalledSamples, boolean reported,
                                                       long maxObservedGapMs) {
        return SimpleTimer2.evaluateStall(completed, lastCompleted, stalledSamples, THRESHOLD,
                                          reported, maxObservedGapMs);
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

    /** A timer with a dense history: three minutes of quiet is far past anything it has done. */
    @Test
    public void denseTimerIsReportedAfterTheFlatThreshold() {
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(391_066L, 391_066L, THRESHOLD, false, 12_000L));
    }

    /**
     * The false positive that motivated the first fix: a low-frequency timer that has
     * simply not run yet is not wedged.
     */
    @Test
    public void lowFrequencyTimerIsNotReportedAtTheFlatThreshold() {
        // This timer has previously gone 385s without completing anything, so a
        // 180s lull is well inside its normal behaviour.
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(13L, 13L, THRESHOLD, false, 385_000L));
    }

    @Test
    public void lowFrequencyTimerIsReportedOncePastTwiceItsLongestGap() {
        long longestGap = 385_000L;
        int samplesForTwice = (int) ((2 * longestGap) / INTERVAL) + 1;
        assertTrue("the test needs a sample count past twice the longest gap",
                   samplesForTwice > THRESHOLD);
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(13L, 13L, samplesForTwice, false, longestGap));
    }

    /**
     * The false positive that the mean-interval fix could not solve: a bursty timer
     * whose mean is far shorter than the real gaps between its bursts. Reporting it
     * would be exactly the noise this threshold exists to remove.
     */
    @Test
    public void burstyTimerIsNotReportedAtItsOwnTypicalGap() {
        // Dense bursts with multi-minute lulls in between. 180s of quiet is normal.
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(479L, 479L, THRESHOLD, false, 600_000L));
    }

    @Test
    public void burstyTimerIsReportedOnlyWhenTheGapExceedsItsHistory() {
        long longestGap = 600_000L;
        int samples = (int) ((2 * longestGap) / INTERVAL) + 1;
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(479L, 479L, samples, false, longestGap));
    }

    /**
     * A timer that is quiet for longer than twice anything it has recovered from is
     * genuinely wedged, even though its completion count is large and healthy-looking.
     */
    @Test
    public void aTimerThatStopsDeadIsStillReported() {
        assertEquals(SimpleTimer2.WatchdogDecision.REPORT,
                     decide(479L, 479L, (int) ((2 * 600_000L) / INTERVAL) + 1, false, 600_000L));
    }

    @Test
    public void anAlreadyReportedEpisodeStaysQuiet() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(391_066L, 391_066L, THRESHOLD * 10, true, 12_000L));
    }

    /**
     * With no learned history there is nothing to compare against, and a quiet timer
     * cannot be told apart from an idle one. Reporting here is what produced the
     * original noise, so silence is the only safe answer.
     */
    @Test
    public void anUncalibratedTimerIsNeverReported() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(100L, 100L, THRESHOLD, false, 0L));
    }

    @Test
    public void anUncalibratedTimerIsStillSilentBelowTheFloor() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     decide(100L, 100L, THRESHOLD - 1, false, 0L));
    }

    /**
     * Jitter either side of exactly twice the longest gap must not flip the decision,
     * or a busy timer would report and clear repeatedly on the same lull.
     */
    @Test
    public void theBoundaryIsStrict() {
        long longestGap = 600_000L;
        int samples = (int) ((2 * longestGap) / INTERVAL);
        assertEquals("exactly twice the gap is inside normal behaviour",
                     SimpleTimer2.WatchdogDecision.PENDING,
                     decide(479L, 479L, samples, false, longestGap));
        assertEquals("a sample past it reports",
                     SimpleTimer2.WatchdogDecision.REPORT,
                     decide(479L, 479L, samples + 1, false, longestGap));
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
