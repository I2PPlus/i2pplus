package net.i2p.stat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link RateSampleDelivery}, the off-timer queue that keeps RRD writes
 * off the shared two-thread coalesce timer.
 *
 * <p>Three properties carry real risk, and each is asserted here.
 *
 * <p><b>Order.</b> Delivery is FIFO on a single consumer, because an RRD rejects a
 * sample whose timestamp does not advance — out-of-order delivery corrupts a
 * database rather than merely misordering it.
 *
 * <p><b>Same-step supersession.</b> Storage holds one value per archive step, so
 * when two samples for one step arrive the older can never be written. The queue
 * must therefore replace the tail rather than grow, and must say so through
 * {@link RateSampleDelivery#getSuperseded()}. A queue that grows on duplicates
 * eventually overruns on a consumer that is merely slow, and turns a harmless
 * repeat into counted data loss.
 *
 * <p><b>Hard overflow.</b> Only reaching capacity evicts anything, and that is
 * the counter that must stay zero, so it is asserted exactly rather than
 * "at least".
 *
 * <p>{@code RateSampleDelivery} accepts a null context, so no router is needed.
 *
 * @since 0.9.71+
 */
public class RateSampleDeliveryTest {

    /** Rate period used throughout; one archive step per {@link #PERIOD} ms. */
    private static final long PERIOD = 60000;

    /** Collects samples in arrival order. */
    private static final class CollectingListener implements RateSummaryListener {
        final List<Double> values = Collections.synchronizedList(new ArrayList<Double>());
        final CountDownLatch received;

        CollectingListener(int expected) {
            received = new CountDownLatch(expected);
        }

        @Override
        public void add(double totalValue, long eventCount, double totalEventTime, long period) {
            values.add(totalValue);
            received.countDown();
        }

        synchronized List<Double> snapshot() {
            return new ArrayList<>(values);
        }
    }

    /**
     * Parks the consumer inside its first delivery so the queue can be filled
     * deterministically, and records everything it is handed once released.
     */
    private static final class BlockingListener implements RateSummaryListener {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final List<Double> values = Collections.synchronizedList(new ArrayList<Double>());

        @Override
        public void add(double totalValue, long eventCount, double totalEventTime, long period) {
            values.add(totalValue);
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized List<Double> snapshot() {
            return new ArrayList<>(values);
        }
    }

    /** Records deliveries, then throws, mimicking a listener whose database failed. */
    private static final class ThrowingListener implements RateSummaryListener {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch failed = new CountDownLatch(1);

        @Override
        public void add(double totalValue, long eventCount, double totalEventTime, long period) {
            calls.incrementAndGet();
            failed.countDown();
            throw new IllegalStateException("deliberate test failure");
        }
    }

    /** Stamp placing a sample in archive step {@code step}. */
    private static long atStep(long step) {
        return step * PERIOD;
    }

    /** Wait for a listener to have recorded {@code n} samples. */
    private static void awaitSize(List<Double> values, int n, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (values) {
                if (values.size() >= n)
                    return;
            }
            Thread.sleep(5);
        }
        synchronized (values) {
            assertEquals(what, n, values.size());
        }
    }

    @Test
    public void deliversASubmittedSample() throws Exception {
        CollectingListener lsnr = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 16);
        try {
            assertTrue(delivery.submit(lsnr, 1.0, 1, 1.0, PERIOD, atStep(1)));
            assertTrue("sample was not delivered within timeout", lsnr.received.await(10, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1.0), lsnr.snapshot());
            assertEquals(1, delivery.getDelivered());
            assertEquals(0, delivery.getOverruns());
            assertEquals(0, delivery.getSuperseded());
        } finally {
            delivery.shutdown();
        }
    }

    @Test
    public void passesEveryArgumentThroughUnchanged() throws Exception {
        final List<Object[]> seen = new ArrayList<>();
        CountDownLatch got = new CountDownLatch(1);
        RateSummaryListener lsnr = (totalValue, eventCount, totalEventTime, period) -> {
            synchronized (seen) { seen.add(new Object[] {totalValue, eventCount, totalEventTime, period}); }
            got.countDown();
        };
        RateSampleDelivery delivery = new RateSampleDelivery(null, 16);
        try {
            delivery.submit(lsnr, 42.5, 7L, 13.25, PERIOD, atStep(3));
            assertTrue(got.await(10, TimeUnit.SECONDS));
            assertEquals(1, seen.size());
            assertEquals(42.5, (Double) seen.get(0)[0], 0.0001);
            assertEquals(7L, seen.get(0)[1]);
            assertEquals(13.25, (Double) seen.get(0)[2], 0.0001);
            assertEquals(PERIOD, seen.get(0)[3]);
        } finally {
            delivery.shutdown();
        }
    }

    /**
     * FIFO across distinct steps. Timestamps are spread one period apart so no two
     * samples compete for the same archive step and supersession cannot mask an
     * ordering defect.
     */
    @Test
    public void preservesSubmissionOrder() throws Exception {
        int samples = 200;
        CollectingListener lsnr = new CollectingListener(samples);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 256);
        try {
            for (int i = 0; i < samples; i++)
                assertTrue(delivery.submit(lsnr, i, 1, 1.0, PERIOD, atStep(i)));
            assertTrue(lsnr.received.await(10, TimeUnit.SECONDS));

            List<Double> got = lsnr.snapshot();
            assertEquals(samples, got.size());
            for (int i = 0; i < samples; i++)
                assertEquals("sample " + i + " arrived out of order", (double) i, got.get(i), 0.0001);
            assertEquals("a distinct-step backlog must not supersede anything", 0, delivery.getSuperseded());
            assertEquals(0, delivery.getOverruns());
        } finally {
            delivery.shutdown();
        }
    }

    /**
     * Two samples for one archive step are not two pieces of history: storage keeps
     * one value per step, so the older is unwritable and the queue must replace it
     * rather than grow. The replacement is also the correct value to deliver.
     */
    @Test
    public void sameStepSupersedesTheTailRatherThanGrowing() throws Exception {
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, 64);
        try {
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            long ts = atStep(1);
            assertTrue(delivery.submit(blocker, 10, 1, 1.0, PERIOD, ts));
            assertEquals(1, delivery.getPending());
            assertEquals("a same-step sample must replace the tail", 1, delivery.getPending());
            assertTrue(delivery.submit(blocker, 20, 1, 1.0, PERIOD, ts + PERIOD / 2));
            assertEquals("a same-step sample must not grow the queue", 1, delivery.getPending());
            assertEquals(1, delivery.getSuperseded());
            assertEquals("supersession is not an overrun", 0, delivery.getOverruns());

            blocker.release.countDown();
            awaitSize(blocker.values, 2, "samples delivered after release");
            assertEquals("the newer value for a step is the one that must be delivered",
                         Arrays.asList(-1.0, 20.0), blocker.snapshot());
            assertEquals(2, delivery.getDelivered());
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    /**
     * Supersession must not fire across a step boundary: consecutive steps are
     * separate history and both must be delivered.
     */
    @Test
    public void differentStepIsNotSuperseded() throws Exception {
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, 64);
        try {
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            assertTrue(delivery.submit(blocker, 10, 1, 1.0, PERIOD, atStep(1)));
            assertTrue(delivery.submit(blocker, 20, 1, 1.0, PERIOD, atStep(2)));
            assertEquals("consecutive steps are distinct history", 2, delivery.getPending());
            assertEquals(0, delivery.getSuperseded());

            blocker.release.countDown();
            awaitSize(blocker.values, 3, "samples delivered after release");
            assertEquals(Arrays.asList(-1.0, 10.0, 20.0), blocker.snapshot());
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    /**
     * The step is only mergeable for the listener that owns it. Two distinct
     * listeners for one rate compare equal by rate name, so this also pins the
     * identity test the supersession check relies on.
     */
    @Test
    public void differentListenerIsNotSuperseded() throws Exception {
        BlockingListener blocker = new BlockingListener();
        CollectingListener other = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 64);
        try {
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            // Tail belongs to `other`; the incoming sample is for `blocker`.
            assertTrue(delivery.submit(other, 10, 1, 1.0, PERIOD, atStep(1)));
            assertTrue(delivery.submit(blocker, 20, 1, 1.0, PERIOD, atStep(1)));
            assertEquals("one listener's step must not be merged into another's", 2, delivery.getPending());
            assertEquals(0, delivery.getSuperseded());

            blocker.release.countDown();
            assertTrue(other.received.await(10, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(10.0), other.snapshot());
            awaitSize(blocker.values, 2, "samples delivered to the first listener");
            assertEquals(Arrays.asList(-1.0, 20.0), blocker.snapshot());
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    /**
     * The policy that actually loses data: only a full queue evicts, and then it
     * keeps the newest samples, because a graph whose tail is frozen is the
     * failure this class exists to prevent. The overrun is counted exactly, so it
     * cannot be quietly absorbed.
     */
    @Test
    public void hardOverflowEvictsOldestAndCountsTheOverrun() throws Exception {
        int capacity = 4;
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, capacity);
        try {
            // Park the consumer inside the first delivery.
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            // Fill the 4-slot queue with distinct steps.
            for (int i = 1; i <= capacity; i++)
                delivery.submit(blocker, i, 1, 1.0, PERIOD, atStep(i));
            assertEquals(capacity, delivery.getPending());
            assertEquals(0, delivery.getOverruns());

            // Two more than the queue can hold: both admitted, evicting the two
            // oldest pending samples.
            assertTrue(delivery.submit(blocker, 100, 1, 1.0, PERIOD, atStep(5)));
            assertTrue(delivery.submit(blocker, 101, 1, 1.0, PERIOD, atStep(6)));

            assertEquals("every eviction must be counted", 2, delivery.getOverruns());
            assertEquals(capacity, delivery.getPending());
            assertEquals("an eviction is not a supersession", 0, delivery.getSuperseded());

            blocker.release.countDown();
            awaitSize(blocker.values, capacity + 1, "samples delivered after release");
            assertEquals("the oldest pending samples must be the casualties, and the newest must survive",
                         Arrays.asList(-1.0, 3.0, 4.0, 100.0, 101.0), blocker.snapshot());
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    @Test
    public void blockingListenerDoesNotStallSubmission() throws Exception {
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, 8);
        try {
            delivery.submit(blocker, 1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            // The whole point of the class: submitting must not block while the
            // consumer is stuck in I/O.
            long start = System.nanoTime();
            for (int i = 0; i < 8; i++)
                delivery.submit(blocker, i, 1, 1.0, PERIOD, atStep(i + 1));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue("submission blocked for " + elapsedMs + "ms behind a stalled listener",
                       elapsedMs < 2000);
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    /**
     * A listener that throws must not kill the consumer: the remaining samples
     * belong to other rates, and losing the thread would silently stop delivery
     * for all of them.
     */
    @Test
    public void throwingListenerDoesNotStopTheConsumer() throws Exception {
        ThrowingListener bad = new ThrowingListener();
        CollectingListener good = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 16);
        try {
            delivery.submit(bad, 1, 1, 1.0, PERIOD, atStep(1));
            assertTrue(bad.failed.await(10, TimeUnit.SECONDS));

            delivery.submit(good, 99, 1, 1.0, PERIOD, atStep(1));
            assertTrue("consumer died on a throwing listener",
                       good.received.await(10, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(99.0), good.snapshot());
            assertEquals("a throwing sample must not count as delivered", 1, delivery.getDelivered());
        } finally {
            delivery.shutdown();
        }
    }

    /**
     * A rate with no listener is the normal case when no console is attached, so a
     * null listener must be skipped rather than throwing on the consumer — which
     * would otherwise be logged once per rate per period.
     */
    @Test
    public void nullListenerIsSkippedAndTheConsumerSurvives() throws Exception {
        CollectingListener good = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 16);
        try {
            delivery.submit(null, 1, 1, 1.0, PERIOD, atStep(1));
            delivery.submit(good, 42, 1, 1.0, PERIOD, atStep(1));
            assertTrue("consumer died on a null listener", good.received.await(10, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(42.0), good.snapshot());
            assertEquals("a null-listener sample is not a delivery", 1, delivery.getDelivered());
        } finally {
            delivery.shutdown();
        }
    }

    /**
     * The queue's staleness is the live signal that the consumer has stopped
     * making progress, and it keeps rising while a listener is wedged — which is
     * when the overrun counter can no longer report anything useful.
     */
    @Test
    public void oldestPendingAgeReportsQueueStaleness() throws Exception {
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, 8);
        try {
            assertEquals("an empty queue has no staleness", 0, delivery.getOldestPendingAgeMs());
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            long fiveSecondsAgo = System.currentTimeMillis() - 5000;
            assertTrue(delivery.submit(blocker, 1, 1, 1.0, PERIOD, fiveSecondsAgo));
            assertEquals(1, delivery.getPending());
            assertTrue("the oldest pending age must reflect the head's step stamp",
                       delivery.getOldestPendingAgeMs() >= 5000);
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    /**
     * A stamp from the step just past the current wall clock is normal for a rate
     * whose period has only just turned over, so the age must clamp at zero rather
     * than read as negative.
     */
    @Test
    public void oldestPendingAgeClampsAFutureStepStampToZero() throws Exception {
        BlockingListener blocker = new BlockingListener();
        RateSampleDelivery delivery = new RateSampleDelivery(null, 8);
        try {
            delivery.submit(blocker, -1, 1, 1.0, PERIOD, atStep(0));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));

            long justAhead = System.currentTimeMillis() + 60000;
            assertTrue(delivery.submit(blocker, 1, 1, 1.0, PERIOD, justAhead));
            assertEquals("a future step stamp is not a negative age",
                         0, delivery.getOldestPendingAgeMs());
        } finally {
            blocker.release.countDown();
            delivery.shutdown();
        }
    }

    @Test
    public void shutdownIsIdempotent() {
        RateSampleDelivery delivery = new RateSampleDelivery(null, 4);
        delivery.shutdown();
        delivery.shutdown();
        assertTrue(delivery.isStopped());
    }

    @Test
    public void submitAfterShutdownIsRejectedWithoutThrowing() {
        CollectingListener lsnr = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 4);
        delivery.shutdown();
        assertFalse("submit must refuse once shutdown has run",
                    delivery.submit(lsnr, 1, 1, 1.0, PERIOD, atStep(1)));
        assertTrue("a sample rejected after shutdown must never be delivered",
                   lsnr.values.isEmpty());
        assertEquals(0, delivery.getDelivered());
    }

    /**
     * shutdown() must not return while the consumer is still running, otherwise a
     * caller that closes the listeners' databases right afterwards races an
     * in-flight delivery.
     *
     * <p>The listener deliberately ignores {@link Thread#interrupt()}: a write
     * blocked on a stalled disk is not interruptible, which is the whole reason
     * delivery cannot simply be abandoned at shutdown.
     */
    @Test
    public void shutdownWaitsForTheConsumerToStop() throws Exception {
        final CountDownLatch delivering = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);
        final AtomicBoolean shutdownReturned = new AtomicBoolean();
        RateSummaryListener blocked = (totalValue, eventCount, totalEventTime, period) -> {
            delivering.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = mayFinish.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    // Ignored on purpose: models non-interruptible blocking I/O.
                }
            }
        };
        RateSampleDelivery delivery = new RateSampleDelivery(null, 8);
        delivery.submit(blocked, 1, 1, 1.0, PERIOD, atStep(1));
        assertTrue(delivering.await(10, TimeUnit.SECONDS));

        Thread caller = new Thread(() -> {
            delivery.shutdown();
            // Recorded after shutdown returns, so this thread can assert that no
            // delivery thread is still alive at that point.
            shutdownReturned.set(true);
        });
        caller.start();
        Thread.sleep(200);
        assertFalse("shutdown must still be waiting on the in-flight delivery",
                    shutdownReturned.get());
        mayFinish.countDown();
        caller.join(10000);

        assertTrue(shutdownReturned.get());
        assertFalse("delivery thread must be gone once shutdown returns",
                    isThreadAlive("StatSampleDelivery"));
    }

    private static boolean isThreadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive())
                return true;
        }
        return false;
    }

    /**
     * A null listener must be refused, not enqueued.
     *
     * <p>Regression guard for a production failure: {@code Rate.coalesce()} briefly
     * lost its null-listener guard when delivery moved off-thread, so every
     * listener-less rate - the overwhelming majority - enqueued a null listener that
     * threw on the consumer thread once per coalesce cycle, producing thousands of
     * logged NullPointerExceptions.
     */
@Test
public void nullListenerIsRefusedRatherThanEnqueued() throws Exception {
    RateSampleDelivery delivery = new RateSampleDelivery(null, 8);
    try {
        assertFalse(delivery.submit(null, 1.0, 1, 1.0, PERIOD, atStep(1)));
        assertEquals("a refused sample must not occupy the queue", 0, delivery.getPending());
        assertEquals(0, delivery.getOverruns());
    } finally {
        delivery.shutdown();
    }
}

/**
 * The consumer must survive a burst of null submissions, which is what the
 * missing guard produced in production.
 */
@Test
public void burstOfNullSubmissionsDoesNotKillTheConsumer() throws Exception {
    CollectingListener lsnr = new CollectingListener(1);
    RateSampleDelivery delivery = new RateSampleDelivery(null, 64);
    try {
        for (int i = 0; i < 2000; i++)
            assertFalse(delivery.submit(null, i, 1, 1.0, PERIOD, atStep(i)));

        delivery.submit(lsnr, 42, 1, 1.0, PERIOD, atStep(2001));
        assertTrue("consumer must still deliver after a burst of null submissions",
                   lsnr.received.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Arrays.asList(42.0), lsnr.snapshot());
    } finally {
        delivery.shutdown();
    }
}

@Test
    public void queueSurvivesShutdownAndDrains() throws Exception {
        CollectingListener lsnr = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 16);
        delivery.submit(lsnr, 7, 1, 1.0, PERIOD, atStep(1));
        delivery.shutdown();
        assertTrue("queued sample must still be delivered after shutdown",
                   lsnr.received.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void deliveryThreadIsADaemon() throws Exception {
        RateSampleDelivery delivery = new RateSampleDelivery(null, 4);
        try {
            Thread found = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("StatSampleDelivery".equals(t.getName()) && t.isAlive()) {
                    found = t;
                    break;
                }
            }
            assertNotNull("delivery thread not found", found);
            assertTrue("delivery thread must be a daemon so it cannot hold the JVM open",
                       found.isDaemon());
        } finally {
            delivery.shutdown();
        }
    }

    @Test
    public void nonPositiveCapacityIsClampedRatherThanThrowing() {
        CollectingListener lsnr = new CollectingListener(1);
        RateSampleDelivery delivery = new RateSampleDelivery(null, 0);
        try {
            assertEquals(0, delivery.getPending());
            assertTrue("a clamped queue must still accept one sample",
                       delivery.submit(lsnr, 1, 1, 1.0, PERIOD, atStep(1)));
            assertTrue(lsnr.received.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("interrupted while awaiting delivery");
        } finally {
            delivery.shutdown();
        }
    }
}
