package net.i2p.util;

import org.junit.Test;

import static net.i2p.util.SimpleTimer2.WATCHDOG_RECOVERY_SAMPLES;
import static net.i2p.util.SimpleTimer2.WATCHDOG_STALL_SAMPLES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the timer watchdog's idle-versus-wedged decision.
 *
 * <p>The failure these exist for: streaming retransmission shards that have nothing to
 * retransmit were reported as wedged timers. They complete nothing because no task is
 * due, which is not a fault - and a detector calibrated against a timer's own history
 * cannot tell, because a shard that has always been quiet has no history to calibrate
 * against. Three live shards reported "completed 1 tasks in total" after fourteen
 * minutes.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2WorkDueDecisionTest {

    private static final long GAP_BASELINE = 30_000L;

    /**
     * A timer that has completed one task ever, and has never run anything since.
     * This is the exact shape the live false positive had.
     */
    private static SimpleTimer2.WatchdogDecision quietShard(boolean workDue) {
        return SimpleTimer2.evaluateStall(1L, 1L, WATCHDOG_STALL_SAMPLES, WATCHDOG_STALL_SAMPLES,
                                          false, GAP_BASELINE, workDue);
    }

    /////////////// nothing due means nothing to be late for

    @Test
    public void nothingDueIsNotAStall() {
        assertEquals("a timer with no due task is idle, not wedged",
                     SimpleTimer2.WatchdogDecision.OK, quietShard(false));
    }

    @Test
    public void nothingDueStaysQuietHoweverLongTheGap() {
        // Calibration cannot rescue this case, so the work-due test has to: an hour of
        // silence on a timer that never had work is still not a fault.
        assertEquals(SimpleTimer2.WatchdogDecision.OK,
                     SimpleTimer2.evaluateStall(1L, 1L, 1_000, WATCHDOG_STALL_SAMPLES,
                                               false, 1L, false));
    }

    @Test
    public void nothingDueSuppressesTheNeverCompletedCaseToo() {
        assertEquals(SimpleTimer2.WatchdogDecision.OK,
                     SimpleTimer2.evaluateStall(0L, 0L, WATCHDOG_STALL_SAMPLES,
                                               WATCHDOG_STALL_SAMPLES, false, 0L, false));
    }

    /////////////// work due and not run is the real fault

    @Test
    public void workDueWithNoProgressIsStillReported() {
        assertEquals("the fix must not silence a genuinely wedged timer",
                     SimpleTimer2.WatchdogDecision.REPORT, quietShard(true));
    }

    @Test
    public void workDueButProgressingIsNotReported() {
        assertEquals(SimpleTimer2.WatchdogDecision.OK,
                     SimpleTimer2.evaluateStall(9L, 8L, 0, WATCHDOG_STALL_SAMPLES,
                                               false, GAP_BASELINE, true));
    }

    @Test
    public void aBusyTimerIsUnaffected() {
        assertEquals(SimpleTimer2.WatchdogDecision.OK,
                     SimpleTimer2.evaluateStall(5000L, 4990L, 0, WATCHDOG_STALL_SAMPLES,
                                               false, GAP_BASELINE, true));
    }

    /////////////// the work-due gate is additive, not a replacement

    @Test
    public void theExistingCalibrationStillAppliesWhenWorkIsDue() {
        // Work due, but the quiet period is still inside twice the timer's own longest
        // recovered gap, so the pre-existing calibration must keep it quiet.
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     SimpleTimer2.evaluateStall(5L, 5L, WATCHDOG_STALL_SAMPLES,
                                               WATCHDOG_STALL_SAMPLES, false,
                                               600_000L, true));
    }

    @Test
    public void theSixArgumentOverloadAssumesWorkIsDue() {
        // Kept for callers that cannot test for work; must behave as before the gate.
        assertEquals(SimpleTimer2.evaluateStall(1L, 1L, WATCHDOG_STALL_SAMPLES,
                                                WATCHDOG_STALL_SAMPLES, false, GAP_BASELINE),
                     SimpleTimer2.evaluateStall(1L, 1L, WATCHDOG_STALL_SAMPLES,
                                                WATCHDOG_STALL_SAMPLES, false,
                                                GAP_BASELINE, true));
    }

    @Test
    public void anAlreadyReportedEpisodeIsNotReportedTwice() {
        assertEquals(SimpleTimer2.WatchdogDecision.PENDING,
                     SimpleTimer2.evaluateStall(1L, 1L, WATCHDOG_STALL_SAMPLES,
                                               WATCHDOG_STALL_SAMPLES, true,
                                               GAP_BASELINE, true));
    }

    /////////////// recovery hysteresis

    @Test
    public void recoveryNeedsMoreThanOneSample() {
        // One lucky completion must not end the episode, or a timer that finishes
        // something every few minutes re-reports the same condition every window.
        assertTrue("recovery should require sustained progress",
                   WATCHDOG_RECOVERY_SAMPLES > 1);
    }
}