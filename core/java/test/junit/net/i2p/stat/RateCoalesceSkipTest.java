package net.i2p.stat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the coalesce skip counter, {@link Rate#getCoalesceSkips()}.
 *
 * <p>The skip path in {@link Rate#coalesce()} is otherwise completely silent: no
 * log line, no exception, no counter. A rate stuck skipping stops feeding its
 * summary listener and therefore its RRD database, while every other health check
 * still reports the listener as healthy. This counter is the only way to tell that
 * apart from a rate that is merely visited slightly early.
 *
 * <p>{@link Rate} reads the OS clock rather than the {@code Clock} stat, so these
 * tests need no router context. Periods are chosen so the slack boundary is
 * reachable without a minute-long sleep: the skip threshold is
 * {@code period - 2000}, so a 2100ms period makes the threshold 100ms.
 *
 * @since 0.9.71+
 */
public class RateCoalesceSkipTest {

    /** A period short enough that {@code period - SLACK} is negative, so every
     *  coalesce is due immediately and none should ever be skipped. */
    private static final long ALWAYS_DUE_PERIOD = 1;

    /** Just above the 2000ms slack, so the threshold is 100ms and both the skip
     *  and the coalesce branches are reachable within a test's patience. */
    private static final long NEAR_SLACK_PERIOD = 2100;

    @Test
    public void newRateHasNoSkips() {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        assertEquals(0, rate.getCoalesceSkips());
    }

    @Test
    public void coalesceBeforePeriodIsDueIsSkipped() {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.coalesce();
        assertEquals(1, rate.getCoalesceSkips());
    }

    @Test
    public void repeatedEarlyCoalescesAreEachCounted() {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.coalesce();
        rate.coalesce();
        rate.coalesce();
        assertEquals(3, rate.getCoalesceSkips());
    }

    @Test
    public void coalesceAfterPeriodIsDueDoesNotIncrement() throws Exception {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.coalesce();
        assertEquals(1, rate.getCoalesceSkips());

        Thread.sleep(150);
        rate.coalesce();
        assertEquals("a coalesce that actually coalesced must not be counted as a skip",
                     1, rate.getCoalesceSkips());
    }

    @Test
    public void alwaysDuePeriodNeverSkips() {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        rate.coalesce();
        rate.coalesce();
        rate.coalesce();
        assertEquals(0, rate.getCoalesceSkips());
    }

    @Test
    public void skipDoesNotDisturbCounters() {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.addData(10, 5);
        rate.addData(20, 5);
        assertEquals(30.0f, rate.getCurrentTotalValue(), 0.001f);

        rate.coalesce();
        assertEquals(1, rate.getCoalesceSkips());
        assertEquals("a skipped coalesce must not consume the current period's data",
                     30.0f, rate.getCurrentTotalValue(), 0.001f);
        assertEquals(0, rate.getLastTotalValue(), 0.001f);
    }

    /**
     * The extreme totals are computed during coalesce from the current period, so
     * before any successful coalesce they are still zero. A skipped coalesce must
     * leave them untouched at zero and must not consume the current period that
     * the next successful coalesce will read.
     */
    @Test
    public void skipDoesNotConsumeTheDataTheNextCoalesceNeeds() throws Exception {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        rate.addData(100, 1);
        rate.coalesce();
        assertEquals(1, rate.getCoalesceSkips());
        assertEquals("a skipped coalesce must not compute extreme totals early",
                     0.0f, rate.getExtremeTotalValue(), 0.001f);

        Thread.sleep(150);
        rate.coalesce();
        assertEquals(100.0f, rate.getExtremeTotalValue(), 0.001f);
        assertEquals(100.0f, rate.getLastTotalValue(), 0.001f);
    }

    @Test
    public void lifetimeTotalsSurviveSkippedCoalesces() {
        Rate rate = new Rate(NEAR_SLACK_PERIOD);
        // addData(value, eventDuration): one event worth 7 lasting 3ms.
        rate.addData(7, 3);
        rate.coalesce();
        assertEquals(1, rate.getCoalesceSkips());
        assertEquals(1L, rate.getLifetimeEventCount());
        assertEquals(7.0f, rate.getLifetimeTotalValue(), 0.001f);
    }

    @Test
    public void invalidPeriodIsRejected() {
        try {
            new Rate(0);
            fail("period must be rejected as non-positive");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
