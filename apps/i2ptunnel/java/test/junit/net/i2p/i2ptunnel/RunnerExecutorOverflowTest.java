package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link TunnelControllerGroup#createRunnerExecutor(int, AtomicLong)} overflow
 * semantics for per-tunnel runner pools.
 *
 * <p>Regression guard for cross-dest starvation: each tunnel must own a
 * private burst-queue/AbortPolicy pool so a flood on one dest rejects
 * only that dest's excess connections as RejectedExecutionException, never
 * running them inline and never consuming another tunnel's workers.
 *
 * @since 0.9.71+
 */
public class RunnerExecutorOverflowTest {

    /** After workers and the burst queue are full, reject — not run inline. */
    @Test
    public void testSaturatedPoolRejectsNotInlines() throws InterruptedException {
        int threads = 1;
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(threads, new AtomicLong());
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            exec.execute(() -> {
                entered.countDown();
                try {release.await();} catch (InterruptedException ie) {Thread.currentThread().interrupt();}
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Fill the burst queue while the single worker is blocked.
            for (int i = 0; i < TunnelControllerGroup.RUNNER_BURST_QUEUE; i++) {
                exec.execute(() -> {});
            }
            AtomicBoolean rejectedTaskRan = new AtomicBoolean(false);
            try {
                exec.execute(() -> rejectedTaskRan.set(true));
                fail("expected RejectedExecutionException once workers + burst queue are full");
            } catch (RejectedExecutionException expected) {
                // correct
            }
            assertFalse("rejected task must NOT run inline", rejectedTaskRan.get());
        } finally {
            release.countDown();
            exec.shutdownNow();
        }
    }

    /**
     * A micro-burst larger than the worker count but within the burst queue
     * must be accepted (no RejectedExecutionException) and later run — this is
     * the browser parallel-open case that previously shed as "server is busy".
     */
    @Test
    public void testBurstQueueAbsorbsMicroBurst() throws InterruptedException {
        int threads = 2;
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(threads, new AtomicLong());
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger started = new AtomicInteger();
            CountDownLatch workersEntered = new CountDownLatch(threads);
            // Occupy both workers with blocked tasks.
            for (int i = 0; i < threads; i++) {
                exec.execute(() -> {
                    started.incrementAndGet();
                    workersEntered.countDown();
                    try {release.await();} catch (InterruptedException ie) {Thread.currentThread().interrupt();}
                });
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS));
            // Queue a micro-burst under the capacity limit — must not reject.
            int burst = Math.min(8, TunnelControllerGroup.RUNNER_BURST_QUEUE);
            for (int i = 0; i < burst; i++) {
                exec.execute(started::incrementAndGet);
            }
            release.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            int expected = threads + burst;
            while (started.get() < expected && System.currentTimeMillis() < deadline) {
                try {Thread.sleep(5);} catch (InterruptedException ie) {Thread.currentThread().interrupt(); break;}
            }
            assertEquals("burst tasks must all run after workers free", expected, started.get());
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

    /** Idle threads are reclaimable (allowCoreThreadTimeOut with core == max). */
    @Test
    public void testIdleThreadsAreReclaimable() {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(8, new AtomicLong());
        try {
            assertTrue("idle runner threads must time out", exec.allowsCoreThreadTimeOut());
            assertEquals("core == max so the burst queue is not entered with zero workers",
                         8, exec.getCorePoolSize());
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
            assertTrue("core must be >= 1 when a real queue is present", exec.getCorePoolSize() >= 1);
        } finally {
            exec.shutdownNow();
        }
    }

    /** Burst queue capacity is the documented constant (not unbounded). */
    @Test
    public void testBurstQueueCapacityIsBounded() {
        ThreadPoolExecutor exec = TunnelControllerGroup.createRunnerExecutor(4, new AtomicLong());
        try {
            assertEquals(TunnelControllerGroup.RUNNER_BURST_QUEUE, exec.getQueue().remainingCapacity());
            assertTrue("queue must be finite", TunnelControllerGroup.RUNNER_BURST_QUEUE > 0);
            assertTrue("queue must be finite", TunnelControllerGroup.RUNNER_BURST_QUEUE < 4096);
        } finally {
            exec.shutdownNow();
        }
    }
}
