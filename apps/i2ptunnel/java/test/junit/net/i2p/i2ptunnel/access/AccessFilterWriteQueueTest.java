package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Pins the ceiling on the disk writer shared by every filtered tunnel.
 *
 * <p>Each filter submits one disk task every five seconds onto one writer thread, and
 * the queue under it was unbounded. A writer blocked on a bad disk - a hung mount, a
 * full filesystem, an fsync that does not return - therefore never drained it: the
 * backlog grew by one task per interval per filtered tunnel, each holding its filter
 * and its definition alive, and when the disk came back the writer replayed all of
 * them, recording and re-reading state that was hours stale by then. A dozen filtered
 * tunnels accumulate some 8,600 queued tasks a day.
 *
 * @since 0.9.72+
 */
public class AccessFilterWriteQueueTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public AccessFilterWriteQueueTest() {}

    /**
     * A tick is queued while the writer has room, and skipped once it does not -
     * the boundary is the ceiling itself, not a fraction of it.
     */
    @Test
    public void testTheDecisionStopsAtTheCeiling() {
        int limit = AccessFilter.MAX_PENDING_WRITES;
        assertTrue(AccessFilter.mayQueueDiskPass(0, limit));
        assertTrue(AccessFilter.mayQueueDiskPass(limit - 1, limit));
        assertFalse("a full queue still accepted work",
                    AccessFilter.mayQueueDiskPass(limit, limit));
        assertFalse(AccessFilter.mayQueueDiskPass(limit + 1, limit));
    }

    /**
     * The ceiling is real, not only a decision: with the single writer thread held
     * by a task that does not return, exactly MAX_PENDING_WRITES tasks are accepted
     * and the next one is refused rather than queued.
     *
     * @throws InterruptedException if the writer thread never starts the blocking task
     */
    @Test
    public void testTheWriterRefusesWorkOnceItIsStalled() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor writer = AccessFilter.diskWriter();
        writer.execute(() -> {
            blocked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                // the drain below is enough
            }
        });
        assertTrue("the writer never started the blocking task", blocked.await(10, TimeUnit.SECONDS));
        int accepted = 0;
        RejectedExecutionException rejected = null;
        for (int i = 0; i < AccessFilter.MAX_PENDING_WRITES + 8; i++) {
            try {
                writer.execute(() -> {});
                accepted++;
            } catch (RejectedExecutionException ree) {
                rejected = ree;
                break;
            }
        }
        assertEquals("the stalled writer accepted a different number of tasks",
                     AccessFilter.MAX_PENDING_WRITES, accepted);
        assertNotNull("the stalled writer queued past its ceiling", rejected);
        assertEquals(AccessFilter.MAX_PENDING_WRITES, AccessFilter.pendingWrites());
        release.countDown();
        drain(writer);
        assertEquals("the backlog outlived the stall", 0, AccessFilter.pendingWrites());
    }

    /**
     * Wait for the writer to work off what the case queued, so the shared thread is
     * left idle for the rest of the suite.
     *
     * @param writer the executor to wait on
     * @throws InterruptedException if the wait is cut short
     */
    private static void drain(ThreadPoolExecutor writer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (writer.getQueue().size() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
