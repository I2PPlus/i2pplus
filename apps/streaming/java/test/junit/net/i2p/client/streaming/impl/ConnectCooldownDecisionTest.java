package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the per-destination connect cooldown decisions: how long a destination
 * is held out of connect once it has failed, how the strike count that drives
 * that window grows, and when a failure record is old enough to forget.
 *
 * <p>{@link ConnectionManager} already cooled a failed destination down for a
 * flat window. Measured on a live router, one peer's stream established and then
 * died on the 90s liveness backstop six times in thirteen minutes, each attempt
 * reconnecting ~16s after the last - a flat 5s window against a 90s failure cost
 * waits 6% of the time it is spending on a path that keeps failing. The window
 * now doubles per consecutive failure up to a ceiling, so a peer that lost one
 * connect is still retried promptly.
 *
 * @since 0.9.72+
 */
public class ConnectCooldownDecisionTest {

    /** Tuner-default base cooldown, the window a first failure arms. */
    private static final long BASE = 5 * 1000L;
    /** Tuner-default ceiling for the escalating window. */
    private static final long MAX = 5 * 60 * 1000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the configured base and
     * ceiling the pure decisions are called with.
     *
     * @since 0.9.72+
     */
    public ConnectCooldownDecisionTest() {}

    // ---- destCooldownMs ----

    /** The first failure keeps the base window - one lost connect is not a dead peer. */
    @Test
    public void testFirstFailureUsesBase() {
        assertEquals(BASE, ConnectionManager.destCooldownMs(1, BASE, MAX));
    }

    /** Each further failure doubles the window, up to the ceiling. */
    @Test
    public void testWindowDoublesPerFailure() {
        assertEquals(10 * 1000L, ConnectionManager.destCooldownMs(2, BASE, MAX));
        assertEquals(20 * 1000L, ConnectionManager.destCooldownMs(3, BASE, MAX));
        assertEquals(40 * 1000L, ConnectionManager.destCooldownMs(4, BASE, MAX));
        assertEquals(80 * 1000L, ConnectionManager.destCooldownMs(5, BASE, MAX));
        assertEquals(160 * 1000L, ConnectionManager.destCooldownMs(6, BASE, MAX));
    }

    /** The escalation stops at the ceiling and never overshoots or wraps past it. */
    @Test
    public void testCeilingCapsTheEscalation() {
        assertEquals(MAX, ConnectionManager.destCooldownMs(7, BASE, MAX));
        assertEquals(MAX, ConnectionManager.destCooldownMs(8, BASE, MAX));
        assertEquals(MAX, ConnectionManager.destCooldownMs(100, BASE, MAX));
        assertEquals(MAX, ConnectionManager.destCooldownMs(Integer.MAX_VALUE, BASE, MAX));
    }

    /** A ceiling below the base is not a way to shrink the window - the base wins. */
    @Test
    public void testCeilingBelowBaseUsesBase() {
        assertEquals(BASE, ConnectionManager.destCooldownMs(1, BASE, 1000L));
        assertEquals(BASE, ConnectionManager.destCooldownMs(9, BASE, 1000L));
        assertEquals(BASE, ConnectionManager.destCooldownMs(9, BASE, 0L));
    }

    /** A non-positive base disables the cooldown at any strike count. */
    @Test
    public void testNonPositiveBaseDisables() {
        assertEquals(0, ConnectionManager.destCooldownMs(1, 0, MAX));
        assertEquals(0, ConnectionManager.destCooldownMs(12, 0, MAX));
        assertEquals(0, ConnectionManager.destCooldownMs(12, -1, MAX));
    }

    /** No failures recorded is the base window, and a bogus count cannot crash or go negative. */
    @Test
    public void testZeroAndNegativeStrikes() {
        assertEquals(BASE, ConnectionManager.destCooldownMs(0, BASE, MAX));
        assertEquals(BASE, ConnectionManager.destCooldownMs(-3, BASE, MAX));
    }

    /**
     * Doubling a long window cannot overflow into a negative cooldown, which would
     * make the caller skip the wait for a peer that has failed 60+ times.
     */
    @Test
    public void testNoOverflowWithHugeCeiling() {
        long ms = ConnectionManager.destCooldownMs(64, 1, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, ms);
        ms = ConnectionManager.destCooldownMs(200, BASE, Long.MAX_VALUE);
        assertTrue("[" + ms + "]", ms > 0);
        assertEquals(Long.MAX_VALUE, ms);
    }

    // ---- recordFailure ----

    /** The first failure against a destination is one strike. */
    @Test
    public void testFirstFailureIsOneStrike() {
        long[] rec = ConnectionManager.recordFailure(null, 1000L, false);
        assertEquals(1000L, rec[0]);
        assertEquals(1, rec[1]);
        assertEquals(0, rec[2]);
    }

    /** A second failure keeps the new timestamp and grows the strike count. */
    @Test
    public void testStrikesGrow() {
        long[] first = ConnectionManager.recordFailure(null, 1000L, false);
        long[] second = ConnectionManager.recordFailure(first, 2500L, false);
        assertEquals(2500L, second[0]);
        assertEquals(2, second[1]);
        long[] third = ConnectionManager.recordFailure(second, 3000L, false);
        assertEquals(3, third[1]);
    }

    /**
     * The prior record is never written in place: a reader that grabbed it before
     * the update must still see a consistent record.
     */
    @Test
    public void testPriorRecordNotMutated() {
        long[] first = ConnectionManager.recordFailure(null, 1000L, false);
        ConnectionManager.recordFailure(first, 2500L, true);
        assertEquals(1000L, first[0]);
        assertEquals(1, first[1]);
        assertEquals(0, first[2]);
    }

    /** A corrupt or hand-seeded record with no strikes still escalates from one. */
    @Test
    public void testNonPositivePriorStrikesCountsAsOne() {
        assertEquals(2, ConnectionManager.recordFailure(new long[] {1000L, 0, 0}, 2000L, false)[1]);
        assertEquals(2, ConnectionManager.recordFailure(new long[] {1000L, -4, 1}, 2000L, true)[1]);
    }

    /** A path death is marked as one, so a later connect success cannot erase it. */
    @Test
    public void testPathDeathIsMarked() {
        assertEquals(1, ConnectionManager.recordFailure(null, 1000L, true)[2]);
        long[] prior = ConnectionManager.recordFailure(null, 1000L, true);
        assertEquals(1, ConnectionManager.recordFailure(prior, 2000L, true)[2]);
    }

    /** The mark follows the newest failure, not the oldest. */
    @Test
    public void testMarkFollowsNewestFailure() {
        long[] path = ConnectionManager.recordFailure(null, 1000L, true);
        assertEquals(0, ConnectionManager.recordFailure(path, 2000L, false)[2]);
    }

    // ---- forgetsOnConnectSuccess ----

    /** No record at all has nothing to forget. */
    @Test
    public void testNoRecordIsNotForgettable() {
        assertFalse(ConnectionManager.forgetsOnConnectSuccess(null));
    }

    /** A connect that failed is forgotten once a connect succeeds. */
    @Test
    public void testConnectFailureIsForgottenOnSuccess() {
        assertTrue(ConnectionManager.forgetsOnConnectSuccess(new long[] {1000L, 3, 0}));
    }

    /**
     * A stream that died after establishing is not forgotten: the connect before
     * the death succeeded too, so clearing on the next success puts the
     * destination back at the base window forever and the escalation never lands.
     */
    @Test
    public void testPathDeathSurvivesASuccess() {
        assertFalse(ConnectionManager.forgetsOnConnectSuccess(new long[] {1000L, 3, 1}));
    }

    /**
     * The measured cycle: establish, stall 90s, die, reconnect ~16s later, six
     * times in thirteen minutes. A success cannot forget a path death, so the
     * window escalates across the whole cycle instead of resetting to the base
     * on every reconnect.
     */
    @Test
    public void testMeasuredDeathCycleEscalates() {
        long now = 0;
        long[] rec = null;
        long[] windows = new long[6];
        for (int i = 0; i < 6; i++) {
            rec = ConnectionManager.recordFailure(rec, now, true);
            assertFalse(ConnectionManager.forgetsOnConnectSuccess(rec));
            windows[i] = ConnectionManager.destCooldownMs((int) rec[1], BASE, MAX);
            now += 106 * 1000L;
        }
        assertEquals(5 * 1000L, windows[0]);
        assertEquals(160 * 1000L, windows[5]);
    }

    // ---- failureRecordExpired ----

    /**
     * A record is kept for the whole ceiling, not the base window: pruning on the
     * base would drop a destination whose escalated cooldown has outgrown it while
     * that cooldown is still in force, letting the peer the escalation was for
     * connect immediately.
     */
    @Test
    public void testRecordLivesForTheFullCeiling() {
        assertFalse(ConnectionManager.failureRecordExpired(1000L, 1000L + MAX, MAX));
        assertTrue(ConnectionManager.failureRecordExpired(1000L, 1001L + MAX, MAX));
    }

    /** A fresh record is never expired, and a clock that went backwards keeps it. */
    @Test
    public void testFreshRecordKept() {
        assertFalse(ConnectionManager.failureRecordExpired(1000L, 1000L, MAX));
        assertFalse(ConnectionManager.failureRecordExpired(5000L, 1000L, MAX));
    }

    /** A zero ceiling prunes anything but a record from this instant. */
    @Test
    public void testZeroCeilingPrunesAtOnce() {
        assertFalse(ConnectionManager.failureRecordExpired(1000L, 1000L, 0L));
        assertTrue(ConnectionManager.failureRecordExpired(1000L, 1001L, 0L));
    }
}
