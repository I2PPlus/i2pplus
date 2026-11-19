package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * One clock change shifts a job's timing once, wherever that job is parked.
 *
 * <p>A recurring job that requeues itself inside runJob() is in the future-scheduled
 * set and is its runner's current job at the same moment, and the pumper may have
 * promoted it to the ready queue as well. The shift walked every queue and every
 * runner without remembering what it had already shifted, so such a job moved by
 * twice the clock change - its next start landed there instead, and the next
 * requeue carried the error forward. The ready queue was not walked at all, so a
 * job parked there kept an unshifted start time, and the lag the tunnel pools act
 * on reported the clock change as a queue that had fallen behind.
 *
 * @since 0.9.72+
 */
public class JobQueueClockShiftTest {

    /** The clock change to apply: a minute, well past the lag threshold. */
    private static final long DELTA = 60 * 1000L;

    /** How far in the future a scheduled job starts out. */
    private static final long FUTURE_MS = 10 * 1000L;

    /** The lag the tunnel pools treat as an overloaded queue. */
    private static final long LAG_THRESHOLD = 500;

    private RouterContext _ctx;
    private JobQueue _queue;

    /**
     * A fixture group for the job queue's clock handling; it holds no state.
     *
     * @since 0.9.72+
     */
    public JobQueueClockShiftTest() {}

    /**
     * A queue with no runners, so nothing dispatches and every job stays where it
     * was put.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _queue = _ctx.jobQueue();
    }

    /**
     * Stops the queue and the runner each test installs.
     */
    @After
    public void tearDown() {
        if (_queue != null)
            _queue.shutdown();
    }

    /**
     * The defect: a job that requeued itself while running is in the scheduled set
     * and in its runner, and both walks shifted it, so its next start moved by
     * twice the clock change.
     *
     * @throws Exception if the queue's private fields cannot be reached
     */
    @Test
    public void testARequeuedJobIsShiftedOnce() throws Exception {
        NamedJob job = new NamedJob(_ctx, "requeued-while-running");
        long now = _ctx.clock().now();
        job.getTiming().setStartAfter(now + FUTURE_MS);
        job.getTiming().start(now);
        job.getTiming().end(now + 5);
        _queue.addJob(job);
        assertTrue(timed().contains(job));
        setRunningJob(runner(), job, now);

        _queue.updateJobTimings(DELTA);

        JobTiming timing = job.getTiming();
        assertEquals("the next start moved by more than the clock change",
                     now + FUTURE_MS + DELTA, timing.getStartAfter());
        assertEquals("the recorded start moved by more than the clock change",
                     now + DELTA, timing.getActualStart());
        assertEquals("the recorded end moved by more than the clock change",
                     now + 5 + DELTA, timing.getActualEnd());
    }

    /**
     * The fork's ready queue, which the pumper promotes due jobs into, was not
     * walked at all, so a job sitting there kept the start time the pumper gave it
     * before the clock moved.
     *
     * @throws Exception if the queue's private fields cannot be reached
     */
    @Test
    public void testAReadyJobIsShiftedOnce() throws Exception {
        NamedJob job = new NamedJob(_ctx, "promoted-and-waiting");
        long now = _ctx.clock().now();
        job.getTiming().setStartAfter(now);
        ready().add(job);

        _queue.updateJobTimings(DELTA);

        assertEquals("a job in the ready queue was not shifted at all",
                     now + DELTA, job.getTiming().getStartAfter());
    }

    /**
     * What the missed queue costs. The lag the tunnel pools act on is the current
     * clock against the job's start time, so once the clock has been corrected by
     * DELTA a job whose start was never corrected reads as DELTA of queue lag -
     * past the 500 ms at which the pools double the tunnel-removal interval and log
     * an overloaded queue.
     *
     * @throws Exception if the queue's private fields cannot be reached
     */
    @Test
    public void testTheLagMetricDoesNotReportTheClock() throws Exception {
        NamedJob job = new NamedJob(_ctx, "waiting-for-a-runner");
        long now = _ctx.clock().now();
        job.getTiming().setStartAfter(now);
        ready().add(job);

        _queue.updateJobTimings(DELTA);

        long lagAfterTheClockMoves = now + DELTA - job.getTiming().getStartAfter();
        assertTrue("the clock change read as queue lag: " + lagAfterTheClockMoves + "ms",
                   lagAfterTheClockMoves < LAG_THRESHOLD);
    }

    /**
     * A job that has never run keeps its zero sentinels: a shift must not turn
     * "never started" into a timestamp.
     */
    @Test
    public void testUnsetTimingsStayUnset() {
        NamedJob job = new NamedJob(_ctx, "never-run");
        job.getTiming().setStartAfter(_ctx.clock().now() + FUTURE_MS);
        _queue.addJob(job);

        _queue.updateJobTimings(DELTA);

        assertEquals(0, job.getTiming().getActualStart());
        assertEquals(0, job.getTiming().getActualEnd());
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
     * The future-scheduled set, where a job with a start time in the future waits.
     *
     * @return the live set, mutable
     * @throws Exception if the {@code _timedJobs} field is missing or inaccessible
     */
    @SuppressWarnings("unchecked")
    private Set<Job> timed() throws Exception {
        return (Set<Job>) field("_timedJobs");
    }

    /**
     * The queue the pumper promotes due jobs into, ahead of the runners taking them.
     *
     * @return the live queue, mutable
     * @throws Exception if the {@code _timedJobsReady} field is missing or
     *                   inaccessible
     */
    @SuppressWarnings("unchecked")
    private BlockingQueue<Job> ready() throws Exception {
        return (BlockingQueue<Job>) field("_timedJobsReady");
    }

    /**
     * A runner registered with the queue but never started, standing in for the one
     * that is executing the job which requeued itself.
     *
     * @return the runner, already in the queue's runner map
     * @throws Exception if the {@code _queueRunners} field is missing or
     *                   inaccessible
     */
    @SuppressWarnings("unchecked")
    private JobQueueRunner runner() throws Exception {
        Map<Integer, JobQueueRunner> runners =
            (Map<Integer, JobQueueRunner>) field("_queueRunners");
        JobQueueRunner runner = new JobQueueRunner(_ctx, 999);
        runners.put(Integer.valueOf(999), runner);
        return runner;
    }

    /**
     * Marks the runner as executing that job, the way the runner itself does when
     * it takes one off the queue.
     *
     * @param runner the runner to mark busy
     * @param job the job it is running
     * @param beganOn the instant the run began
     * @throws Exception if the {@code _inFlight} field cannot be written
     */
    private static void setRunningJob(JobQueueRunner runner, Job job,
                                      long beganOn) throws Exception {
        Field f = JobQueueRunner.class.getDeclaredField("_inFlight");
        f.setAccessible(true);
        f.set(runner, new JobQueue.InFlight(job, beganOn));
    }

    /**
     * Minimal job with a distinguishable name and no work to do.
     */
    private static class NamedJob extends JobImpl {

        /** The name this job reports. */
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
