package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.data.Hash;
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
 * <p>
 * The other half is what a caller does once that window is long. Measured in a
 * later 133-minute window: 539 failed connects to 24 destinations, 371 of them
 * within a minute of the previous failure to the same destination. Waiting out
 * the cooldown parked every thread dialing the peer and then let all of them
 * send at once, so the escalation bought a burst of attempts instead of one
 * attempt per window - each paying three SYNs, a fresh outbound tunnel and the
 * full connect timeout. These are the decisions that refuse the extras.
 *
 * @since 0.9.72+
 */
public class ConnectCooldownDecisionTest {

    /** Tuner-default base cooldown, the window a first failure arms. */
    private static final long BASE = 5 * 1000L;
    /** Tuner-default ceiling for the escalating window. */
    private static final long MAX = 5 * 60 * 1000L;
    /** Tuner-default cooldown length past which a connect is refused, not waited. */
    private static final long REFUSE_AFTER = 30 * 1000L;

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

    /** Spacing disabled: every failure counts as its own event. */
    private static final long ANY_SPACING = 0;

    /** The first failure against a destination is one strike. */
    @Test
    public void testFirstFailureIsOneStrike() {
        long[] rec = ConnectionManager.recordFailure(null, 1000L, false, ANY_SPACING);
        assertEquals(1000L, rec[0]);
        assertEquals(1, rec[1]);
        assertEquals(0, rec[2]);
    }

    /** A second failure keeps the new timestamp and grows the strike count. */
    @Test
    public void testStrikesGrow() {
        long[] first = ConnectionManager.recordFailure(null, 1000L, false, ANY_SPACING);
        long[] second = ConnectionManager.recordFailure(first, 2500L, false, ANY_SPACING);
        assertEquals(2500L, second[0]);
        assertEquals(2, second[1]);
        long[] third = ConnectionManager.recordFailure(second, 3000L, false, ANY_SPACING);
        assertEquals(3, third[1]);
    }

    /**
     * The prior record is never written in place: a reader that grabbed it before
     * the update must still see a consistent record.
     */
    @Test
    public void testPriorRecordNotMutated() {
        long[] first = ConnectionManager.recordFailure(null, 1000L, false, ANY_SPACING);
        ConnectionManager.recordFailure(first, 2500L, true, ANY_SPACING);
        assertEquals(1000L, first[0]);
        assertEquals(1, first[1]);
        assertEquals(0, first[2]);
    }

    /** A corrupt or hand-seeded record with no strikes still escalates from one. */
    @Test
    public void testNonPositivePriorStrikesCountsAsOne() {
        assertEquals(2, ConnectionManager.recordFailure(new long[] {1000L, 0, 0}, 2000L, false,
                                                        ANY_SPACING)[1]);
        assertEquals(2, ConnectionManager.recordFailure(new long[] {1000L, -4, 1}, 2000L, true,
                                                        ANY_SPACING)[1]);
    }

    /** A path death is marked as one, so a later connect success cannot erase it. */
    @Test
    public void testPathDeathIsMarked() {
        assertEquals(1, ConnectionManager.recordFailure(null, 1000L, true, ANY_SPACING)[2]);
        long[] prior = ConnectionManager.recordFailure(null, 1000L, true, ANY_SPACING);
        assertEquals(1, ConnectionManager.recordFailure(prior, 2000L, true, ANY_SPACING)[2]);
    }

    // ---- one burst is one failure ----

    /**
     * Failures closer together than the spacing are one event, not several
     * strikes, so the doubling escalation cannot be driven by a single local
     * stall.
     *
     * <p>The live shape this pins: eight asset connects a browser had opened to
     * one locally hosted destination all died inside the same millisecond when
     * the router's own send path stalled, and each was counted as a strike -
     * seven strikes, a 300s cooldown, and 616 refused connects to a destination
     * that was demonstrably alive within the next minute.
     */
    @Test
    public void testABurstIsOneStrikeNotSeveral() {
        long[] rec = ConnectionManager.recordFailure(null, 1000L, false, BASE);
        // the seven siblings of the measured burst, all within 1 ms
        for (int i = 0; i < 7; i++) {
            rec = ConnectionManager.recordFailure(rec, 1001L + i, false, BASE);
        }
        assertEquals(1, rec[1]);
        // one strike is a 5s window, below the 30s refuse threshold, so the
        // caller waits it out instead of being refused outright
        long window = ConnectionManager.destCooldownMs((int) rec[1], BASE, MAX);
        assertEquals(BASE, window);
        assertFalse(ConnectionManager.refuseCooldown(window, REFUSE_AFTER));
    }

    /**
     * The timestamp still follows every failure, coalesced or not, so the
     * cooldown window is always measured from the most recent one and a
     * destination that keeps failing cannot be released early.
     */
    @Test
    public void testCoalescedFailureStillMovesTheWindow() {
        long[] rec = ConnectionManager.recordFailure(null, 1000L, false, BASE);
        rec = ConnectionManager.recordFailure(rec, 1200L, false, BASE);
        assertEquals(1200L, rec[0]);
        assertEquals(1, rec[1]);
    }

    /**
     * Once the spacing passes, the next failure is a separate event and
     * escalates as before, so a destination that genuinely fails over and over
     * is still pushed back further each time.
     */
    @Test
    public void testSpacedFailuresStillEscalate() {
        long[] rec = ConnectionManager.recordFailure(null, 0L, false, BASE);
        for (int i = 1; i <= 6; i++) {
            rec = ConnectionManager.recordFailure(rec, i * (BASE + 1), false, BASE);
        }
        assertEquals(7, rec[1]);
        assertEquals(MAX, ConnectionManager.destCooldownMs((int) rec[1], BASE, MAX));
    }

    /**
     * A failure exactly at the spacing boundary is a separate event; one
     * millisecond short of it is part of the same burst; and a disabled spacing
     * counts every failure on its own.
     */
    @Test
    public void testSpacingBoundaryCountsSeparately() {
        long[] rec = ConnectionManager.recordFailure(null, 0L, false, BASE);
        assertEquals(2, ConnectionManager.recordFailure(rec, BASE, false, BASE)[1]);
        assertEquals(1, ConnectionManager.recordFailure(rec, BASE - 1, false, BASE)[1]);
        assertEquals(2, ConnectionManager.recordFailure(rec, 1L, false, ANY_SPACING)[1]);
    }

    /** The mark follows the newest failure, not the oldest. */
    @Test
    public void testMarkFollowsNewestFailure() {
        long[] path = ConnectionManager.recordFailure(null, 1000L, true, ANY_SPACING);
        assertEquals(0, ConnectionManager.recordFailure(path, 2000L, false, ANY_SPACING)[2]);
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
            rec = ConnectionManager.recordFailure(rec, now, true, BASE);
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

    // ---- refuseCooldown ----

    /**
     * A short cooldown is still sat out: the application expects connect() to
     * block, and a peer that lost one connect should be retried as soon as its
     * own window ends.
     */
    @Test
    public void testShortCooldownIsWaitedOut() {
        assertFalse(ConnectionManager.refuseCooldown(0, REFUSE_AFTER));
        assertFalse(ConnectionManager.refuseCooldown(BASE, REFUSE_AFTER));
        assertFalse(ConnectionManager.refuseCooldown(20 * 1000L, REFUSE_AFTER));
    }

    /**
     * The threshold itself is waited: exactly thirty seconds left is the last
     * window a caller still sits through, and one ms past it is refused.
     */
    @Test
    public void testThresholdIsWaited() {
        assertFalse(ConnectionManager.refuseCooldown(REFUSE_AFTER, REFUSE_AFTER));
        assertTrue(ConnectionManager.refuseCooldown(REFUSE_AFTER + 1, REFUSE_AFTER));
        assertTrue(ConnectionManager.refuseCooldown(MAX, REFUSE_AFTER));
    }

    /**
     * A non-positive threshold turns the refusal off entirely and restores the
     * old behaviour of waiting out even a full ceiling-length window - the kill
     * switch for an operator who wants every connect to block.
     */
    @Test
    public void testNonPositiveThresholdAlwaysWaits() {
        assertFalse(ConnectionManager.refuseCooldown(MAX, 0));
        assertFalse(ConnectionManager.refuseCooldown(MAX, -1));
        assertFalse(ConnectionManager.refuseCooldown(Long.MAX_VALUE, 0));
    }

    /**
     * The two decisions composed, which is what the change actually turns on:
     * with the Tuner defaults, the first three failures are still waited out and
     * the fourth - a 40s window - is refused. That is where the measured burst
     * of concurrent dialers stops costing SYNs and outbound tunnels.
     */
    @Test
    public void testEscalationReachesRefusalAtTheFourthFailure() {
        for (int strikes = 1; strikes <= 3; strikes++) {
            long window = ConnectionManager.destCooldownMs(strikes, BASE, MAX);
            assertFalse("strike " + strikes + " window " + window,
                        ConnectionManager.refuseCooldown(window, REFUSE_AFTER));
        }
        for (int strikes = 4; strikes <= 9; strikes++) {
            long window = ConnectionManager.destCooldownMs(strikes, BASE, MAX);
            assertTrue("strike " + strikes + " window " + window,
                       ConnectionManager.refuseCooldown(window, REFUSE_AFTER));
        }
    }

    /**
     * The measured shape: one destination at its fourth consecutive failure, a
     * 40s window, with threads dialing it throughout. Pre-change every one of
     * them waited the window out and then sent - three SYNs and a fresh outbound
     * tunnel each, which is how 371 of 539 failures landed within a minute of
     * the previous failure to the same destination. Now a dialer that arrives
     * while more than the threshold is left is refused at once; one that arrives
     * near the end just waits out what remains, which costs nothing.
     */
    @Test
    public void testDialersArrivingEarlyInALongWindowAreRefused() {
        long window = ConnectionManager.destCooldownMs(4, BASE, MAX);
        assertEquals(40 * 1000L, window);
        int refused = 0;
        int waited = 0;
        for (long arrived = 1; arrived <= 40; arrived++) {
            long elapsed = arrived * 1000L;
            if (!ConnectionManager.cooldownRunning(elapsed, window)) {continue;}
            if (ConnectionManager.refuseCooldown(window - elapsed, REFUSE_AFTER)) {refused++;}
            else {waited++;}
        }
        assertEquals("refused with more than the threshold left", 9, refused);
        assertEquals("waited out 30s or less", 30, waited);
    }

    /**
     * The attempt that arrives when the window has lapsed runs: the record is
     * dropped and one real probe goes out per window, which is the whole point
     * of refusing the ones inside it.
     */
    @Test
    public void testAttemptAtTheWindowBoundaryRuns() {
        long window = ConnectionManager.destCooldownMs(4, BASE, MAX);
        assertTrue(ConnectionManager.cooldownRunning(window - 1, window));
        assertFalse(ConnectionManager.cooldownRunning(window, window));
        assertFalse(ConnectionManager.cooldownRunning(window + 1, window));
    }

    /**
     * A clock that moved backwards leaves a negative elapsed: the record counts
     * as spent rather than armed, so an adjustment cannot hold a reachable peer
     * out of connect for a full window.
     */
    @Test
    public void testClockBackwardsDoesNotArmTheCooldown() {
        assertFalse(ConnectionManager.cooldownRunning(-1, MAX));
        assertFalse(ConnectionManager.cooldownRunning(-MAX, MAX));
        assertTrue(ConnectionManager.cooldownRunning(0, MAX));
        assertFalse(ConnectionManager.cooldownRunning(0, 0));
    }

    /**
     * A refused connect is not a peer failure: the attempt never reached the
     * network, so it must not be counted as another strike. The error string it
     * carries is how the caller tells the two apart, and it must not collide
     * with anything a peer caused.
     */
    @Test
    public void testRefusalIsNotAPeerFailure() {
        assertTrue(ConnectionManager.isBackoffRefusal(ConnectionManager.ERR_CONNECT_BACKOFF));
        assertFalse(ConnectionManager.isBackoffRefusal(null));
        assertFalse(ConnectionManager.isBackoffRefusal(""));
        assertFalse(ConnectionManager.isBackoffRefusal(Connection.ERR_SYN_NOT_ACKNOWLEDGED));
        assertFalse(ConnectionManager.isBackoffRefusal(Connection.ERR_CONNECTION_REFUSED));
        assertFalse(ConnectionManager.isBackoffRefusal(Connection.ERR_CONNECTION_TIMED_OUT));
    }

    // ---- cooldownGate ----

    /**
     * Consults the gate with the class's configured base, ceiling and refuse
     * threshold, so each case below reads as the record and the moment the
     * attempt arrives, not as five arguments.
     *
     * @param record the destination's failure record, or null when it has never failed
     * @param now the clock reading the attempt arrives at, in ms since the epoch
     * @return the outcome to assert on, never null
     */
    private static ConnectionManager.CooldownGate gate(long[] record, long now) {
        return ConnectionManager.cooldownGate(record, now, BASE, MAX, REFUSE_AFTER);
    }

    /**
     * A destination with no failure record, and a cooldown switched off, runs the
     * connect at once: nothing refused, nothing held, nothing left to report.
     */
    @Test
    public void testGateRunsWhenNothingIsArmed() {
        long now = 1_700_000_000_000L;
        ConnectionManager.CooldownGate none = gate(null, now);
        assertFalse(none.refused);
        assertEquals(0, none.waitMs);
        assertEquals(0, none.strikes);
        assertEquals(0, none.remainingMs);

        // A base of 0 switches the cooldown off even against an escalated record.
        long[] escalated = new long[] {now - 1000, 6, 1};
        ConnectionManager.CooldownGate off =
            ConnectionManager.cooldownGate(escalated, now, 0, MAX, REFUSE_AFTER);
        assertFalse(off.refused);
        assertEquals(0, off.waitMs);
    }

    /**
     * The first failure holds the caller for the rest of the base window and does
     * not refuse it - one lost connect is a retry, not a dead destination.
     */
    @Test
    public void testGateHoldsTheFirstFailure() {
        long now = 1_700_000_000_000L;
        ConnectionManager.CooldownGate g = gate(new long[] {now - 1000, 1, 0}, now);
        assertFalse(g.refused);
        assertEquals(BASE - 1000, g.waitMs);
        assertEquals(1, g.strikes);
        assertEquals(BASE - 1000, g.remainingMs);
    }

    /**
     * The refusals a live router actually wrote, reproduced from the record that
     * armed them: "Cooldown left: 39s * Consecutive failures: 4" and
     * "Cooldown left: 148s * Consecutive failures: 6". Both must come back as a
     * refusal carrying the same window and the same strike count, because those
     * two values are all the log line has to report.
     */
    @Test
    public void testGateRefusesTheMeasuredWindows() {
        long now = 1_700_000_000_000L;
        // 4 strikes arm 40s; the attempt arrived 1s in, so 39s were left.
        ConnectionManager.CooldownGate four = gate(new long[] {now - 1000, 4, 1}, now);
        assertTrue(four.refused);
        assertEquals(39 * 1000L, four.remainingMs);
        assertEquals(4, four.strikes);
        assertEquals(0, four.waitMs);

        // 6 strikes arm 160s; the attempt arrived 12s in, so 148s were left.
        ConnectionManager.CooldownGate six = gate(new long[] {now - 12 * 1000, 6, 1}, now);
        assertTrue(six.refused);
        assertEquals(148 * 1000L, six.remainingMs);
        assertEquals(6, six.strikes);
    }

    /**
     * Once the window has run out the connect goes through and the record is
     * retired - the gate must not hold a destination past the window its failures
     * bought, and it must not refuse an attempt that is already allowed to run.
     */
    @Test
    public void testGateRunsOnceTheWindowHasRun() {
        long now = 1_700_000_000_000L;
        // 4 strikes arm 40s; this attempt is 60s after the failure that armed it.
        ConnectionManager.CooldownGate g = gate(new long[] {now - 60 * 1000, 4, 1}, now);
        assertFalse(g.refused);
        assertEquals(0, g.waitMs);
        assertEquals(0, g.remainingMs);
        assertEquals(4, g.strikes);
    }

    /**
     * A clock that stepped backwards leaves a negative elapsed: the record counts
     * as spent, so the connect runs instead of sitting out a window the clock
     * invented.
     */
    @Test
    public void testGateRunsOnAClockStepBackwards() {
        long now = 1_700_000_000_000L;
        ConnectionManager.CooldownGate g = gate(new long[] {now + 30 * 1000, 6, 1}, now);
        assertFalse(g.refused);
        assertEquals(0, g.waitMs);
        assertEquals(0, g.remainingMs);
    }

    /**
     * Switching the refuse threshold off restores the old behaviour in full: even
     * a window escalated to the ceiling is waited out rather than refused.
     */
    @Test
    public void testGateWaitsWhenRefusalIsDisabled() {
        long now = 1_700_000_000_000L;
        long[] record = new long[] {now - 1000, 20, 1};
        ConnectionManager.CooldownGate g =
            ConnectionManager.cooldownGate(record, now, BASE, MAX, 0);
        assertFalse(g.refused);
        assertEquals(MAX - 1000, g.waitMs);
        assertEquals(MAX - 1000, g.remainingMs);
    }

    /**
     * The whole cycle end to end: failures arriving a few seconds apart, each one
     * recorded through recordFailure() and each later attempt consulted through
     * the gate, escalate until an attempt is refused instead of held. This is the
     * sequence the counting defect hid - the refusals were happening and the stat
     * that reports them stayed empty.
     */
    @Test
    public void testSustainedFailuresEscalateToARefusal() {
        long t = 1_700_000_000_000L;
        long[] record = null;
        // One failure: the next attempt is held for what is left of the base window.
        record = ConnectionManager.recordFailure(record, t, false, BASE);
        assertEquals(1, record[1]);
        assertEquals(BASE - 1000, gate(record, t + 1000).waitMs);

        // Failures spaced past the base window are separate events, so each one
        // buys the next doubling: 10s, then 20s, then 40s.
        record = ConnectionManager.recordFailure(record, t + 8 * 1000, false, BASE);
        record = ConnectionManager.recordFailure(record, t + 20 * 1000, false, BASE);
        assertEquals(3, record[1]);
        assertFalse(gate(record, t + 21 * 1000).refused);

        record = ConnectionManager.recordFailure(record, t + 42 * 1000, false, BASE);
        assertEquals(4, record[1]);
        ConnectionManager.CooldownGate g = gate(record, t + 43 * 1000);
        assertTrue(g.refused);
        assertEquals(39 * 1000L, g.remainingMs);
    }

    /**
     * The refusal line names the destination, how much of its window is left,
     * and how many failures put it there - and the duration comes from the house
     * formatter, so a 40s window reads as 40s and not as 40000ms.
     */
    @Test
    public void testRefusalLineNamesTheWindowAndTheStrikes() {
        String line = ConnectionManager.backoffRefusal(hash(0x41), 40 * 1000L, 4);
        assertTrue("destination bracketed: " + line,
                   line.contains("[" + hash(0x41).toShortBase64() + "]"));
        assertTrue("outcome after the arrow: " + line,
                   line.contains(" -> Still cooling down"));
        assertTrue("window carries a unit: " + line,
                   line.contains(" * Cooldown left: 40s"));
        assertTrue("strikes named: " + line,
                   line.contains(" * Consecutive failures: 4"));
        assertTrue("no raw milliseconds: " + line, !line.contains("40000"));
        assertTrue("no key=value: " + line, !line.contains("="));
    }

    /**
     * A ceiling-length window renders in minutes, the same way every other
     * duration in the log does.
     */
    @Test
    public void testRefusalLineFormatsALongWindow() {
        String line = ConnectionManager.backoffRefusal(hash(0x42), MAX, 7);
        assertTrue("minutes, not seconds: " + line,
                   line.contains(" * Cooldown left: 5m"));
    }

    /**
     * The refusal line is one line an operator can read at a glance, so it stays
     * inside the house length limit for an emitted log line.
     */
    @Test
    public void testRefusalLineFitsOneLogLine() {
        String line = ConnectionManager.backoffRefusal(hash(0x43), MAX, 99);
        assertTrue("[" + line.length() + " chars] " + line, line.length() <= 100);
    }

    /**
     * A 32-byte hash whose first byte is given, the rest zero, for the
     * destination form the refusal line prints.
     *
     * @param first the first byte of the hash
     * @return a hash whose short form is fixed by that byte
     */
    private static Hash hash(int first) {
        byte[] data = new byte[32];
        data[0] = (byte) first;
        return new Hash(data);
    }
}
