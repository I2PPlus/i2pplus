package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link TunnelControllerGroup#createRunnerExecutor(int, AtomicLong)} overflow
 * semantics for per-tunnel runner pools.
 *
 * <p>Regression guard for cross-dest starvation: each tunnel must own a
 * private SynchronousQueue/AbortPolicy pool so a flood on one dest rejects
 * only that dest's excess connections as RejectedExecutionException, never
 * running them inline and never consuming another tunnel's workers.
 *
 * @since 0.9.71+
 */
public class RunnerExecutorOverflowTest {

    /** Overflow must reject with RejectedExecutionException, not run inline. */
    @Test
    public void testSaturatedPoolRejectsNotInlines() throws InterruptedException {
        int threads = 1;
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(threads, new AtomicLong());
        CountDownLatch release = new CountDownLatch(1);
        try {
            // SynchronousQueue: a second submission while the single worker is
            // busy must be rejected immediately (no queue).
            CountDownLatch entered = new CountDownLatch(1);
            exec.execute(() -> {
                entered.countDown();
                try {release.await();} catch (InterruptedException ie) {Thread.currentThread().interrupt();}
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicBoolean rejectedTaskRan = new AtomicBoolean(false);
            try {
                exec.execute(() -> rejectedTaskRan.set(true));
                fail("expected RejectedExecutionException from a saturated SynchronousQueue pool");
            } catch (RejectedExecutionException expected) {
                // correct
            }
            assertFalse("rejected task must NOT run inline", rejectedTaskRan.get());
        } finally {
            release.countDown();
            exec.shutdownNow();
        }
    }

    /** An under-loaded pool accepts and runs the task. */
    @Test
    public void testUnderLoadedPoolRunsTask() {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(4, new AtomicLong());
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            exec.execute(() -> ran.set(true));
            for (int i = 0; i < 1000 && !ran.get(); i++) {
                try {Thread.sleep(2);} catch (InterruptedException ie) {Thread.currentThread().interrupt(); break;}
            }
            assertTrue("task should run on a non-saturated pool", ran.get());
        } finally {
            exec.shutdownNow();
        }
    }

    /** Worker threads are daemon and named TunnelCln.*. */
    @Test
    public void testWorkerThreadsAreDaemonAndNamed() throws InterruptedException {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(1, new AtomicLong());
        try {
            Object[] holder = new Object[1];
            CountDownLatch done = new CountDownLatch(1);
            exec.execute(() -> {holder[0] = Thread.currentThread(); done.countDown();});
            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread t = (Thread) holder[0];
            assertTrue("runner threads must be daemon", t.isDaemon());
            assertTrue("runner thread should be named TunnelCln.*: " + t.getName(),
                       t.getName().startsWith("TunnelCln."));
        } finally {
            exec.shutdownNow();
        }
    }

    /** Idle threads are reclaimable (allowCoreThreadTimeOut with core 0). */
    @Test
    public void testIdleThreadsAreReclaimable() {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(8, new AtomicLong());
        try {
            assertTrue("idle runner threads must time out", exec.allowsCoreThreadTimeOut());
            assertEquals("core stays 0 for SynchronousQueue", 0, exec.getCorePoolSize());
            assertEquals(8, exec.getMaximumPoolSize());
        } finally {
            exec.shutdownNow();
        }
    }

    /** Floor: a zero/negative max is clamped to at least 1 so the pool can run. */
    @Test
    public void testMaxClampedToAtLeastOne() {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(0, new AtomicLong());
        try {
            assertTrue("max must be >= 1", exec.getMaximumPoolSize() >= 1);
        } finally {
            exec.shutdownNow();
        }
    }
}
