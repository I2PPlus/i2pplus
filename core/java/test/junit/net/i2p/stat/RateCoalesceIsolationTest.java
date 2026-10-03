package net.i2p.stat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the per-rate coalesce isolation in {@link RateStat#coalesceStats()}.
 *
 * <p>The defect this guards against is the reason the isolation exists.
 * {@link StatManager#coalesceStats()} walks the whole stat table in one pass on the
 * shared coalesce timer, so an exception escaping a single rate used to abandon
 * every stat later in that pass. Their listeners received nothing, their graphs
 * froze, and nothing was logged anywhere - the rate simply stopped recording, which
 * is indistinguishable from healthy to every existing check.
 *
 * @since 0.9.71+
 */
public class RateCoalesceIsolationTest {

    /** A rate that always throws from coalesce, standing in for a faulty stat. */
    private static final class ThrowingRate extends Rate {
        private final Throwable _failure;
        final AtomicInteger calls = new AtomicInteger();

        ThrowingRate(long period, Throwable failure) {
            super(period);
            _failure = failure;
        }

        @Override
        public synchronized void coalesce() {
            calls.incrementAndGet();
            sneakyThrow(_failure);
        }

        /**
         * Throws a checked or unchecked throwable without declaring it, so this
         * stand-in can model an {@link Error} as well as a {@link RuntimeException}
         * without widening {@link Rate#coalesce()}'s signature.
         */
        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
            throw (T) t;
        }
    }

    /** A rate that records that it was coalesced. */
    private static final class CountingRate extends Rate {
        final AtomicInteger coalesces = new AtomicInteger();

        CountingRate(long period) {
            super(period);
        }

        @Override
        public synchronized void coalesce() {
            coalesces.incrementAndGet();
            super.coalesce();
        }
    }

    @Test
    public void aThrowingRateDoesNotStopTheRatesAfterIt() {
        // Order matters: the failing rate must come first, or the healthy one has
        // already coalesced by the time the exception is thrown and the test proves
        // nothing. This is the case that used to abandon every later stat.
        ThrowingRate bad = new ThrowingRate(600000, new IllegalStateException("boom"));
        CountingRate good = new CountingRate(60000);
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {bad, good});

        stat.coalesceStats();

        assertEquals("the rate after the failure must still coalesce", 1, good.coalesces.get());
        assertEquals(1, bad.calls.get());
        assertEquals(1, stat.getCoalesceFailures());
    }

    @Test
    public void aThrowingRateDoesNotStopEarlierSiblings() {
        ThrowingRate bad = new ThrowingRate(600000, new IllegalStateException("boom"));
        CountingRate first = new CountingRate(60000);
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {first, bad});

        stat.coalesceStats();

        assertEquals("a rate before the failure must be unaffected",
                     1, first.coalesces.get());
        assertEquals(1, stat.getCoalesceFailures());
    }

    @Test
    public void everyThrowingRateIsIsolated() {
        ThrowingRate a = new ThrowingRate(60000, new IllegalStateException("a"));
        ThrowingRate b = new ThrowingRate(600000, new IllegalStateException("b"));
        ThrowingRate c = new ThrowingRate(3600000, new IllegalStateException("c"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {a, b, c});

        stat.coalesceStats();

        assertEquals("all three must be attempted", 1, a.calls.get());
        assertEquals(1, b.calls.get());
        assertEquals(1, c.calls.get());
        assertEquals(3, stat.getCoalesceFailures());
    }

    @Test
    public void firstFailureIsIdentifiedByPeriod() {
        ThrowingRate bad = new ThrowingRate(600000, new IllegalStateException("boom"));
        RateStat stat = new RateStat("test.stat", "d", "g",
                                      new Rate[] {new CountingRate(60000), bad});

        stat.coalesceStats();

        assertNotNull("the failing period must be identifiable", stat.getFirstCoalesceFailurePeriod());
        assertEquals("600000", stat.getFirstCoalesceFailurePeriod());
        assertNotNull(stat.getFirstCoalesceFailureCause());
        assertEquals("boom", stat.getFirstCoalesceFailureCause().getMessage());
    }

    @Test
    public void failureCountAccumulatesAcrossSweeps() {
        ThrowingRate bad = new ThrowingRate(60000, new IllegalStateException("boom"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {bad});

        stat.coalesceStats();
        stat.coalesceStats();
        stat.coalesceStats();

        assertEquals(3, stat.getCoalesceFailures());
    }

    @Test
    public void healthyStatReportsNoFailures() {
        CountingRate good = new CountingRate(60000);
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {good});

        stat.coalesceStats();

        assertEquals(0, stat.getCoalesceFailures());
        assertNull(stat.getFirstCoalesceFailurePeriod());
        assertNull(stat.getFirstCoalesceFailureCause());
    }

    /**
     * The first failure is the one reported. A later, different failure must not
     * overwrite it, or the log would name a period that already recovered.
     */
    @Test
    public void firstFailureIsNotOverwrittenByLaterOnes() {
        ThrowingRate first = new ThrowingRate(60000, new IllegalStateException("first"));
        ThrowingRate second = new ThrowingRate(600000, new IllegalStateException("second"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {first, second});

        stat.coalesceStats();

        assertEquals("60000", stat.getFirstCoalesceFailurePeriod());
        assertEquals("first", stat.getFirstCoalesceFailureCause().getMessage());
    }

    @Test
    public void errorsAreIsolatedToo() {
        CountingRate good = new CountingRate(60000);
        ThrowingRate bad = new ThrowingRate(60000, new StackOverflowError("deep"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {bad, good});

        stat.coalesceStats();

        assertEquals("an Error must be isolated like any Throwable",
                     1, good.coalesces.get());
        assertEquals(1, stat.getCoalesceFailures());
    }

    @Test
    public void emptyRateArrayIsRejected() {
        try {
            new RateStat("test.stat", "d", "g", new Rate[0]);
            fail("an empty rate array must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /**
     * A stat whose only rate throws still returns normally, so the caller's loop
     * over the rest of the table is never abandoned.
     */
    @Test
    public void coalesceStatsDoesNotPropagate() {
        ThrowingRate bad = new ThrowingRate(60000, new IllegalStateException("boom"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {bad});

        stat.coalesceStats();

        assertEquals(1, stat.getCoalesceFailures());
    }

    @Test
    public void mixedStatsAllGetTheirAttempt() {
        List<Rate> rates = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            rates.add(i % 2 == 0 ? new CountingRate(60000)
                                 : new ThrowingRate(60000, new IllegalStateException("x")));
        RateStat stat = new RateStat("test.stat", "d", "g", rates.toArray(new Rate[0]));

        stat.coalesceStats();

        // Five rates alternating healthy/faulty starting healthy: two throwers.
        assertEquals(2, stat.getCoalesceFailures());
        int coalesced = 0;
        for (Rate r : rates) {
            if (r instanceof CountingRate)
                coalesced += ((CountingRate) r).coalesces.get();
        }
        assertEquals("every healthy rate in a mixed stat must coalesce", 3, coalesced);
    }

    @Test
    public void failureIdentityIsStableAcrossSweeps() {
        ThrowingRate bad = new ThrowingRate(3600000, new IllegalStateException("boom"));
        RateStat stat = new RateStat("test.stat", "d", "g", new Rate[] {bad});

        stat.coalesceStats();
        String firstSeen = stat.getFirstCoalesceFailurePeriod();
        stat.coalesceStats();
        stat.coalesceStats();

        assertEquals("the reported period must not drift between sweeps",
                     firstSeen, stat.getFirstCoalesceFailurePeriod());
        assertEquals(3, stat.getCoalesceFailures());
    }

    @Test
    public void periodsAreReportedInMilliseconds() {
        List<Rate> rates = new ArrayList<>(Arrays.asList(
                new CountingRate(60000),
                new ThrowingRate(86400000L, new IllegalStateException("daily"))));
        RateStat stat = new RateStat("test.stat", "d", "g", rates.toArray(new Rate[0]));

        stat.coalesceStats();

        assertEquals("86400000", stat.getFirstCoalesceFailurePeriod());
    }
}