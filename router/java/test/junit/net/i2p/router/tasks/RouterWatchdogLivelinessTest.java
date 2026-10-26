package net.i2p.router.tasks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The watchdog's "a job has been running too long" decision.
 *
 * <p>This reading is not cosmetic: a false hit writes a CRIT line, dumps every
 * thread in the router, and after enough consecutive hits with the wrapper
 * present forces a restart. It used the start time of the last job to run, a
 * value that is never cleared when the job finishes, so a runner parked for a
 * minute and then handed a job was reported as a job that had been running for
 * that whole minute - and the router barked at an idle, healthy queue.
 *
 * @since 0.9.72+
 */
public class RouterWatchdogLivelinessTest {

    /** The watchdog's own limit. */
    private static final long LIMIT = 60 * 1000L;

    /** Nothing in flight is never a hang, whatever the clock says. */
    @Test
    public void idleQueueIsNeverAHang() {
        assertEquals(-1, RouterWatchdog.runningTooLong(-1, 1_000_000L, LIMIT));
        // A runner that has never run a job reports 0. That must not read as
        // "running since the epoch", which is what the old check made of it.
        assertEquals(-1, RouterWatchdog.runningTooLong(0, System.currentTimeMillis(), LIMIT));
        assertEquals(-1, RouterWatchdog.runningTooLong(-5, 1_000_000L, LIMIT));
    }

    /** A job inside the budget is healthy, at the boundary and below it. */
    @Test
    public void jobWithinBudgetIsHealthy() {
        long now = 1_000_000L;
        assertEquals(-1, RouterWatchdog.runningTooLong(now, now, LIMIT));
        assertEquals(-1, RouterWatchdog.runningTooLong(now - LIMIT + 1, now, LIMIT));
        assertEquals(-1, RouterWatchdog.runningTooLong(now - LIMIT, now, LIMIT));
    }

    /** Past the budget, the figure reported is how long the job has been running. */
    @Test
    public void jobPastBudgetReportsItsRuntime() {
        long now = 1_000_000L;
        // one ms past the boundary is enough to report
        assertEquals(LIMIT + 1, RouterWatchdog.runningTooLong(now - LIMIT - 1, now, LIMIT));
        assertEquals(120_000L, RouterWatchdog.runningTooLong(now - 120_000L, now, LIMIT));
    }
}
