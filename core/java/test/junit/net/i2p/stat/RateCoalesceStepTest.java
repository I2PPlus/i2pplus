package net.i2p.stat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the coalesce step arithmetic: {@link Rate#archiveStep},
 * {@link Rate#coalesceDueMs} and {@link Rate#isBacklogCollapse}.
 *
 * <p>This is the crux of the change. A rate's RRD is created with an archive step
 * of {@code period/1000}, so the step a sample belongs to is
 * {@code timestampMs / period}. Stamping a late tick with the current wall clock
 * puts its aggregate into whichever step the clock happens to be in rather than
 * the step whose data it is, and with the router's 50s coalesce timer driving a
 * 60s period that is the ordinary case rather than an edge case.
 *
 * <p>These are pure functions of their arguments, so nothing here sleeps,
 * allocates a rate, or needs a router.
 *
 * @since 0.9.71+
 */
public class RateCoalesceStepTest {

    /** The router's default graph period. */
    private static final long PERIOD = 60000;

    /** A grid origin deliberately off any step boundary. */
    private static final long OFF_GRID = 1_700_000_123_456L;

    @Test
    public void archiveStepIsTimestampOverPeriod() {
        assertEquals(0, Rate.archiveStep(0, PERIOD));
        assertEquals(0, Rate.archiveStep(PERIOD - 1, PERIOD));
        assertEquals(1, Rate.archiveStep(PERIOD, PERIOD));
        assertEquals(1, Rate.archiveStep(2 * PERIOD - 1, PERIOD));
        assertEquals(2, Rate.archiveStep(2 * PERIOD, PERIOD));
        assertEquals(7, Rate.archiveStep(7 * PERIOD + 12345, PERIOD));
    }

    /**
     * With a 1s period, a step is a whole second, so the formula degenerates to
     * the timestamp itself. Pinned because the tests for {@code Rate} rely on that
     * to compare steps by comparing stamps.
     */
    @Test
    public void archiveStepWithUnitPeriodIsTheTimestamp() {
        assertEquals(OFF_GRID, Rate.archiveStep(OFF_GRID, 1));
    }

    @Test
    public void dueIsOnePeriodAfterTheLastCoalesce() {
        assertEquals(OFF_GRID + PERIOD, Rate.coalesceDueMs(OFF_GRID, PERIOD));
        assertEquals(PERIOD, Rate.coalesceDueMs(0, PERIOD));
    }

    /**
     * The property that makes the grid a grid: consecutive coalesces are exactly
     * one period apart on the timestamp, therefore exactly one step apart in the
     * archive — however far off the step boundary the rate was created.
     */
    @Test
    public void consecutiveDueStepsDifferByExactlyOne() {
        long last = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        long prevStep = Rate.archiveStep(last, PERIOD);
        for (int i = 0; i < 20; i++) {
            long due = Rate.coalesceDueMs(last, PERIOD);
            long step = Rate.archiveStep(due, PERIOD);
            assertEquals("due stamp must advance by exactly one period",
                         last + PERIOD, due);
            assertEquals("consecutive samples must land one step apart", prevStep + 1, step);
            prevStep = step;
            last = due;
        }
    }

    /**
     * The defect being fixed, shown rather than described. A tick that arrives 10s
     * late is well inside one period, so nothing collapses and no data is missing —
     * this is the ordinary case with a 50s timer on a 60s period, not an edge case.
     * Stamping it with the current clock nevertheless lands it in the step the
     * previous tick already wrote, and only one of the two is storable.
 */
    @Test
    public void aLateClockStampRewritesAnAlreadyUsedStep() {
        long firstDue = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        long lateVisit = firstDue + 10000;
        assertFalse("a tick 10s late is not a collapsed backlog",
                    Rate.isBacklogCollapse(lateVisit, firstDue, PERIOD));

        long firstStep = Rate.archiveStep(firstDue, PERIOD);
        assertEquals("a clock-stamped late tick lands in the step already written",
                     firstStep, Rate.archiveStep(lateVisit, PERIOD));
        assertEquals("the grid stamp moves the same tick to the next step instead",
                     firstStep + 1,
                     Rate.archiveStep(Rate.coalesceDueMs(firstDue, PERIOD), PERIOD));
    }

    @Test
    public void noCollapseWhileTheBacklogIsWithinOnePeriod() {
        long due = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        assertFalse(Rate.isBacklogCollapse(due, OFF_GRID, PERIOD));
        assertFalse(Rate.isBacklogCollapse(due + 1, OFF_GRID, PERIOD));
        assertFalse(Rate.isBacklogCollapse(due + PERIOD - 1, OFF_GRID, PERIOD));
    }

    @Test
    public void collapseAtExactlyOnePeriodBehind() {
        long due = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        assertTrue("one whole period behind is the boundary and collapses",
                   Rate.isBacklogCollapse(due + PERIOD, OFF_GRID, PERIOD));
    }

    @Test
    public void collapseWhenTheTimerWasStarvedForSeveralPeriods() {
        long due = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        assertTrue(Rate.isBacklogCollapse(due + PERIOD + 1, OFF_GRID, PERIOD));
        assertTrue(Rate.isBacklogCollapse(due + 37 * PERIOD, OFF_GRID, PERIOD));
    }

    /**
     * A collapse needs the backlog to exceed a whole period, so a timer that
     * overshoots by less than one period never loses a step to collapse — the
     * common late case stays on the grid.
     */
    @Test
    public void lateButWithinOnePeriodDoesNotCollapse() {
        long due = Rate.coalesceDueMs(OFF_GRID, PERIOD);
        for (long lateness = 0; lateness < PERIOD; lateness += PERIOD / 8)
            assertFalse("lateness " + lateness + "ms must not collapse",
                        Rate.isBacklogCollapse(due + lateness, OFF_GRID, PERIOD));
    }

    /**
     * The collapse threshold is two periods measured from the last coalesce, so a
     * rate with period {@code p} tolerates a timer gap of {@code 2p} and collapses
     * beyond it. Pins the constant so a future edit to the slack cannot silently
     * halve the tolerance.
     */
    @Test
    public void collapseThresholdIsTwoPeriodsFromTheLastCoalesce() {
        for (long period : new long[] {1, 2100, PERIOD, 3600000}) {
            assertFalse("at exactly 2p - 1", Rate.isBacklogCollapse(2 * period - 1, 0, period));
            assertTrue("at exactly 2p", Rate.isBacklogCollapse(2 * period, 0, period));
        }
    }

    /**
     * {@link Rate#coalesce()} only reaches these helpers once the period is due
     * minus slack, so an early visit never advances the grid and never collapses.
     */
    @Test
    public void earlyVisitsAreNeitherDueNorCollapsed() {
        long last = OFF_GRID;
        long earlyVisit = last + PERIOD / 2;
        assertFalse(Rate.isBacklogCollapse(earlyVisit, last, PERIOD));
        assertTrue("the due instant is still ahead of an early visit",
                   Rate.coalesceDueMs(last, PERIOD) > earlyVisit);
    }
}
