package net.i2p.stat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Concurrency contract for {@link Rate} and {@link RateStat} accumulation.
 *
 * <p>This is the invariant set that the rate hot path depends on and that no
 * existing test covered: {@link StatManager#addRateData} is called from per-packet,
 * per-fragment and per-job threads, so every router thread is a writer to these
 * counters. Three properties have to hold simultaneously.
 *
 * <ol>
 * <li><b>No lost or duplicated counts.</b> Every increment must land exactly once.
 * This is what the monitor is for; a lock-free or {@code LongAdder} redesign has to
 * reproduce it.</li>
 * <li><b>No torn composite.</b> {@link Rate#getAverageValue()} divides the last
 * period's total by its event count, so those two must always come from the same
 * period. Two independently-updated accumulators cannot promise this: a reader can
 * observe a total and a count that never coexisted. Every value added here is 1, so
 * a consistent reader must see an average of exactly 1.0 whenever the count is
 * nonzero - anything else is a torn read.</li>
 * <li><b>Period boundary correctness.</b> A coalesce moves the current partial
 * period into the last period exactly once; data accrued afterwards must start the
 * next period rather than leak backwards.</li>
 * </ol>
 *
 * <p>No router context is needed: these exercise {@link Rate} and {@link RateStat}
 * directly, as the rest of this package's tests do.
 *
 * @since 0.9.71+
 */
public class RateConcurrentUpdateTest {

    /** Threads per test. Enough to make lost updates likely, few enough not to
     * starve the timing-sensitive tests sharing this JVM. */
    private static final int THREADS = 4;

    /**
     * Per-thread updates. The product must stay well below 2^24 so that
     * accumulating 1.0 into a float stays exact and the assertions can be about the
     * counting and not about float rounding.
     */
    private static final int PER_THREAD = 5_000;

    /** A period of 1ms puts {@code period - SLACK} below zero, so every coalesce
     * is due immediately and no test has to sleep for a period to elapse. */
    private static final long ALWAYS_DUE = 1;

    private static final long TIMEOUT_MS = 10_000;

    /**
     * Reads the concurrent test performs, and coalesces the other test performs.
     *
     * <p>Kept small deliberately. These are the only CPU-bound tests in this
     * package, and {@link RateSampleDeliveryTest} asserts on a latch/counter
     * ordering that a busy machine can stretch, so this test has to get its
     * interleaving without starving its neighbours.
     */
    private static final int READS = 40_000;
    private static final int COALESCES = 2_000;

    /**
     * Run {@code body} on {@link #THREADS} threads released together, and fail if
     * any thread throws.
     */
    private static void runConcurrently(Worker body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < THREADS; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    body.run();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "RateConcurrentUpdateTest-" + t).start();
        }
        start.countDown();
        assertTrue("workers did not finish in time", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        if (failure.get() != null)
            throw new AssertionError("worker threw: " + failure.get(), failure.get());
    }

    private interface Worker { void run() throws Exception; }

    /**
     * Property 1: concurrent increments must neither be lost nor double-counted.
     */
    @Test
    public void concurrentAddDataLosesNoEvents() throws Exception {
        RateStat rs = new RateStat("concurrent", "d", "g", new long[] { 60_000L });
        runConcurrently(() -> {
            for (int i = 0; i < PER_THREAD; i++)
                rs.addData(1L);
        });
        long expected = (long) THREADS * PER_THREAD;
        Rate r = rs.getRate(60_000L);
        assertEquals("lost or duplicated lifetime events", expected, r.getLifetimeEventCount());
        assertEquals("lost or duplicated current events", expected, r.getCurrentEventCount());
        // 1.0 is exactly representable and the total is below 2^24, so this is exact.
        assertEquals((double) expected, r.getLifetimeTotalValue(), 0.0d);
        assertEquals((double) expected, r.getCurrentTotalValue(), 0.0d);
    }

    /** Property 1 again, for the variant that also accrues event durations. */
    @Test
    public void concurrentAddDataWithDurationLosesNoEvents() throws Exception {
        RateStat rs = new RateStat("concurrentDur", "d", "g", new long[] { 60_000L });
        runConcurrently(() -> {
            for (int i = 0; i < PER_THREAD; i++)
                rs.addData(1L, 2L);
        });
        long expected = (long) THREADS * PER_THREAD;
        Rate r = rs.getRate(60_000L);
        assertEquals(expected, r.getLifetimeEventCount());
        assertEquals(expected * 2L, r.getLifetimeTotalEventTime());
        assertEquals((double) expected, r.getLifetimeTotalValue(), 0.0d);
        assertEquals("average of N ones is 1", 1.0d, r.getLifetimeAverageValue(), 0.0d);
    }

    /**
     * Property 2: a reader must never see a total and a count from different
     * periods, and must never see a negative, NaN or infinite derived value while
     * writers and a coalescer run.
     *
     * <p>Every value added is exactly 1, so in any single period the total and the
     * event count are equal, and the average over a nonzero count is therefore
     * exactly 1.0. A reader that caught a total and a count from different periods
     * would divide two unrelated numbers and get something else - 0.6, say - so
     * "the average is always 1.0 or there were no events" is exactly the
     * no-torn-composite property, stated in a way that cannot be faked.
     *
     * <p>The count is deliberately <em>not</em> sampled separately alongside the
     * average. {@code getAverageValue()} and {@code getLastEventCount()} are two
     * calls, so a coalesce landing between them would hand the reader a count from
     * one period and an average from the next - a test artefact, not a code defect.
     * A single call cannot be interleaved, so only its own return value is checked.
     */
    @Test
    public void averageValueIsNeverTornUnderConcurrentUpdate() throws Exception {
        RateStat rs = new RateStat("torn", "d", "g", new long[] { ALWAYS_DUE });
        final Rate r = rs.getRate(ALWAYS_DUE);
        final boolean[] torn = { false };
        final boolean[] bad = { false };
        final long[] reads = { 0 };

        Thread reader = new Thread(() -> {
            for (int i = 0; i < READS; i++) {
                double avg = r.getAverageValue();
                reads[0]++;
                if (Double.isNaN(avg) || Double.isInfinite(avg) || avg < 0.0d)
                    bad[0] = true;
                if (avg != 0.0d && avg != 1.0d)
                    torn[0] = true;
                if (r.getCurrentEventCount() < 0 || r.getLifetimeEventCount() < 0)
                    bad[0] = true;
            }
        }, "rate-reader");
        Thread swap = new Thread(() -> {
            for (int i = 0; i < COALESCES; i++)
                rs.coalesceStats();
        }, "rate-coalescer");
        reader.start();
        swap.start();
        runConcurrently(() -> {
            for (int i = 0; i < PER_THREAD; i++)
                rs.addData(1L);
        });
        reader.join(TIMEOUT_MS);
        swap.join(TIMEOUT_MS);

        assertTrue("reader never ran", reads[0] > 0);
        assertFalse("derived value went negative, NaN or infinite", bad[0]);
        assertFalse("getAverageValue() observed a total and count from different periods", torn[0]);
        assertEquals((long) THREADS * PER_THREAD, r.getLifetimeEventCount());
    }

    /**
     * Property 3: a coalesce hands the whole current period to the last period and
     * leaves the current one empty; the next period starts from zero rather than
     * continuing to accumulate.
     */
    @Test
    public void coalesceMovesExactlyOnePeriod() {
        Rate r = new Rate(ALWAYS_DUE);
        for (int i = 0; i < 5; i++)
            r.addData(1L);
        assertEquals("nothing is in the last period before a coalesce", 0L, r.getLastEventCount());
        assertEquals(5L, r.getCurrentEventCount());

        r.coalesce();
        assertEquals("the current period became the last period", 5L, r.getLastEventCount());
        assertEquals(5.0d, r.getLastTotalValue(), 0.0d);
        assertEquals(1.0d, r.getAverageValue(), 0.0d);
        assertEquals("the current period starts empty", 0L, r.getCurrentEventCount());
        assertEquals(0.0d, r.getCurrentTotalValue(), 0.0d);

        for (int i = 0; i < 3; i++)
            r.addData(1L);
        r.coalesce();
        assertEquals("the second period replaced the first, it did not accumulate onto it",
                     3L, r.getLastEventCount());
        assertEquals("lifetime spans both periods", 8L, r.getLifetimeEventCount());

        r.coalesce();
        assertEquals("an empty period coalesces to an empty last period", 0L, r.getLastEventCount());
        assertEquals("and the average of no events is 0, not a division by zero", 0.0d, r.getAverageValue(), 0.0d);
        assertEquals("lifetime is unaffected by coalescing", 8L, r.getLifetimeEventCount());
    }

    /**
     * A coalesce concurrent with writers must still leave the counters non-negative
     * and the lifetime total consistent with the lifetime count, since the lifetime
     * pair is updated only by addData.
     */
    @Test
    public void coalesceConcurrentWithWritersKeepsCountersSane() throws Exception {
        RateStat rs = new RateStat("mixed", "d", "g", new long[] { ALWAYS_DUE, 2100L });
        final Rate shortPeriod = rs.getRate(ALWAYS_DUE);
        Thread coalescer = new Thread(() -> {
            for (int i = 0; i < COALESCES; i++)
                rs.coalesceStats();
        }, "mixed-coalescer");
        coalescer.start();
        runConcurrently(() -> {
            for (int i = 0; i < PER_THREAD; i++)
                rs.addData(1L);
        });
        coalescer.join(TIMEOUT_MS);

        long expected = (long) THREADS * PER_THREAD;
        assertEquals(expected, shortPeriod.getLifetimeEventCount());
        assertEquals((double) expected, shortPeriod.getLifetimeTotalValue(), 0.0d);
        for (Rate r : new Rate[] { rs.getRate(ALWAYS_DUE), rs.getRate(2100L) }) {
            assertTrue("current event count went negative", r.getCurrentEventCount() >= 0);
            assertTrue("last event count went negative", r.getLastEventCount() >= 0);
            assertTrue("current total went negative", r.getCurrentTotalValue() >= 0.0d);
            assertTrue("last total went negative", r.getLastTotalValue() >= 0.0d);
        }
    }
}
