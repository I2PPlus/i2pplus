package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The limits on what the scaler may do to the runner count: the gates in front
 * of a scale-up, which conditions are urgent enough to skip the pacing they
 * impose, and the floor under every removal — the configured minimum plus
 * whatever subsystems are holding under lease.
 *
 * <p>The breaker's dropped-jobs override used to be only a log line. The branch
 * that logged "allowing emergency scale-up" left both the breaker and the
 * cooldown in place, so no scale-up could follow it, and the pass went on to the
 * scale-down path while the queue was shedding work. The rollback floored itself
 * at the configured minimum alone and so took back runners a subsystem was still
 * counting on, which the floor path handed straight back on the next check. And
 * the emergency override answered a dropped-job storm with more threads even
 * when the machine was out of CPU or the bandwidth limiter was holding the
 * queue. These pin which conditions the override outranks, where a removal
 * stops, and when more runners cannot help at all.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerGateTest {

    /**
     * The scale-up gate's cases. Every case calls the static decision, so the
     * instance holds no state and needs no router or job queue.
     */
    public JobQueueScalerGateTest() {
    }

    /**
     * Out of cooldown, under the ceiling, breaker closed: the check may act.
     */
    @Test
    public void aQuietCheckUnderTheCeilingMayAct() {
        assertTrue(JobQueueScaler.canScaleUp(false, 8, 24, false, false));
    }

    /**
     * The cooldown paces the scale events so each one gets a chance to show an
     * effect before the next is taken.
     */
    @Test
    public void cooldownHoldsTheNextEventBack() {
        assertFalse(JobQueueScaler.canScaleUp(true, 8, 24, false, false));
    }

    /**
     * The ceiling is a ceiling: at it and above it there is nothing to add.
     */
    @Test
    public void theCeilingStandsRegardlessOfTheOtherGates() {
        assertFalse(JobQueueScaler.canScaleUp(false, 24, 24, false, false));
        assertFalse(JobQueueScaler.canScaleUp(false, 25, 24, false, false));
        // not even dropped jobs get runners past the ceiling
        assertFalse(JobQueueScaler.canScaleUp(true, 24, 24, true, true));
    }

    /**
     * An open breaker stops scale-ups, which is what it is for.
     */
    @Test
    public void anOpenBreakerStopsScaleUps() {
        assertFalse(JobQueueScaler.canScaleUp(false, 8, 24, true, false));
        assertFalse(JobQueueScaler.canScaleUp(true, 8, 24, true, false));
    }

    /**
     * Jobs being dropped outrank an open breaker and the cooldown it implies —
     * the case the bypass was written for and did not deliver.
     */
    @Test
    public void droppedJobsOutrankAnOpenBreaker() {
        assertTrue(JobQueueScaler.canScaleUp(true, 8, 24, true, true));
        assertTrue(JobQueueScaler.canScaleUp(false, 8, 24, true, true));
    }

    /**
     * With the breaker closed, dropped jobs still outrank the cooldown. A queue
     * that has begun shedding work cannot wait out a pacing interval chosen for
     * ordinary events; the feedback check, not the cooldown, is what keeps a
     * drop storm from adding runners every check.
     */
    @Test
    public void droppedJobsOutrankTheCooldown() {
        assertTrue(JobQueueScaler.canScaleUp(true, 8, 24, false, true));
        assertTrue(JobQueueScaler.canScaleUp(false, 8, 24, false, true));
    }

    /**
     * A rollback takes back no more than the failed scale-up added, and no more
     * than the pool can spare above the floor.
     */
    @Test
    public void aRollbackTakesBackOnlyWhatTheScaleUpAdded() {
        assertEquals(4, JobQueueScaler.rollbackCount(4, 20, 8));
        assertEquals(2, JobQueueScaler.rollbackCount(6, 10, 8));
    }

    /**
     * At or below the floor there is nothing to take back, and the answer is
     * never a negative number of runners.
     */
    @Test
    public void aRollbackStopsAtTheFloor() {
        assertEquals(0, JobQueueScaler.rollbackCount(3, 8, 8));
        assertEquals(0, JobQueueScaler.rollbackCount(3, 7, 8));
    }

    /**
     * The floor counts the runners subsystems hold under lease, so a rollback
     * cannot undo a runner another subsystem is still counting on — the pair of
     * thread creations that took it away and gave it back again.
     */
    @Test
    public void aRollbackRespectsAHeldLease() {
        // 12 runners, a base minimum of 4, and a lease holding 6 of them
        assertEquals(2, JobQueueScaler.rollbackCount(4, 12, 10));
        assertEquals(0, JobQueueScaler.rollbackCount(4, 10, 10));
    }

    /**
     * A queue that is behind because the machine is out of CPU, or because the
     * bandwidth limiter is holding its jobs, is not answered by another thread.
     */
    @Test
    public void anEmergencyIsNotActionableWhenTheMachineIsTheBottleneck() {
        assertFalse(JobQueueScaler.emergencyActionable(true, false, false, false,
                                                       true, false, false));
        assertFalse(JobQueueScaler.emergencyActionable(false, true, false, false,
                                                       false, true, false));
        assertFalse(JobQueueScaler.emergencyActionable(false, false, true, false,
                                                       false, false, true));
        assertFalse(JobQueueScaler.emergencyActionable(true, true, true, true,
                                                       true, true, true));
    }

    /**
     * With the machine's own resources free, each of the four signals is
     * actionable on the first check that sees it.
     */
    @Test
    public void anEmergencyIsActionableWhenRunnersAreTheBottleneck() {
        assertTrue(JobQueueScaler.emergencyActionable(true, false, false, false,
                                                       false, false, false));
        assertTrue(JobQueueScaler.emergencyActionable(false, true, false, false,
                                                       false, false, false));
        assertTrue(JobQueueScaler.emergencyActionable(false, false, true, false,
                                                       false, false, false));
        assertTrue(JobQueueScaler.emergencyActionable(false, false, false, true,
                                                       false, false, false));
    }

    /**
     * A quiet check reports no emergency at all, whatever the machine is doing.
     */
    @Test
    public void aQuietCheckIsNeverAnEmergency() {
        assertFalse(JobQueueScaler.emergencyActionable(false, false, false,
                                                       false, false, false, false));
        assertFalse(JobQueueScaler.emergencyActionable(false, false, false,
                                                       false, true, true, true));
    }

    /**
     * A low tunnel build success rate is congestion, and congestion is what the
     * queue's own exploratory and LeaseSet jobs relieve, so it is not counted
     * with the machine's own saturation.
     */
    @Test
    public void networkCongestionIsNotLocalSaturation() {
        assertFalse(JobQueueScaler.localResourcesSaturated(false, false, false));
        assertTrue(JobQueueScaler.localResourcesSaturated(true, false, false));
        assertTrue(JobQueueScaler.localResourcesSaturated(false, true, false));
        assertTrue(JobQueueScaler.localResourcesSaturated(false, false, true));
    }
}
