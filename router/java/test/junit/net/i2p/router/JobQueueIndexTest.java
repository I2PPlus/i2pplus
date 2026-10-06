package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link JobQueue}'s ready-queue membership index stays in step with
 * the queues it mirrors.
 *
 * Duplicate detection in addJob() reads this index instead of scanning the
 * three {@code LinkedBlockingQueue}s (O(n) each, so ~640ns per addJob at a
 * 200-deep backlog, and worse precisely when the router is saturated). An
 * index that leaks entries would silently stop admitting legitimate requeues;
 * one that misses entries would let duplicate jobs run twice. Both failure
 * modes are invisible from the outside, so they are pinned here against the
 * queues themselves.
 *
 * @since 0.9.71+
 */
public class JobQueueIndexTest {

    private static final long FAR_FUTURE_MS = 10 * 60 * 1000L;

    private RouterContext _ctx;
    private JobQueue _queue;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        // Deliberately not calling allowParallelOperation(): with no runners the
        // queued jobs stay put, so the tests observe the queues rather than a
        // race against dispatch.
        _queue = _ctx.jobQueue();
    }

    @After
    public void tearDown() {
        if (_queue != null) {
            _queue.shutdown();
        }
    }

    /**
     * @param name the private field name on JobQueue
     * @return the field's value
     */
    private Object field(String name) throws Exception {
        Field f = JobQueue.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(_queue);
    }

    /**
     * @return the live membership index
     */
    @SuppressWarnings("unchecked")
    private Set<Job> index() throws Exception {
        return (Set<Job>) field("_readyIndex");
    }

    /**
     * @param name the private queue field name
     * @return that queue's contents
     */
    @SuppressWarnings("unchecked")
    private List<Job> queue(String name) throws Exception {
        return new ArrayList<>((BlockingQueue<Job>) field(name));
    }

    /**
     * Every job present in a ready queue must be in the index, and every index
     * member must be present in at least one ready queue. A duplicate copy of
     * the same job within a queue satisfies both directions - the index is a
     * set, so it answers membership, not multiplicity.
     */
    private void assertIndexConsistent() throws Exception {
        List<Job> ready = queue("_readyJobs");
        List<Job> high = queue("_highPriorityJobs");
        List<Job> timedReady = queue("_timedJobsReady");
        Set<Job> live = new HashSet<>();
        live.addAll(ready);
        live.addAll(high);
        live.addAll(timedReady);
        Set<Job> indexed = new HashSet<>(index());

        Set<Job> missingFromIndex = new HashSet<>(live);
        missingFromIndex.removeAll(indexed);
        assertTrue("queued jobs absent from the index: " + missingFromIndex,
                   missingFromIndex.isEmpty());

        Set<Job> staleInIndex = new HashSet<>(indexed);
        staleInIndex.removeAll(live);
        assertTrue("index entries with no queue to match: " + staleInIndex,
                   staleInIndex.isEmpty());
        assertEquals("index must have one entry per distinct queued job",
                     live.size(), index().size());
    }

    /**
     * @return how many copies of job the three ready queues hold
     */
    private int copies(Job job) throws Exception {
        int n = 0;
        for (String name : new String[]{"_readyJobs", "_highPriorityJobs", "_timedJobsReady"}) {
            for (Job j : queue(name)) {
                if (j == job) {n++;}
            }
        }
        return n;
    }

    /**
     * @param name a job class name fragment
     * @return a fresh job whose name contains name
     */
    private JobImpl job(String name) {
        return new NamedJob(_ctx, name);
    }

    /**
     * A re-adding the same instance repeatedly must leave exactly one copy
     * queued. This is the whole point of the index: a second copy means the
     * job body runs twice.
     */
    @Test
    public void testDuplicateAddAdmitsOneCopy() throws Exception {
        JobImpl job = job("dup");
        for (int i = 0; i < 5; i++) {
            _queue.addJob(job);
        }
        assertEquals(1, copies(job));
        assertEquals(1, _queue.getReadyCount());
        assertIndexConsistent();
    }

    /**
     * removeJob() must release the index entry, otherwise the job could never
     * be queued again and would silently stop running for the rest of the
     * router's life.
     */
    @Test
    public void testRemoveJobReleasesIndexEntry() throws Exception {
        JobImpl job = job("remove-release");
        _queue.addJob(job);
        assertEquals(1, index().size());
        _queue.removeJob(job);
        assertEquals(0, index().size());
        assertEquals(0, _queue.getReadyCount());
        assertIndexConsistent();

        _queue.addJob(job);
        assertEquals("job must be re-admissible after removal", 1, copies(job));
        assertIndexConsistent();
    }

    /**
     * addJobToTop() moves a queued job between queues. Both the dequeue and the
     * enqueue have to adjust the index, or the job ends up in the index and in
     * the high-priority queue while never having been released - which would
     * block every later add of that instance.
     */
    @Test
    public void testAddJobToTopKeepsIndexInStep() throws Exception {
        JobImpl job = job("promote");
        _queue.addJob(job);
        _queue.addJobToTop(job);
        assertEquals(1, copies(job));
        assertEquals(1, index().size());
        assertEquals(1, queue("_highPriorityJobs").size());
        assertEquals(0, queue("_readyJobs").size());
        assertIndexConsistent();
    }

    /**
     * addJobToTop() of an instance that is not queued must claim the index,
     * and must still do so when the instance is already sitting in the
     * future-scheduled skip list.
     */
    @Test
    public void testAddJobToTopOfScheduledJob() throws Exception {
        JobImpl job = job("promote-scheduled");
        job.getTiming().setStartAfter(_ctx.clock().now() + FAR_FUTURE_MS);
        _queue.addJob(job);
        assertEquals(0, index().size());

        _queue.addJobToTop(job);
        assertEquals(1, copies(job));
        assertEquals(1, index().size());
        assertEquals(1, queue("_highPriorityJobs").size());
        assertIndexConsistent();
    }

    /**
     * Dispatch releases the index entry, not job completion. JobImpl.requeue()
     * is called from runJob() - i.e. while the job is still running - and if
     * the entry survived until updateStats() the requeue would be swallowed and
     * the job would never run again.
     */
    @Test
    public void testDispatchReleasesIndexEntry() throws Exception {
        JobImpl job = job("dispatch");
        _queue.addJob(job);
        assertEquals(1, index().size());

        Job taken = _queue.getNext();
        assertSame(job, taken);
        assertEquals("dispatched job must leave the index", 0, index().size());
        assertEquals(0, _queue.getReadyCount());
        assertIndexConsistent();

        _queue.addJob(job);
        assertEquals("a self-requeue after dispatch must be admitted", 1, copies(job));
        assertIndexConsistent();
    }

    /**
     * A job still sitting in the future-scheduled skip list is deduplicated by
     * removing and re-adding it, not by the ready-queue index. The re-add must
     * not leave a stale entry behind for a job that is only scheduled.
     */
    @Test
    public void testRescheduledJobLeavesNoIndexEntry() throws Exception {
        JobImpl job = job("reschedule");
        job.getTiming().setStartAfter(_ctx.clock().now() + FAR_FUTURE_MS);
        _queue.addJob(job);
        assertEquals("a scheduled job is not a ready job", 0, index().size());

        _queue.addJob(job);
        assertEquals("rescheduling must not queue a second copy", 0, copies(job));
        assertEquals(0, index().size());
        assertEquals(0, _queue.getReadyCount());
        assertIndexConsistent();
    }

    /**
     * The index must survive a concurrent storm of adds and promotions without
     * drifting from the queues. Distinct instances per thread make this an
     * exact count check; duplicates admitted inside the check-then-add window
     * are tolerated by design, so only membership is asserted.
     */
    @Test
    public void testIndexConsistentUnderConcurrentAdds() throws Exception {
        int threads = 4;
        int perThread = 40;
        List<JobImpl> jobs = new ArrayList<>();
        for (int i = 0; i < threads * perThread; i++) {
            jobs.add(job("concurrent-" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        JobImpl j = jobs.get(base + i);
                        // Mix in a duplicate add and a promotion so the
                        // index has to survive all three enqueue paths.
                        _queue.addJob(j);
                        _queue.addJob(j);
                        if ((i & 3) == 0) {
                            _queue.addJobToTop(j);
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "idx-" + t);
            workers.add(th);
            th.start();
        }
        start.countDown();
        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS));
        for (Thread th : workers) {
            th.join(1000);
        }

        // Every distinct job must be represented exactly once as an index
        // member, and every index member must still be in a queue.
        assertEquals("one index entry per distinct job",
                     threads * perThread, index().size());
        assertEquals("each job is queued exactly once",
                     threads * perThread, _queue.getReadyCount());
        assertIndexConsistent();

        // Removing everything must empty the index too.
        for (JobImpl j : jobs) {
            _queue.removeJob(j);
        }
        assertEquals(0, index().size());
        assertEquals(0, _queue.getReadyCount());
        assertIndexConsistent();
    }

    /**
     * getTestJobCount() is now an O(1) counter maintained by the same helpers
     * as the index. No TestJob can be constructed in a unit test (it claims a
     * tunnel instance slot), so this pins the other half of the contract: the
     * counter must stay at zero for ordinary jobs across every add, promote,
     * dispatch and remove path, i.e. it must not be touched spuriously.
     */
    @Test
    public void testTestJobCounterStaysZeroForOrdinaryJobs() throws Exception {
        JobImpl job = job("not-a-test-job");
        assertEquals(0, _queue.getTestJobCount());
        _queue.addJob(job);
        assertEquals(0, _queue.getTestJobCount());
        _queue.addJobToTop(job);
        assertEquals(0, _queue.getTestJobCount());
        assertSame(job, _queue.getNext());
        assertEquals(0, _queue.getTestJobCount());
        _queue.addJob(job);
        _queue.removeJob(job);
        assertEquals(0, _queue.getTestJobCount());
    }

    /**
     * Shutdown must clear the index and the counter, otherwise a queue that is
     * restarted (or a router that restarts the queue) would reject every job.
     */
    @Test
    public void testShutdownClearsIndex() throws Exception {
        JobQueue queue = _queue;
        queue.addJob(job("shutdown"));
        assertEquals(1, index().size());
        queue.shutdown();
        assertTrue("index must be empty after shutdown", index().isEmpty());
        assertEquals(0, queue.getTestJobCount());
        _queue = null; // already shut down; skip the tearDown pass
    }

    /**
     * Minimal job whose only job is to have a distinguishable name.
     */
    private static class NamedJob extends JobImpl {
        private final String _name;

        /**
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
