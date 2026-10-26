package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The scaler's "did that scale-up help?" decision.
 *
 * <p>The verdict decides whether threads get destroyed, so it has to be taken
 * on values that represent the period on both sides of the scale event. The
 * comparison used to pit one instantaneous sample of the ready-job depth and
 * the worst lag against one other sample, and it compared the transport send
 * delay against the <em>job lag</em> baseline — two unrelated metrics — so a
 * scale-up was rolled back for load that extra runners were never going to
 * absorb.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerFeedbackTest {

    /** A quiet queue: 10 ms avg lag, 100 ms send delay, 20 ready, 50 ms worst. */
    private static JobQueueScaler.PreScaleSnapshot quiet() {
        return new JobQueueScaler.PreScaleSnapshot(10L, 2, 100L, 20L, 50L);
    }

    /** Unchanged load is not a failure. */
    @Test
    public void unchangedLoadIsNotDegraded() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, false, 20L, 50L));
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 1L, 1L, false, false, 1L, 1L));
    }

    /** Dropped jobs and a nearly-full heap are emergencies: one sample decides. */
    @Test
    public void emergenciesDegradeImmediately() {
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, true, false, 20L, 50L));
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, true, 20L, 50L));
    }

    /** Average job lag: 1.5x is the boundary, strictly above it degrades. */
    @Test
    public void avgLagBoundary() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 15L, 100L, false, false, 20L, 50L));
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 16L, 100L, false, false, 20L, 50L));
    }

    /** Smoothed worst lag, same boundary. */
    @Test
    public void smoothMaxLagBoundary() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, false, 20L, 75L));
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, false, 20L, 76L));
    }

    /** Smoothed ready-job depth, same boundary. */
    @Test
    public void smoothReadyJobsBoundary() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, false, 30L, 50L));
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 10L, 100L, false, false, 31L, 50L));
    }

    /** Send delay is judged against the send delay captured before the scale. */
    @Test
    public void sendDelayBoundary() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 150L, false, false, 20L, 50L));
        assertTrue(JobQueueScaler.feedbackDegraded(quiet(), 10L, 151L, false, false, 20L, 50L));
    }

    /**
     * The units fix. The old comparison was
     * {@code currentMessageDelay > snapshot.avgLag * 1.5}, so with a 10 ms job
     * lag any send delay above 15 ms — a normal, healthy router — condemned the
     * scale-up. A send delay of 20 ms with the job lag unchanged is not a
     * failure, and must not roll threads away.
     */
    @Test
    public void sendDelayIsNotJudgedAgainstJobLag() {
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 20L, false, false, 20L, 50L));
        assertFalse(JobQueueScaler.feedbackDegraded(quiet(), 10L, 99L, false, false, 20L, 50L));
    }

    /** A zero baseline means no data, and no data is not evidence of failure. */
    @Test
    public void zeroBaselinesDegradeNothing() {
        JobQueueScaler.PreScaleSnapshot zero =
            new JobQueueScaler.PreScaleSnapshot(0L, 2, 0L, 0L, 0L);
        assertFalse(JobQueueScaler.feedbackDegraded(zero, 1000L, 1000L, false, false, 1000L, 1000L));
    }

    /** A zero baseline with an emergency still degrades - the gate is separate. */
    @Test
    public void zeroBaselinesDoNotMaskEmergencies() {
        JobQueueScaler.PreScaleSnapshot zero =
            new JobQueueScaler.PreScaleSnapshot(0L, 2, 0L, 0L, 0L);
        assertTrue(JobQueueScaler.feedbackDegraded(zero, 0L, 0L, true, false, 0L, 0L));
    }
}
