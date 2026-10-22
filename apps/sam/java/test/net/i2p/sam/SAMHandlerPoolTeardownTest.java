package net.i2p.sam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * The hand-off that keeps a SAM handler teardown off the pool's selector thread.
 * stopHandling() runs SAMv3Handler.die(), which waits up to 10 seconds for a
 * session's threads to exit, and one selector serves every pooled connection -
 * so a teardown run inline behind a slow client stalls all the others.
 *
 * @since 0.9.72+
 */
public class SAMHandlerPoolTeardownTest {

    /** A live pool accepts the teardown and runs it elsewhere. */
    @Test
    public void testAcceptedAndRunOffCallerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicInteger ran = new AtomicInteger();
            final Thread caller = Thread.currentThread();
            final Thread[] where = new Thread[1];
            boolean accepted = SAMHandlerPool.queueTeardown(pool, () -> {
                where[0] = Thread.currentThread();
                ran.incrementAndGet();
                done.countDown();
            });
            assertTrue("a live pool must accept the teardown", accepted);
            assertTrue("teardown must actually run", done.await(5, TimeUnit.SECONDS));
            assertEquals(1, ran.get());
            assertNotEquals("teardown must not run on the caller's thread",
                            caller, where[0]);
        } finally {
            pool.shutdown();
        }
    }

    /** A shut-down pool rejects, and the caller is told so it can cope. */
    @Test
    public void testRejectedAfterShutdown() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        AtomicInteger ran = new AtomicInteger();
        assertFalse("a shut-down pool must report rejection",
                    SAMHandlerPool.queueTeardown(pool, ran::incrementAndGet));
        assertEquals("a rejected teardown must not have run", 0, ran.get());
    }

    /** A pool that throws on execute is reported as a rejection, not propagated. */
    @Test
    public void testRejectingExecutorDoesNotThrow() {
        Executor alwaysRejects = r -> {
            throw new RejectedExecutionException("full");
        };
        assertFalse(SAMHandlerPool.queueTeardown(alwaysRejects, () -> {
            throw new AssertionError("must not run");
        }));
    }

    /** The teardown body runs exactly once - no double close. */
    @Test
    public void testRunsExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AtomicInteger ran = new AtomicInteger();
            assertTrue(SAMHandlerPool.queueTeardown(pool, ran::incrementAndGet));
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, ran.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A live pool runs its teardowns on the pool built for that work - not on
     * the caller, and not on the connector pool, whose four threads are what a
     * user-facing STREAM CONNECT or FORWARD waits on.
     */
    @Test
    public void testTeardownRunsOnTheTeardownPool() throws Exception {
        SAMHandlerPool pool = new SAMHandlerPool();
        try {
            CountDownLatch done = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            Thread[] where = new Thread[1];
            pool.runTeardown(() -> {
                where[0] = Thread.currentThread();
                done.countDown();
            });
            assertTrue("the teardown must complete", done.await(5, TimeUnit.SECONDS));
            assertNotEquals("the thread that noticed the disconnect must not run it",
                            caller, where[0]);
            assertTrue("teardown ran on the wrong pool: " + where[0].getName(),
                       where[0].getName().startsWith("SAM-Teardown"));
        } finally {
            pool.stop();
        }
    }

    /**
     * Once stop() has taken the teardown pool down, the caller still runs the
     * teardown rather than dropping it - a handler left open keeps its session's
     * threads and its socket.
     */
    @Test
    public void testTeardownRunsInlineAfterStop() {
        SAMHandlerPool pool = new SAMHandlerPool();
        pool.stop();
        AtomicInteger ran = new AtomicInteger();
        pool.runTeardown(ran::incrementAndGet);
        assertEquals("a refused teardown must still run on the caller", 1, ran.get());
    }

    /**
     * stop() takes the teardown pool down with the rest, which is what puts the
     * inline fallback above on its reachable path.
     */
    @Test
    public void testStopShutsDownTheTeardownPool() {
        SAMHandlerPool pool = new SAMHandlerPool();
        pool.stop();
        assertTrue("stop() left the teardown pool accepting work",
                   pool.getTeardowns().isShutdown());
    }
}
