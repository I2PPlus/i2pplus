package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The scaler's scale-down decisions: how long a scale-up gets before a
 * scale-down may undo it, and how many runners one scale-down may take.
 *
 * <p>Both used to be open-loop. The scale-down step was fixed at 2 against a
 * scale-up step of 1, it required 2 quiet checks against the 3 a scale-up
 * needed, and it had no minimum dwell, so one quiet interval removed runners
 * the previous event had just created — and the next scale-up made them again.
 * Each of those cycles created and destroyed OS threads.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerDecisionTest {

    /** The scaler's own dwell for a 10 s cooldown. */
    private static final long DWELL = 20_000L;

    /** Nothing has scaled yet, so there is nothing to hold a scale-down for. */
    @Test
    public void noPreviousScaleEarnsNoDwell() {
        assertFalse(JobQueueScaler.dwellAfterScaleUp(0, true, 100_000L, DWELL));
        assertFalse(JobQueueScaler.dwellAfterScaleUp(0, false, 100_000L, DWELL));
    }

    /** Only a scale-up earns the dwell; a scale-down has nothing to undo. */
    @Test
    public void onlyAScaleUpEarnsTheDwell() {
        long scaledAt = 100_000L;
        assertTrue(JobQueueScaler.dwellAfterScaleUp(scaledAt, true, scaledAt + 1, DWELL));
        assertFalse(JobQueueScaler.dwellAfterScaleUp(scaledAt, false, scaledAt + 1, DWELL));
    }

    /** The dwell is inclusive up to its length, then it is over. */
    @Test
    public void dwellEndsAtItsLength() {
        long scaledAt = 100_000L;
        assertTrue(JobQueueScaler.dwellAfterScaleUp(scaledAt, true, scaledAt + DWELL - 1, DWELL));
        assertFalse(JobQueueScaler.dwellAfterScaleUp(scaledAt, true, scaledAt + DWELL, DWELL));
        assertFalse(JobQueueScaler.dwellAfterScaleUp(scaledAt, true, scaledAt + DWELL + 1, DWELL));
    }

    /** Nothing above the floor, nothing to remove. */
    @Test
    public void noExcessMeansNothingToRemove() {
        assertEquals(0, JobQueueScaler.scaleDownStep(8, 8, 0, 2));
        assertEquals(0, JobQueueScaler.scaleDownStep(5, 8, 4, 2));
    }

    /** With no scale-up to bound by, half the excess goes, at least the step. */
    @Test
    public void stepOrHalfTheExcess() {
        // excess 1: the step is 2, so the excess caps it
        assertEquals(1, JobQueueScaler.scaleDownStep(9, 8, 0, 2));
        // excess 2: the step
        assertEquals(2, JobQueueScaler.scaleDownStep(10, 8, 0, 2));
        // excess 10: half of it
        assertEquals(5, JobQueueScaler.scaleDownStep(18, 8, 0, 2));
    }

    /**
     * A scale-down never takes more than the last scale-up added, so it cannot
     * drive the count below the level the scale-up judged necessary.
     */
    @Test
    public void scaleDownIsBoundedByTheLastScaleUp() {
        // excess 10, last scale-up added 3 -> remove 3, not 5
        assertEquals(3, JobQueueScaler.scaleDownStep(18, 8, 3, 2));
        // a scale-up of 1 earns a scale-down of 1: the symmetric pair
        assertEquals(1, JobQueueScaler.scaleDownStep(18, 8, 1, 2));
        // the bound is spent as runners go, so a second pass is capped lower
        assertEquals(2, JobQueueScaler.scaleDownStep(15, 8, 2, 2));
    }

    /** The bound never asks for more than the excess available. */
    @Test
    public void boundNeverExceedsExcess() {
        assertEquals(1, JobQueueScaler.scaleDownStep(9, 8, 8, 2));
        assertEquals(2, JobQueueScaler.scaleDownStep(10, 8, 8, 2));
    }

    /**
     * The scale-down trigger: average lag under the threshold and a
     * job-to-runner ratio under 1. Both are required, and the ready-job depth
     * is not required to reach zero.
     */
    @Test
    public void scaleDownTriggerNeedsBothHalves() {
        assertTrue(JobQueueScaler.wantsScaleDown(4L, 7, 8, 5));
        // lag at the threshold is not low
        assertFalse(JobQueueScaler.wantsScaleDown(5L, 7, 8, 5));
        // jobs equal to runners is not a surplus
        assertFalse(JobQueueScaler.wantsScaleDown(4L, 8, 8, 5));
        // neither half alone is enough
        assertFalse(JobQueueScaler.wantsScaleDown(9L, 7, 8, 5));
        assertFalse(JobQueueScaler.wantsScaleDown(4L, 9, 8, 5));
        // an idle queue with a runner present
        assertTrue(JobQueueScaler.wantsScaleDown(0L, 0, 1, 5));
    }
}
