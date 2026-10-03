package net.i2p.stat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link Rate#clearSummaryListener}, the ownership-guarded listener
 * removal that stops a torn-down listener from clearing a live replacement's
 * registration.
 *
 * <p>The defect this guards against was silent and permanent: the survivor's
 * database stayed open, so every health check reported it as attached, and no
 * revive pass ever re-armed it. See {@link Rate#clearSummaryListener}.
 *
 * @since 0.9.71+
 */
public class RateListenerOwnershipTest {

    /** Records what it was handed, so tests can assert on delivery. */
    private static final class RecordingListener implements RateSummaryListener {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Double> lastValue = new AtomicReference<>(Double.NaN);

        @Override
        public void add(double totalValue, long eventCount, double totalEventTime, long period) {
            calls.incrementAndGet();
            lastValue.set(totalValue);
        }
    }

    @Test
    public void clearRemovesTheRegisteredListener() {
        Rate rate = new Rate(60000);
        RecordingListener lsnr = new RecordingListener();
        rate.setSummaryListener(lsnr);
        assertSame(lsnr, rate.getSummaryListener());

        assertTrue(rate.clearSummaryListener(lsnr));
        assertNull(rate.getSummaryListener());
    }

    @Test
    public void clearIsNoOpWhenRegistrationHeldBySomeoneElse() {
        Rate rate = new Rate(60000);
        RecordingListener oldLsnr = new RecordingListener();
        RecordingListener newLsnr = new RecordingListener();

        rate.setSummaryListener(oldLsnr);
        // A replacement registers before the old listener tears down.
        rate.setSummaryListener(newLsnr);

        // The stale teardown must not clear the live registration.
        assertFalse(rate.clearSummaryListener(oldLsnr));
        assertSame("live listener must survive a stale teardown",
                   newLsnr, rate.getSummaryListener());
    }

    @Test
    public void clearIsNoOpWhenNothingRegistered() {
        Rate rate = new Rate(60000);
        RecordingListener lsnr = new RecordingListener();

        assertFalse(rate.clearSummaryListener(lsnr));
        assertNull(rate.getSummaryListener());
    }

    @Test
    public void clearWithNullExpectationDoesNotRemoveALiveListener() {
        Rate rate = new Rate(60000);
        RecordingListener lsnr = new RecordingListener();
        rate.setSummaryListener(lsnr);

        assertFalse(rate.clearSummaryListener(null));
        assertSame(lsnr, rate.getSummaryListener());
    }

    @Test
    public void secondClearOfSameListenerIsNoOp() {
        Rate rate = new Rate(60000);
        RecordingListener lsnr = new RecordingListener();
        rate.setSummaryListener(lsnr);

        assertTrue(rate.clearSummaryListener(lsnr));
        assertFalse("clearing an already-absent registration must report no change",
                    rate.clearSummaryListener(lsnr));
    }

    @Test
    public void clearIsIdempotentAcrossReRegistration() {
        Rate rate = new Rate(60000);
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();

        rate.setSummaryListener(first);
        assertTrue(rate.clearSummaryListener(first));

        rate.setSummaryListener(second);
        assertTrue(rate.clearSummaryListener(second));
        assertNull(rate.getSummaryListener());
    }

    /**
     * A registration that was never set must never deliver, and clearing must not
     * resurrect it.
     */
    @Test
    public void clearedListenerReceivesNoSamples() throws Exception {
        Rate rate = new Rate(1);
        RecordingListener lsnr = new RecordingListener();
        rate.setSummaryListener(lsnr);
        rate.clearSummaryListener(lsnr);

        rate.addData(1, 1);
        rate.coalesce();

        assertEquals("no delivery after the registration is cleared", 0, lsnr.calls.get());
    }

    /**
     * The ordering the whole mechanism depends on: a listener registered after a
     * clear is delivered to, proving the guard is not leaving the rate inert.
     */
    @Test
    public void liveListenerStillReceivesSamples() throws Exception {
        Rate rate = new Rate(1);
        RecordingListener lsnr = new RecordingListener();
        rate.setSummaryListener(lsnr);
        rate.clearSummaryListener(lsnr);
        rate.setSummaryListener(lsnr);

        rate.addData(1, 1);
        rate.coalesce();

        assertEquals(1, lsnr.calls.get());
    }

    /**
     * The guard's whole purpose, under contention: a stale listener clearing its
     * own registration must never remove the live listener's registration, no
     * matter how the two threads interleave.
     *
     * <p>Note what this deliberately does not assert. A clear that succeeds and is
     * then followed by a legitimate re-register is not a defect — the register came
     * later. The invariant is directional: a clear of the wrong listener never
     * displaces the right one.
     */
    @Test
    public void staleClearNeverDisplacesTheLiveListenerUnderContention() throws Exception {
        for (int round = 0; round < 100; round++) {
            Rate rate = new Rate(1);
            RecordingListener live = new RecordingListener();
            RecordingListener stale = new RecordingListener();

            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger staleClearedLive = new AtomicInteger();
            Thread registrar = new Thread(() -> {
                try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { return; }
                for (int i = 0; i < 500; i++)
                    rate.setSummaryListener(live);
            });
            Thread clearer = new Thread(() -> {
                try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { return; }
                for (int i = 0; i < 500; i++) {
                    if (rate.clearSummaryListener(stale))
                        staleClearedLive.incrementAndGet();
                    // Observe the registration immediately after the clear: if the
                    // guard failed, the live listener is gone right here.
                    if (rate.getSummaryListener() == null)
                        return;
                }
            });
            registrar.start();
            clearer.start();
            go.countDown();
            registrar.join(10000);
            clearer.join(10000);

            assertEquals("a stale listener cleared the live registration", 0,
                         staleClearedLive.get());
        }
    }

    /**
     * Repeated clears of the same listener must not resurrect it, and a clear
     * racing a clear of a different listener must be idempotent.
     */
    @Test
    public void concurrentClearsOfOneListenerDoNotResurrectIt() throws Exception {
        for (int round = 0; round < 50; round++) {
            Rate rate = new Rate(1);
            RecordingListener lsnr = new RecordingListener();
            rate.setSummaryListener(lsnr);

            int threads = 4;
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger successes = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { return; }
                    for (int i = 0; i < 200; i++) {
                        if (rate.clearSummaryListener(lsnr))
                            successes.incrementAndGet();
                    }
                    done.countDown();
                }).start();
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));

            assertEquals("exactly one clear may report success", 1, successes.get());
            assertNull(rate.getSummaryListener());
        }
    }
}
