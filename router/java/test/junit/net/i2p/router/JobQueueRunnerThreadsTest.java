package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The runner-thread gauge: how many runner threads are actually running,
 * against how many slots the queue's registry holds.
 *
 * <p>The registry's size is what the scaler sizes the pool from, and it said
 * nothing about the threads behind it. A runner that lost its registry slot
 * keeps taking jobs, is never retired again because it never goes idle from the
 * registry's point of view, and sits outside every limit the scaler enforces -
 * invisible except in the thread dump. The count is taken inside the runner's
 * own loop and released in a finally covering the whole method, so no exit path
 * - idle retirement, the queue closing, or an Error escaping a job - can leave
 * it high and report an orphan that does not exist.
 *
 * @since 0.9.72+
 */
public class JobQueueRunnerThreadsTest {

    /** A throwaway router context, or null when one cannot be built. */
    private RouterContext _ctx;

    /** The queue whose registry and thread count the cases read. */
    private JobQueue _queue;

    /** How many runners the queue started, so the cases count relative to it. */
    private int _started;

    /**
     * The gauge's cases. Each drives a real job queue, because what is pinned
     * is the count a set of started and retired runner threads leaves behind.
     */
    public JobQueueRunnerThreadsTest() {
    }

    /**
     * A context with its runners started, and enough of them that retiring a
     * few leaves runners standing.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _queue = _ctx.jobQueue();
        _queue.allowParallelOperation();
        _started = _queue.getActiveRunnerCount();
        assertTrue("runners did not start", _started >= 6);
    }

    /**
     * Stops the queue and its runners, so a case that retired a runner does not
     * leave its thread running for the next one.
     */
    @After
    public void tearDown() {
        if (_queue != null) {_queue.shutdown();}
    }

    /**
     * Started, looping runners are counted, and never more than the runners
     * that exist. The one-way test is deliberate: a runner is registered before
     * its thread starts, so the registry can legitimately be one ahead of the
     * gauge, while a gauge ahead of the registry is a thread nothing owns.
     */
    @Test
    public void startedRunnersAreCounted() {
        int live = _queue.getLiveRunnerThreadCount();
        assertTrue("live threads " + live + " above registered " + _started, live <= _started);
        assertTrue("live threads " + live + " far below registered " + _started,
                   live >= _started - 1);
    }

    /**
     * Retiring idle runners and then closing the queue releases every counted
     * thread. Retirement - the loop condition failing, then the runner dropping
     * its own slot - is the path a scale-down takes, and it leaves the runner
     * blocked on its next job until the queue closes; a count left behind on
     * that path would report an orphan for the rest of the run.
     *
     * @throws InterruptedException from the wait for the retired threads to end
     */
    @Test
    public void retiringRunnersReleasesTheirCount() throws InterruptedException {
        int retired = _queue.removeIdleRunners(3);
        assertTrue("no runners retired", retired >= 1);

        _queue.shutdown();

        assertEquals(0, _queue.getActiveRunnerCount());
        assertEquals(0, awaitLiveCount(0));
    }

    /**
     * Closing the queue releases every counted thread: the queue going away, an
     * idle exit and a poison job all leave through the same finally, so the
     * gauge is back at zero rather than carrying a stale count forward.
     *
     * @throws InterruptedException from the wait for the threads to end
     */
    @Test
    public void closingTheQueueLeaksNoCountedThreads() throws InterruptedException {
        _queue.shutdown();
        assertEquals(0, awaitLiveCount(0));
    }

    /**
     * Waits for the live runner-thread count to fall to a value, and returns
     * what it reached. A retired runner ends on its own schedule - it leaves the
     * job it was blocked on - so the count is not readable the instant the
     * retirement returns.
     *
     * @param want the count to wait for
     * @return the live count when it reached want, or what it settled at after
     *         five seconds
     * @throws InterruptedException from the wait between readings
     */
    private int awaitLiveCount(int want) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        int live = _queue.getLiveRunnerThreadCount();
        while (live > want && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            live = _queue.getLiveRunnerThreadCount();
        }
        return live;
    }
}
