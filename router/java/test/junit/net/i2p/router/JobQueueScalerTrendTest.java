package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The scaler's early-warning signals: when a growing queue is a trend worth
 * acting on, and when a backlog has wanted runners long enough to act.
 *
 * <p>Both used to be looser than the router's own noise. A ready depth of four
 * jobs moving by three between two checks counted as rapid growth, and any
 * trend signal waived the three-check requirement entirely, so one sample could
 * open a scale event. Measured on a running router over 4,059 checks: 51
 * scale-ups, 63 scale-downs and 167 runner threads created, while the worst
 * ready-job lag averaged 0.38ms — the scaler was churning threads against a
 * queue that was never behind.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerTrendTest {

    /**
     * The early-warning cases. Every case calls a static decision, so the
     * instance holds no state and needs no router or job queue.
     */
    public JobQueueScalerTrendTest() {
    }

    /**
     * Jitter in a shallow queue is not growth: the depth has to be approaching
     * the pool that serves it before a rise means runners are needed.
     */
    @Test
    public void aShallowQueueDoesNotTrend() {
        // the measured idle case: 4 ready jobs against 19 runners
        assertFalse(JobQueueScaler.rapidQueueGrowth(3, 4, 19));
        assertFalse(JobQueueScaler.rapidQueueGrowth(10, 4, 19));
    }

    /**
     * A backlog climbing toward the size of its runner pool is the early
     * warning: the ratio gate needs twice that much backlog before it opens.
     */
    @Test
    public void aBacklogApproachingThePoolTrends() {
        assertTrue(JobQueueScaler.rapidQueueGrowth(3, 10, 19));
        assertTrue(JobQueueScaler.rapidQueueGrowth(3, 40, 19));
    }

    /**
     * The depth test is half the pool, exclusive: at exactly half, the queue is
     * still being served faster than it fills.
     */
    @Test
    public void halfThePoolIsNotYetABacklog() {
        assertFalse(JobQueueScaler.rapidQueueGrowth(3, 9, 19));
        assertTrue(JobQueueScaler.rapidQueueGrowth(3, 10, 19));
        assertFalse(JobQueueScaler.rapidQueueGrowth(3, 10, 20));
        assertTrue(JobQueueScaler.rapidQueueGrowth(3, 11, 20));
    }

    /**
     * Growth must still exceed one check's worth of jobs, however deep the
     * backlog is — a queue that is merely large is not a queue that is filling.
     */
    @Test
    public void aSteadyBacklogIsNotGrowth() {
        assertFalse(JobQueueScaler.rapidQueueGrowth(2, 40, 19));
        assertFalse(JobQueueScaler.rapidQueueGrowth(0, 40, 19));
        assertFalse(JobQueueScaler.rapidQueueGrowth(-5, 40, 19));
    }

    /**
     * Work with no runner to serve it trends immediately, however small the
     * queue: there is nothing to compare the depth against.
     */
    @Test
    public void workWithNoRunnersTrends() {
        assertTrue(JobQueueScaler.rapidQueueGrowth(3, 1, 0));
    }

    /**
     * Three checks in a row must want the runners before they are added.
     */
    @Test
    public void aTrendMustBeSustained() {
        assertFalse(JobQueueScaler.sustainedOrEmergency(0, false, false, false));
        assertFalse(JobQueueScaler.sustainedOrEmergency(1, false, false, false));
        assertFalse(JobQueueScaler.sustainedOrEmergency(2, false, false, false));
        assertTrue(JobQueueScaler.sustainedOrEmergency(3, false, false, false));
        assertTrue(JobQueueScaler.sustainedOrEmergency(4, false, false, false));
    }

    /**
     * A lag at the critical multiple, a wedged active job, or jobs being
     * dropped cannot wait out the count.
     */
    @Test
    public void anEmergencySkipsTheCount() {
        assertTrue(JobQueueScaler.sustainedOrEmergency(1, true, false, false));
        assertTrue(JobQueueScaler.sustainedOrEmergency(1, false, true, false));
        assertTrue(JobQueueScaler.sustainedOrEmergency(1, false, false, true));
    }
}
