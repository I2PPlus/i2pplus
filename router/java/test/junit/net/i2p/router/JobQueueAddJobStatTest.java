package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link JobQueue#addJob(Job)} still reports the same two numbers it
 * always reported, now that it feeds them through cached stat handles instead of
 * looking them up by name on every enqueue.
 * <p>
 * Both stats feed the console's job-queue graphs, and the whole point of the
 * change is that nothing about the recorded data moves: {@code readyJobs} gets
 * how many jobs were already ready at the moment of the add, {@code queuedJobs}
 * gets the size of the future-scheduled set after it, one sample per add, in the
 * order of the adds. Getting those two mixed up - or quietly dropping one - is
 * invisible to the router and visible only as a graph that stops matching the
 * queue it names, so the values are pinned one sequence at a time.
 * <p>
 * Each case uses a job queue of its own rather than the context's, because the
 * dummy subsystems schedule their own jobs on that one and their adds would land
 * in the same two shared stats.
 *
 * @since 0.9.72+
 */
public class JobQueueAddJobStatTest {

    /** Start time far enough ahead that the pumper leaves a scheduled job alone. */
    private static final long FUTURE_MS = 10 * 60 * 1000L;

    private RouterContext _ctx;
    private JobQueue _queue;

    /**
     * Build a context with its own stat manager and a job queue that has a pumper
     * but no runners, so every add stays observable in the queues.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _queue = new JobQueue(_ctx);
        _queue.startup();
    }

    /**
     * Stop the queue's pumper and scaler.
     */
    @After
    public void tearDown() {
        if (_queue != null) {
            _queue.shutdown();
        }
    }

    /**
     * The one-minute rate behind a rate stat the queue reports.
     *
     * @param name the stat name as the console sees it
     * @return the 1-minute rate, never null
     */
    private Rate rate(String name) {
        RateStat stat = _ctx.statManager().getRate(name);
        assertNotNull("stat not registered: " + name, stat);
        Rate rv = stat.getRate(RateConstants.ONE_MINUTE);
        assertNotNull("stat has no 1-minute rate: " + name, rv);
        return rv;
    }

    /**
     * Read a private field of the job queue.
     *
     * @param name the private field name on JobQueue
     * @return the field's value
     * @throws Exception if the field is missing or reflection may not read it
     */
    private Object field(String name) throws Exception {
        Field f = JobQueue.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(_queue);
    }

    /**
     * The core regression: three ready adds feed {@code readyJobs} the ready
     * count each add saw (0, then 1, then 2) and feed {@code queuedJobs} zero
     * three times, because nothing was scheduled for the future.
     */
    @Test
    public void testReadyAddsReportTheReadyCountEachAddSaw() {
        Rate ready = rate("jobQueue.readyJobs");
        Rate queued = rate("jobQueue.queuedJobs");
        long readyEvents = ready.getCurrentEventCount();
        double readyTotal = ready.getCurrentTotalValue();
        long queuedEvents = queued.getCurrentEventCount();
        double queuedTotal = queued.getCurrentTotalValue();

        _queue.addJob(new NamedJob(_ctx, "stat-ready-1"));
        _queue.addJob(new NamedJob(_ctx, "stat-ready-2"));
        _queue.addJob(new NamedJob(_ctx, "stat-ready-3"));

        assertEquals("one sample per add", 3, ready.getCurrentEventCount() - readyEvents);
        assertEquals("0 + 1 + 2 ready jobs as each add arrived", 3D,
                     ready.getCurrentTotalValue() - readyTotal, 0.001D);
        assertEquals("one sample per add", 3, queued.getCurrentEventCount() - queuedEvents);
        assertEquals("nothing was scheduled for the future", 0D,
                     queued.getCurrentTotalValue() - queuedTotal, 0.001D);
    }

    /**
     * Two future-scheduled adds feed {@code queuedJobs} the size of the scheduled
     * set after each one (1, then 2) while {@code readyJobs} stays at zero, which
     * is what tells the two stats apart.
     */
    @Test
    public void testScheduledAddsReportTheScheduledCount() {
        Rate ready = rate("jobQueue.readyJobs");
        Rate queued = rate("jobQueue.queuedJobs");
        long readyEvents = ready.getCurrentEventCount();
        double readyTotal = ready.getCurrentTotalValue();
        long queuedEvents = queued.getCurrentEventCount();
        double queuedTotal = queued.getCurrentTotalValue();

        _queue.addJob(scheduledJob("stat-timed-1"));
        _queue.addJob(scheduledJob("stat-timed-2"));

        assertEquals("one sample per add", 2, queued.getCurrentEventCount() - queuedEvents);
        assertEquals("1 + 2 jobs waiting for a future start", 3D,
                     queued.getCurrentTotalValue() - queuedTotal, 0.001D);
        assertEquals("one sample per add", 2, ready.getCurrentEventCount() - readyEvents);
        assertEquals("no job became ready", 0D,
                     ready.getCurrentTotalValue() - readyTotal, 0.001D);
    }

    /**
     * The handles the constructor resolved are the very stats {@link
     * net.i2p.stat.StatManager} serves by name, so caching them cannot fork the
     * data away from what the console reads.
     *
     * @throws Exception if the cached-handle fields are missing or unreadable
     */
    @Test
    public void testCachedHandlesAreTheRegisteredStats() throws Exception {
        assertSame("jobQueue.readyJobs handle is not the registered stat",
                   _ctx.statManager().getRate("jobQueue.readyJobs"), field("_readyJobsStat"));
        assertSame("jobQueue.queuedJobs handle is not the registered stat",
                   _ctx.statManager().getRate("jobQueue.queuedJobs"), field("_queuedJobsStat"));
    }

    /**
     * A null job never reaches the queues and never reports anything, exactly as
     * before: the stats must not gain a sample for an add that did not happen.
     */
    @Test
    public void testRejectedAddReportsNothing() {
        Rate ready = rate("jobQueue.readyJobs");
        Rate queued = rate("jobQueue.queuedJobs");
        long readyEvents = ready.getCurrentEventCount();
        long queuedEvents = queued.getCurrentEventCount();

        _queue.addJob(null);

        assertEquals(readyEvents, ready.getCurrentEventCount());
        assertEquals(queuedEvents, queued.getCurrentEventCount());
    }

    /**
     * A do-nothing job scheduled for a time far enough ahead that the pumper
     * leaves it in the future-scheduled set for the length of the test.
     *
     * @param name the job name
     * @return the job, not yet queued
     */
    private JobImpl scheduledJob(String name) {
        JobImpl job = new NamedJob(_ctx, name);
        job.getTiming().setStartAfter(_ctx.clock().now() + FUTURE_MS);
        return job;
    }

    /**
     * A job with a fixed name that does nothing when run.
     */
    private static class NamedJob extends JobImpl {
        private final String _name;

        /**
         * A job with a fixed name, due as soon as it is queued.
         *
         * @param ctx the router context
         * @param name the job name reported by getName()
         */
        NamedJob(RouterContext ctx, String name) {
            super(ctx);
            _name = name;
        }

        @Override
        public String getName() { return _name; }

        @Override
        public void runJob() { /* never run: no runners in this test */ }
    }
}
