package net.i2p.router.tunnel;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 *  Concurrency tests for the HopConfig pumped-message counters.
 *  Pure data class - no I2P context needed.
 *
 *  The processed-message counter is incremented once per message pumped through
 *  every participating tunnel, so losing an increment silently understates router
 *  stats. These tests fail if the counter is not a true atomic accumulator.
 */
public class HopConfigConcurrencyTest {

    private static final int THREADS = 8;
    private static final int INCREMENTS = 10000;
    private static final long TIMEOUT_MS = 30000;

    /**
     *  Every increment from every thread must be counted exactly once.
     *  A read-modify-write race (i.e. a plain int) drops increments here.
     *
     *  @throws Exception if a worker thread is interrupted
     */
    @Test
    public void testConcurrentIncrementsAreAllCounted() throws Exception {
        HopConfig cfg = new HopConfig();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Thread[] workers = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            workers[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        startGate.await();
                        for (int n = 0; n < INCREMENTS; n++)
                            cfg.incrementProcessedMessages();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }
            }, "hopcfg-inc-" + i);
            workers[i].start();
        }

        startGate.countDown();
        assertTrue("workers did not finish within " + TIMEOUT_MS + "ms",
                   done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        for (Thread t : workers)
            t.join(TIMEOUT_MS);

        if (failure.get() != null)
            throw new AssertionError("worker thread failed: " + failure.get());
        assertEquals(THREADS * INCREMENTS, cfg.getProcessedMessagesCount());
        assertEquals(THREADS * INCREMENTS, cfg.getRecentMessagesCount());
    }

    /**
     *  The reset window must start where the previous one ended: the delta plus the
     *  totals must always add up, with no increment lost or double counted.
     *
     *  @throws Exception if a worker thread is interrupted
     */
    @Test
    public void testResetWindowAccountingUnderContention() throws Exception {
        final HopConfig cfg = new HopConfig();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Thread[] workers = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            workers[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        startGate.await();
                        for (int n = 0; n < INCREMENTS; n++)
                            cfg.incrementProcessedMessages();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }
            }, "hopcfg-win-" + i);
            workers[i].start();
        }

        long sum = 0;
        while (done.getCount() > 0) {
            sum += cfg.getAndResetRecentMessagesCount();
            if (done.await(1, TimeUnit.MILLISECONDS)) break;
        }
        sum += cfg.getAndResetRecentMessagesCount();
        startGate.countDown();
        for (Thread t : workers)
            t.join(TIMEOUT_MS);

        if (failure.get() != null)
            throw new AssertionError("worker thread failed: " + failure.get());
        assertEquals("reset windows lost or duplicated increments", THREADS * INCREMENTS, sum);
        assertEquals(THREADS * INCREMENTS, cfg.getProcessedMessagesCount());
    }

    /**
     *  A reset returns the messages processed since the last reset, and an
     *  immediate second call returns nothing because the baseline was advanced.
     */
    @Test
    public void testGetAndResetRecentMessagesCountDelta() {
        HopConfig cfg = new HopConfig();
        assertEquals(0, cfg.getAndResetRecentMessagesCount());
        cfg.incrementProcessedMessages();
        cfg.incrementProcessedMessages();
        cfg.incrementProcessedMessages();
        assertEquals(3, cfg.getAndResetRecentMessagesCount());
        assertEquals(0, cfg.getAndResetRecentMessagesCount());
        cfg.incrementProcessedMessages();
        assertEquals(1, cfg.getAndResetRecentMessagesCount());
        assertEquals(0, cfg.getAndResetRecentMessagesCount());
    }

    /**
     *  The read-only recent count must track the total while no reset has happened.
     */
    @Test
    public void testRecentCountFollowsTotalWithoutReset() {
        HopConfig cfg = new HopConfig();
        for (int i = 0; i < 1000; i++) {
            cfg.incrementProcessedMessages();
            assertEquals(i + 1, cfg.getProcessedMessagesCount());
            assertEquals(i + 1, cfg.getRecentMessagesCount());
        }
    }
}
