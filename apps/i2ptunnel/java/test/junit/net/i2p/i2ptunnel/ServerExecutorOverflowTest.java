package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * {@link TunnelControllerGroup#createServerExecutor(int, AtomicLong)} overflow
 * semantics.
 *
 * <p>Regression guard for the accept-loop starvation bug: the shared server
 * handler executor must NOT run a handler inline on the submitting thread when
 * the fixed pool and its bounded queue are saturated. The former
 * {@code CallerRunsPolicy} did exactly that: a write-blocked handler executed
 * on the {@code I2PTunnelServer.run()} accept thread, pinning {@code accept()},
 * stopping the streaming SYN queue from draining, and leading to every fresh
 * connection expiring and being RESET under load.
 *
 * @since 0.9.71+
 */
public class ServerExecutorOverflowTest {

    /**
     * The bounded queue (1024) must be FULL before a submission is rejected; a
     * rejection must surface as a {@link RejectedExecutionException} rather than
     * running the handler inline on the submitting (accept-loop) thread.
     */
    @Test
    public void testSaturatedPoolRejectsNotInlines() throws InterruptedException {
        int threads = 1;
        ThreadPoolExecutor exec = TunnelControllerGroup.createServerExecutor(threads, new AtomicLong());
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            // Occupy the single worker plus the full bounded queue (1024) so the
            // pool is genuinely saturated: 1 running + 1024 queued.
            int queueCapacity = 1024;
            int toSubmit = threads + queueCapacity;
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(threads);
            for (int i = 0; i < toSubmit; i++) {
                exec.execute(() -> {
                    entered.countDown();
                    await(release);
                });
            }
            entered.await(); // first `threads` tasks are running on workers
            // Queue is now full; the next submission must be REJECTED (AbortPolicy),
            // not executed inline on THIS thread (the former CallerRunsPolicy bug).
            AtomicBoolean rejectedTaskRan = new AtomicBoolean(false);
            try {
                exec.execute(() -> rejectedTaskRan.set(true));
                fail("expected RejectedExecutionException from a saturated pool");
            } catch (RejectedExecutionException expected) {
                // correct behavior
            }
            assertFalse("rejected task must NOT run inline on the submitting (accept-loop) thread",
                        rejectedTaskRan.get());
        } finally {
            release.countDown();
            exec.shutdownNow();
        }
    }

    /**
     * A pool with free capacity still accepts and runs the task (no regression of
     * normal dispatching).
     */
    @Test
    public void testUnderLoadedPoolRunsTask() {
        int threads = 1;
        ThreadPoolExecutor exec = TunnelControllerGroup.createServerExecutor(threads, new AtomicLong());
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            exec.execute(() -> ran.set(true));
            // wait for completion
            for (int i = 0; i < 1000 && !ran.get(); i++) {
                try { Thread.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            assertTrue("task should run on a non-saturated pool", ran.get());
        } finally {
            exec.shutdownNow();
        }
    }

    /** Worker threads are daemon (verified across normal test runs) and named. */
    @Test
    public void testWorkerThreadsAreDaemonAndNamed() throws InterruptedException {
        ThreadPoolExecutor exec = TunnelControllerGroup.createServerExecutor(1, new AtomicLong());
        try {
            Object[] holder = new Object[1];
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            exec.execute(() -> { holder[0] = Thread.currentThread(); done.countDown(); });
            done.await();
            Thread t = (Thread) holder[0];
            assertTrue("server handler threads must be daemon", t.isDaemon());
            assertTrue("server handler thread should be named TunnelServer.*: " + t.getName(),
                       t.getName().startsWith("TunnelServer."));
        } finally {
            exec.shutdownNow();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch l) {
        try { l.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
