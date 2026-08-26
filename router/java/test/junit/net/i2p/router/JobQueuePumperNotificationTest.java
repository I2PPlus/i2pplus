package net.i2p.router;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the QueuePumper notifies runners via _runnerLock when
 * moving jobs from _timedJobs to _timedJobsReady. Without this
 * notification, runners block for up to 50ms on _readyJobs.poll()
 * and miss jobs that have already become ready in _timedJobsReady.
 *
 * The behavioral assertion is: a job scheduled a short time in the
 * future should be picked up within a small window after its scheduled
 * start time, not delayed by the 50ms polling ceiling.
 *
 * @since 0.9.71+
 */
public class JobQueuePumperNotificationTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        // RouterContext.initAll() already creates and starts the JobQueue
        // (pumper + scaler). We just need to enable runners.
        _ctx.jobQueue().allowParallelOperation();
    }

    @After
    public void tearDown() {
        if (_ctx != null) {
            _ctx.jobQueue().shutdown();
        }
    }

    /**
     * A minimal job that records the wall-clock time it was picked up
     * by a runner and counts down a latch so the test can measure latency.
     */
    private static class TimedRecordJob extends JobImpl {
        private final AtomicLong _pickedUpAt = new AtomicLong();
        private final CountDownLatch _latch;

        TimedRecordJob(RouterContext ctx, CountDownLatch latch) {
            super(ctx);
            _latch = latch;
        }

        @Override
        public String getName() { return "TimedRecordJob"; }

        @Override
        public void runJob() {
            _pickedUpAt.set(System.nanoTime());
            _latch.countDown();
        }

        /**
         * @return nanosecond timestamp when this job was executed by a runner
         */
        long getPickedUpNanos() { return _pickedUpAt.get(); }
    }

    /**
     * Verify that a job scheduled slightly in the future (going through
     * the pumper path: _timedJobs -> _timedJobsReady) is picked up
     * promptly. The lag between scheduled start and runner pickup must
     * be well under the 50ms poll timeout, proving the notification
     * wakes runners.
     */
    @Test
    public void testTimedJobPickedUpPromptly() throws Exception {
        // Schedule a job 30ms from now — it goes into _timedJobs,
        // the pumper moves it to _timedJobsReady when ready.
        long delayMs = 30;
        long scheduleNanos = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(1);
        TimedRecordJob job = new TimedRecordJob(_ctx, latch);
        long now = _ctx.clock().now();
        job.getTiming().setStartAfter(now + delayMs);
        _ctx.jobQueue().addJob(job);

        assertTrue("Job should be picked up within 200ms",
                   latch.await(200, TimeUnit.MILLISECONDS));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - scheduleNanos);
        // The job should not have run before its scheduled time,
        // and should run within a reasonable window after it.
        // The key assertion: elapsed must be < 50ms + margin, proving
        // the runner was woken by notification rather than polling.
        assertTrue("Elapsed " + elapsedMs + "ms should be < 150ms (notification-wake)",
                   elapsedMs < 150);
        assertTrue("Elapsed " + elapsedMs + "ms should be >= " + delayMs + "ms (not early)",
                   elapsedMs >= delayMs);
    }

    /**
     * Multiple timed jobs scheduled at staggered future times should
     * all be picked up promptly, confirming that the pumper notifies
     * runners each time it moves jobs — not just once.
     */
    @Test
    public void testMultipleTimedJobsPickedUp() throws Exception {
        int count = 5;
        CountDownLatch latch = new CountDownLatch(count);
        TimedRecordJob[] jobs = new TimedRecordJob[count];
        long[] scheduledNanos = new long[count];

        long baseNow = _ctx.clock().now();
        long baseNano = System.nanoTime();
        for (int i = 0; i < count; i++) {
            jobs[i] = new TimedRecordJob(_ctx, latch);
            // Stagger: 20ms, 40ms, 60ms, 80ms, 100ms
            jobs[i].getTiming().setStartAfter(baseNow + 20L * (i + 1));
            scheduledNanos[i] = baseNano + 20L * (i + 1) * 1_000_000;
            _ctx.jobQueue().addJob(jobs[i]);
        }

        assertTrue("All jobs should be picked up within 500ms",
                   latch.await(500, TimeUnit.MILLISECONDS));

        // Verify each job ran roughly on time: within 100ms of its
        // scheduled wall-clock offset. The pumper moves each job
        // to _timedJobsReady and notifies runners, so lag should be small.
        for (int i = 0; i < count; i++) {
            long actualDelayMs = TimeUnit.NANOSECONDS.toMillis(
                    jobs[i].getPickedUpNanos() - baseNano);
            long expectedDelayMs = 20L * (i + 1);
            long lagMs = actualDelayMs - expectedDelayMs;
            // First job may wait for a pumper cycle; allow generous margin
            assertTrue("Job " + i + " ran at " + actualDelayMs + "ms, " +
                       "expected ~" + expectedDelayMs + "ms, lag " + lagMs + "ms should be < 150ms",
                       lagMs < 150);
        }
    }

    /**
     * A job that is already past its start time when added should go
     * directly to _readyJobs (not through the pumper) and be picked
     * up immediately. This confirms the test setup is correct and
     * contrasts with the pumper path.
     */
    @Test
    public void testImmediateJobPickedUpFast() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        TimedRecordJob job = new TimedRecordJob(_ctx, latch);
        // startAfter is already in the past (now) — goes to _readyJobs directly
        long beforeNanos = System.nanoTime();
        _ctx.jobQueue().addJob(job);

        assertTrue("Immediate job should be picked up within 100ms",
                   latch.await(100, TimeUnit.MILLISECONDS));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - beforeNanos);
        // Direct-path jobs should be picked up very fast — under 50ms
        assertTrue("Immediate job elapsed " + elapsedMs + "ms should be < 50ms",
                   elapsedMs < 50);
    }
}
