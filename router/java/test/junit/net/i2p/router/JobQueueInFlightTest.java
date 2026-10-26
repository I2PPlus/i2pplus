package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The in-flight record a runner publishes: the job and the instant its run
 * began, as one value.
 *
 * <p>The runner used to publish the job and the start time in two separate
 * writes, and to leave the start time set after the job finished. The watchdog
 * read them at two instants, so it could name a job that had just begun
 * alongside the timestamp of the runner's previous run, and report a run as
 * long as the idle gap that preceded it. Publishing one immutable value removes
 * both readings, and these pin them.
 *
 * @since 0.9.72+
 */
public class JobQueueInFlightTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _ctx.jobQueue().allowParallelOperation();
    }

    @After
    public void tearDown() {
        if (_ctx != null) {
            _ctx.jobQueue().shutdown();
        }
    }

    /**
     * A job that parks inside runJob() until released, so it can be observed
     * while it is genuinely in flight.
     */
    private static class ParkedJob extends JobImpl {
        private final CountDownLatch _release;

        ParkedJob(RouterContext ctx, CountDownLatch release) {
            super(ctx);
            _release = release;
        }

        @Override
        public String getName() {return "ParkedJob";}

        @Override
        public void runJob() {
            try {
                _release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A job that stays in runJob() long enough to be sampled while it is in
     * flight, and returns on its own.
     */
    private static class BusyJob extends JobImpl {
        private final CountDownLatch _done;

        BusyJob(RouterContext ctx, CountDownLatch done) {
            super(ctx);
            _done = done;
        }

        @Override
        public String getName() {return "BusyJob";}

        @Override
        public void runJob() {
            try {
                Thread.sleep(15);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (_done != null) {_done.countDown();}
        }
    }

    /** A job that returns at once, to be picked up repeatedly. */
    private static class ShortJob extends JobImpl {
        private final CountDownLatch _done;

        ShortJob(RouterContext ctx, CountDownLatch done) {
            super(ctx);
            _done = done;
        }

        @Override
        public String getName() {return "ShortJob";}

        @Override
        public void runJob() {
            if (_done != null) {_done.countDown();}
        }
    }

    /** An idle queue reports nothing in flight. */
    @Test
    public void idleQueueReportsNothingInFlight() {
        assertNull(_ctx.jobQueue().getLongestRunningJob());
        assertEquals(-1, _ctx.jobQueue().getLastJobBegin());
        assertNull(_ctx.jobQueue().getLastJob());
    }

    /**
     * A job parked in runJob() is in flight: it is named, and timed from the
     * instant its own run began.
     */
    @Test
    public void parkedJobIsInFlight() throws Exception {
        _ctx.jobQueue().runQueue(2);
        CountDownLatch release = new CountDownLatch(1);
        ParkedJob job = new ParkedJob(_ctx, release);
        long before = _ctx.clock().now();
        _ctx.jobQueue().addJob(job);
        JobQueue.InFlight f = null;
        for (int i = 0; i < 400 && f == null; i++) {
            f = _ctx.jobQueue().getLongestRunningJob();
            if (f == null) {Thread.sleep(25);}
        }
        assertNotNull("job never showed up as in flight", f);
        assertSame(job, f._job);
        assertTrue("began on " + f._beganOn + " * added at " + before, f._beganOn >= before);
        assertEquals(f._beganOn, _ctx.jobQueue().getLastJobBegin());
        assertSame(job, _ctx.jobQueue().getLastJob());
        release.countDown();
    }

    /**
     * Once a job is done its record is retired. Left in place it read as a job
     * that had been running ever since it started, which is the false positive
     * that made the watchdog bark at an idle router.
     */
    @Test
    public void finishedJobLeavesNothingInFlight() throws Exception {
        _ctx.jobQueue().runQueue(2);
        CountDownLatch done = new CountDownLatch(1);
        _ctx.jobQueue().addJob(new ShortJob(_ctx, done));
        assertTrue("job did not run", done.await(15, TimeUnit.SECONDS));
        for (int i = 0; i < 400; i++) {
            if (_ctx.jobQueue().getLongestRunningJob() == null) {return;}
            Thread.sleep(25);
        }
        assertNull("in-flight record outlived the job", _ctx.jobQueue().getLongestRunningJob());
    }

    /**
     * A reader must never see a job paired with the start time of a different
     * run. Each job records its own start when the runner takes it, so every
     * snapshot taken while it runs has to agree with that record.
     */
    @Test
    public void snapshotNeverPairsAJobWithAnotherRunsStart() throws Exception {
        // one runner, so it goes idle between jobs: a start time left over from
        // the previous run is exactly what a torn read shows up as
        _ctx.jobQueue().runQueue(1);
        int seen = 0;
        for (int i = 0; i < 60; i++) {
            CountDownLatch done = new CountDownLatch(1);
            _ctx.jobQueue().addJob(new BusyJob(_ctx, done));
            for (int s = 0; s < 40 && done.getCount() > 0; s++) {
                JobQueue.InFlight f = _ctx.jobQueue().getLongestRunningJob();
                if (f != null) {
                    seen++;
                    long began = f._job.getTiming().getActualStart();
                    assertEquals("torn in-flight read: the job began at " + began
                                 + " * the snapshot says " + f._beganOn, began, f._beganOn);
                }
                Thread.sleep(1);
            }
            assertTrue("job " + i + " did not run", done.await(10, TimeUnit.SECONDS));
        }
        assertTrue("no in-flight snapshot was ever observed", seen > 0);
    }
}
