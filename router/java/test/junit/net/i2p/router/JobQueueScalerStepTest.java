package net.i2p.router;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * The scaler's scale-up decision: how many runners one pass adds, and what may
 * enlarge that number.
 *
 * <p>The step used to be sized by the backlog signals and then widened by
 * network congestion as well, in the same pass that had just suppressed a
 * routine scale-up because the network, not the thread count, was the
 * bottleneck. One signal therefore had two opposite readings, and a congested
 * minute could both hold a scale-up back and make it bigger. The step is now
 * sized by the backlog alone, and these pin that, along with the ladder's
 * boundaries — the delay tiers, the proportional path's own cap, and the
 * ceiling every signal together may not pass.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerStepTest {

    /**
     * A lag threshold high enough that the delay-proportional branch stays off,
     * so a case can pin the delay tiers on their own.
     */
    private static final int QUIET_THRESHOLD = 1000;

    /**
     * The step ladder's cases. Every case calls the static decision, so the
     * instance holds no state and needs no router or job queue.
     */
    public JobQueueScalerStepTest() {
    }

    /**
     * The step for a backlog of one oldest job's delay, with no other signal
     * firing and the delay-proportional branch held off.
     *
     * @param maxLag ms the oldest ready job has waited
     * @return runners the pass would add
     */
    private static int stepForLag(long maxLag) {
        return JobQueueScaler.scaleUpStep(maxLag, QUIET_THRESHOLD, false, 0, false, false, 0);
    }

    /**
     * The step with nothing but a trigger to explain it: the floor, not zero,
     * because a scale-up that adds nothing leaves the queue exactly as loaded
     * as it was and buys another cooldown before the next attempt.
     */
    @Test
    public void theFloorStepIsTwo() {
        assertEquals(2, stepForLag(0));
    }

    /**
     * The 500 ms tier is exclusive: at it, the step is still the floor.
     */
    @Test
    public void halfASecondOfLagIsNotYetATier() {
        assertEquals(2, stepForLag(500));
        assertEquals(3, stepForLag(501));
    }

    /**
     * The 1 s tier is exclusive too, and it tops the tier ladder — nothing
     * climbs further by tier alone.
     */
    @Test
    public void aSecondOfLagTopsTheTiers() {
        assertEquals(3, stepForLag(1000));
        assertEquals(4, stepForLag(1001));
        assertEquals(4, stepForLag(4999));
    }

    /**
     * Once lag is more than five times the configured threshold, the step is
     * sized from the lag itself — and that path has its own cap of 6, below the
     * ceiling, so the other signals still have room to add to it.
     */
    @Test
    public void aLateQueueSizesTheStepOnItsOwnScale() {
        // threshold 100ms, so the branch starts above 500ms of lag
        assertEquals(3, JobQueueScaler.scaleUpStep(600, 100, false, 0, false, false, 0));
        assertEquals(6, JobQueueScaler.scaleUpStep(1200, 100, false, 0, false, false, 0));
        // far past that, the branch's own cap holds it at 6
        assertEquals(6, JobQueueScaler.scaleUpStep(4000, 100, false, 0, false, false, 0));
    }

    /**
     * The proportional branch starts above five times the threshold, not at it,
     * and its answer can be smaller than the tiers it replaces.
     */
    @Test
    public void theProportionalBranchStartsAboveFiveTimesTheThreshold() {
        // 5000 against a 1000ms threshold is exactly 5x, so the tiers alone
        // still size the step
        assertEquals(4, stepForLag(5000));
        // one ms past it, 5001/1000/2 sizes the step at 2
        assertEquals(2, JobQueueScaler.scaleUpStep(5001, 1000, false, 0, false, false, 0));
    }

    /**
     * A threshold of 0 or less turns the proportional path off rather than
     * dividing by it: the tier ladder still sizes the step.
     */
    @Test
    public void aZeroThresholdNeverDivides() {
        assertEquals(4, JobQueueScaler.scaleUpStep(5000, 0, false, 0, false, false, 0));
        assertEquals(4, JobQueueScaler.scaleUpStep(5000, -5, false, 0, false, false, 0));
    }

    /**
     * Rapid queue growth adds the ratio of that growth to one check's worth of
     * jobs, and a growth under one check's worth adds nothing.
     */
    @Test
    public void queueGrowthAddsItsRatio() {
        assertEquals(3, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, true, 2, false, false, 0));
        assertEquals(5, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, true, 6, false, false, 0));
        assertEquals(2, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, true, 1, false, false, 0));
    }

    /**
     * Jobs being dropped outright is the strongest backlog signal there is, and
     * adds a flat two.
     */
    @Test
    public void droppedJobsAddTwo() {
        assertEquals(4, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, false, 0, true, false, 0));
    }

    /**
     * TestJobs count a third of a runner each, and always worth at least one,
     * so a queue with a handful of them still gets a runner rather than nothing.
     */
    @Test
    public void dominantTestJobsAddAtLeastOne() {
        assertEquals(3, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, false, 0, false, true, 1));
        assertEquals(3, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, false, 0, false, true, 2));
        assertEquals(5, JobQueueScaler.scaleUpStep(0, QUIET_THRESHOLD, false, 0, false, true, 9));
    }

    /**
     * Every signal at once cannot take more than the ceiling from one pass:
     * each scale event creates OS threads, and the feedback check only judges
     * the pass after it has run.
     */
    @Test
    public void everySignalTogetherStaysUnderTheCeiling() {
        assertEquals(8, JobQueueScaler.scaleUpStep(1001, 100, true, 8, true, true, 30));
        assertEquals(8, JobQueueScaler.scaleUpStep(60_000, 1, true, 1000, true, true, 10_000));
    }

    /**
     * The step is sized by the backlog alone. A saturation signal that let this
     * pass through on an emergency override must not also enlarge it: the gate
     * that suppressed the routine scale-up already read that saturation as
     * evidence that more threads are not the answer. Pinning the decision's
     * inputs makes re-adding one fail here, in a message, rather than surfacing
     * later as a step nobody can attribute.
     */
    @Test
    public void noSaturationSignalEnlargesTheStep() {
        int found = 0;
        for (Method m : JobQueueScaler.class.getDeclaredMethods()) {
            if ("scaleUpStep".equals(m.getName())) {
                found++;
                assertEquals("scaleUpStep() takes the backlog signals only",
                             7, m.getParameterCount());
            }
        }
        assertEquals("scaleUpStep() is the decision that sizes a scale-up", 1, found);
    }
}
