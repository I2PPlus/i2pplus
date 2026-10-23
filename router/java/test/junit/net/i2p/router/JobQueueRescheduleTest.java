package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that re-adding a job the queue already holds in its future-scheduled
 * set leaves that job scheduled.
 *
 * addJob() treats a job already in {@code _timedJobs} as a duplicate and takes
 * it out of the set before deciding what to do with it. If the re-add then stops
 * there, the job is in no queue at all: it never runs again, and nothing about
 * the router's behaviour reveals it - the periodic job has simply stopped. The
 * warning addJob() logs for this case promises "rescheduled", so the re-add is
 * the contract, and it is pinned here against the set itself.
 * <p>
 * Every case re-adds without moving the start time. A re-add that also calls
 * {@link JobTiming#setStartAfter} first is a separate, unfixed hazard: the set
 * is ordered on that field, so the job's node is no longer reachable and the
 * removal that precedes the re-add cannot find it.
 *
 * @since 0.9.72+
 */
public class JobQueueRescheduleTest {

    private static final long FUTURE_MS = 10 * 60 * 1000L;

    private RouterContext _ctx;
    private JobQueue _queue;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        // No runners: nothing dispatches, so a job that becomes ready stays
        // observable in the ready queue instead of being consumed.
        _queue = _ctx.jobQueue();
    }

    @After
    public void tearDown() {
        if (_queue != null) {
            _queue.shutdown();
        }
    }

    /**
     * Read a private field of the job queue.
     *
     * @param name the private field name on JobQueue
     * @return the field's value
     * @throws Exception if the field is not declared by JobQueue or is not
     *                   accessible
     */
    private Object field(String name) throws Exception {
        Field f = JobQueue.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(_queue);
    }

    /**
     * The future-scheduled skip list.
     *
     * @return the live set of not-yet-ready jobs
     * @throws Exception if the {@code _timedJobs} field is missing or
     *                   inaccessible
     */
    @SuppressWarnings("unchecked")
    private ConcurrentSkipListSet<Job> timed() throws Exception {
        return (ConcurrentSkipListSet<Job>) field("_timedJobs");
    }

    /**
     * The named ready queue.
     *
     * @param name the private queue field name
     * @return that queue's contents
     * @throws Exception if the named queue field is missing or inaccessible
     */
    @SuppressWarnings("unchecked")
    private List<Job> queue(String name) throws Exception {
        return new ArrayList<>((BlockingQueue<Job>) field(name));
    }

    /**
     * How many copies of this exact instance the future-scheduled set holds.
     * Counted by identity, and scoped to the instance under test, because the
     * test context schedules its own jobs on the same queue.
     *
     * @param job the instance to count
     * @return copies in {@code _timedJobs}, so 0 means the job was dropped
     * @throws Exception if reading {@code _timedJobs} reflectively fails
     */
    private int timedCopies(Job job) throws Exception {
        int n = 0;
        for (Job j : timed()) {
            if (j == job) {n++;}
        }
        return n;
    }

    /**
     * How many copies of this exact instance the ready queues hold.
     *
     * @param job the instance to count
     * @return copies in the three ready queues
     * @throws Exception if a ready-queue field is missing or inaccessible
     */
    private int readyCopies(Job job) throws Exception {
        int n = 0;
        for (String name : new String[]{"_readyJobs", "_highPriorityJobs", "_timedJobsReady"}) {
            for (Job j : queue(name)) {
                if (j == job) {n++;}
            }
        }
        return n;
    }

    /**
     * How many copies of the job sit in any queue, ready or scheduled.
     *
     * @param job the instance to count
     * @return copies held by the queue, so 0 means the job was dropped
     * @throws Exception if reading {@code _timedJobs} or a ready queue reflectively fails
     */
    private int copies(Job job) throws Exception {
        return readyCopies(job) + timedCopies(job);
    }

    /**
     * Schedule a job well in the future so the pumper leaves it alone.
     *
     * @param name a job class name fragment
     * @return a job already queued in {@code _timedJobs}
     */
    private JobImpl scheduled(String name) {
        JobImpl job = new NamedJob(_ctx, name);
        job.getTiming().setStartAfter(_ctx.clock().now() + FUTURE_MS);
        _queue.addJob(job);
        try {
            assertEquals("setup: job must start out scheduled", 1, timedCopies(job));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return job;
    }

    /**
     * The core regression: adding a job that is already scheduled must leave it
     * scheduled. Removing it from {@code _timedJobs} without putting it back
     * silently cancels the job for the rest of the router's life.
     *
     * @throws Exception if reading {@code _timedJobs} or a ready queue
     *                   reflectively fails
     */
    @Test
    public void testReAddOfScheduledJobStaysScheduled() throws Exception {
        JobImpl job = scheduled("reschedule-keep");
        assertEquals(1, timedCopies(job));
        assertTrue(timed().contains(job));

        _queue.addJob(job);

        assertTrue("re-adding a scheduled job cancelled it", timed().contains(job));
        assertEquals("re-adding must not schedule a second copy", 1, timedCopies(job));
        assertEquals(0, readyCopies(job));
    }

    /**
     * Re-adding repeatedly must be idempotent, not additive: a periodic job
     * re-added on every pass would otherwise grow {@code _timedJobs} without
     * bound and run its body once per copy.
     *
     * @throws Exception if reading {@code _timedJobs} or a ready queue reflectively fails
     */
    @Test
    public void testRepeatedReAddsStayOneCopy() throws Exception {
        JobImpl job = scheduled("reschedule-idempotent");
        for (int i = 0; i < 8; i++) {
            _queue.addJob(job);
        }
        assertEquals(1, copies(job));
        assertEquals(1, timedCopies(job));
        assertEquals(0, readyCopies(job));
    }

    /**
     * The fix must not make removeJob() unable to cancel a scheduled job -
     * otherwise a job removed by a shutdown path would be resurrected by the
     * next re-add and run against a context that has moved on.
     *
     * @throws Exception if reading {@code _timedJobs} or a ready queue reflectively fails
     */
    @Test
    public void testRemoveJobStillCancelsScheduledJob() throws Exception {
        JobImpl job = scheduled("reschedule-remove");
        _queue.removeJob(job);
        assertEquals(0, timedCopies(job));
        assertEquals(0, copies(job));

        // And it is admissible again afterwards, so the removal released it
        // rather than leaving a claim behind.
        _queue.addJob(job);
        assertEquals(1, copies(job));
    }

    /**
     * A job that was never scheduled takes the normal path unchanged, so the
     * reschedule branch did not become the only working one.
     *
     * @throws Exception if reading {@code _timedJobs} or a ready queue reflectively fails
     */
    @Test
    public void testFirstAddOfFutureJobIsScheduledOnce() throws Exception {
        JobImpl job = new NamedJob(_ctx, "first-add");
        job.getTiming().setStartAfter(_ctx.clock().now() + FUTURE_MS);
        _queue.addJob(job);
        assertEquals(1, timedCopies(job));
        assertTrue(timed().contains(job));
        assertEquals(0, readyCopies(job));
    }

    /**
     * Minimal job whose only job is to have a distinguishable name.
     */
    private static class NamedJob extends JobImpl {
        private final String _name;

        /**
         * A job with a fixed name.
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
