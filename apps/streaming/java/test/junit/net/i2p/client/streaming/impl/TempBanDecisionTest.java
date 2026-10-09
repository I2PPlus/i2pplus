package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Tests for the pure decision logic behind the streaming autoban:
 * the refusal-threshold trigger, ban-extension, and ban-expiry rules.
 * These mirror the hot-path decisions in ConnectionManager so the
 * flood-loop behavior can be pinned without a running router.
 *
 * @since 0.9.71+
 */
public class TempBanDecisionTest {

    /**
     * Strikes-to-ban used throughout these tests: the production default, so a
     * change to it has to be made deliberately here too. Must be at least 2 —
     * a single burst window must never ban on its own.
     */
    private static final int NEEDED = 3;

    /* refusalThresholdMet */

    @Test
    public void testRefusalBelowThresholdNotBanned() {
        assertFalse("refusals equal to threshold must not ban",
                    ConnectionManager.refusalThresholdMet(50, 50));
        assertFalse("refusals below threshold must not ban",
                    ConnectionManager.refusalThresholdMet(10, 50));
        assertFalse("zero refusals must not ban",
                    ConnectionManager.refusalThresholdMet(0, 50));
    }

    @Test
    public void testRefusalAboveThresholdBanned() {
        assertTrue("refusals above threshold must ban",
                   ConnectionManager.refusalThresholdMet(51, 50));
        // one over is enough; 52/50 is also over (">{", not ">=", is the boundary)
        assertTrue(ConnectionManager.refusalThresholdMet(52, 50));
        assertTrue(ConnectionManager.refusalThresholdMet(500, 50));
    }

    @Test
    public void testThresholdDisabledNeverBans() {
        assertFalse("threshold 0 (disabled) must never ban",
                    ConnectionManager.refusalThresholdMet(1000, 0));
        assertFalse("negative threshold must never ban",
                    ConnectionManager.refusalThresholdMet(1000, -1));
    }

    /* banActive (expiry) */

    @Test
    public void testBanActiveBeforeExpiry() {
        long now = 1000;
        assertTrue("ban until future is active",
                   ConnectionManager.banActive(now + 1, now));
        assertTrue("ban until far future is active",
                   ConnectionManager.banActive(now + 86400000L, now));
    }

    @Test
    public void testBanExpired() {
        long now = 1000;
        assertFalse("ban exactly at now is expired",
                    ConnectionManager.banActive(now, now));
        assertFalse("ban in the past is expired",
                    ConnectionManager.banActive(now - 1, now));
        assertFalse("null ban is not active",
                    ConnectionManager.banActive(null, now));
    }

    /* banIsLonger (extension only, never shrink) */

    @Test
    public void testBanNotExtendedByShorter() {
        Long existing = Long.valueOf(2000);
        assertFalse("shorter candidate must not shrink the ban",
                    ConnectionManager.banIsLonger(existing, Long.valueOf(1500)));
        assertFalse("equal candidate must not change the ban",
                    ConnectionManager.banIsLonger(existing, Long.valueOf(2000)));
    }

    @Test
    public void testBanExtendedByLonger() {
        assertTrue("longer candidate replaces the ban",
                   ConnectionManager.banIsLonger(Long.valueOf(2000), Long.valueOf(3000)));
    }

    @Test
    public void testBanIsLongerNullGuards() {
        assertFalse(ConnectionManager.banIsLonger(null, Long.valueOf(3000)));
        assertFalse(ConnectionManager.banIsLonger(Long.valueOf(2000), null));
        assertFalse(ConnectionManager.banIsLonger(null, null));
    }

    /* synBurstTripped (sub-second SYN burst gate) */

    @Test
    public void testBurstTrippedWithinWindow() {
        long now = 1000000;
        // count 11 SYNs in a 500ms window with burst threshold 10 -> tripped
        assertTrue(ConnectionManager.synBurstTripped(now - 100, 11, now, 500, 10));
    }

    @Test
    public void testBurstNotTrippedAtOrBelowThreshold() {
        long now = 1000000;
        // exactly at threshold: not an ''exceeds'' trip
        assertFalse(ConnectionManager.synBurstTripped(now - 100, 10, now, 500, 10));
        assertFalse(ConnectionManager.synBurstTripped(now - 100, 1, now, 500, 10));
        // null/absent window start -> never trips
        assertFalse(ConnectionManager.synBurstTripped(null, 99, now, 500, 10));
    }

    @Test
    public void testBurstAgedOutOfWindow() {
        long now = 1000000;
        // window start older than windowMs -> burst ignored (count doesn't apply)
        assertFalse(ConnectionManager.synBurstTripped(now - 501, 99, now, 500, 10));
        // exactly at boundary is outside (>= windowMs)
        assertFalse(ConnectionManager.synBurstTripped(now - 500, 99, now, 500, 10));
    }

    @Test
    public void testBurstGateDisabled() {
        long now = 1000000;
        // windowMs or burst <= 0 disables the gate entirely
        assertFalse(ConnectionManager.synBurstTripped(Long.valueOf(now - 100), 99, now, 0, 10));
        assertFalse(ConnectionManager.synBurstTripped(Long.valueOf(now - 100), 99, now, 500, 0));
    }

    /* synBurstStrikeAction (multi-strike gate: distinct burst windows only) */

    @Test
    public void testFirstTripRecordsStrike() {
        long now = 1000000;
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(null, now - 100, now,
                                                            ConnectionManager.STRIKE_WINDOW_MS, NEEDED));
    }

    @Test
    public void testSameWindowNeverBans() {
        long w1 = 999900;
        long now = 1000000;
        // every repeat trip inside the window that already struck is ignored,
        // however far over threshold the burst goes: a single unlucky page-load
        // burst must never cost a 5-minute ban, however many SYN trips it makes
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(w1, w1), w1, w1 + 1,
                                                            ConnectionManager.STRIKE_WINDOW_MS, NEEDED));
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(w1, w1), w1, now,
                                                            ConnectionManager.STRIKE_WINDOW_MS, NEEDED));
        // window identity wins over age even for a long-lived same-window trip
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(w1, w1), w1, w1 + 30000,
                                                            ConnectionManager.STRIKE_WINDOW_MS, NEEDED));
    }

    /**
     * Only the required number of distinct windows bans, so a merely busy peer
     * cannot lose connectivity. The Nth window is the ban; the ones before it
     * each record a strike.
     */
    @Test
    public void testOnlyRequiredStrikesBan() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        // strike 1 -> record, not a ban
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(null, now - 1000, now, w, NEEDED));
        // strike 2 of 3 -> still just records
        assertEquals("second of three distinct windows must not ban",
                     ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(
                             new ConnectionManager.SynStrike(now - 1000, now - 1000, 1),
                             now - 500, now, w, NEEDED));
        // strike 3 of 3 -> ban
        assertEquals("third distinct window is the ban",
                     ConnectionManager.SynBurstAction.BAN,
                     ConnectionManager.synBurstStrikeAction(
                             new ConnectionManager.SynStrike(now - 500, now - 500, 2),
                             now - 100, now, w, NEEDED));
    }

    /**
     * The ban boundary is inclusive of the strike window: a window starting
     * just inside it still counts toward the required total.
     */
    @Test
    public void testBanJustInsideStrikeWindow() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        assertEquals(ConnectionManager.SynBurstAction.BAN,
                     ConnectionManager.synBurstStrikeAction(
                             new ConnectionManager.SynStrike(now - (w - 1), now - (w - 1), NEEDED - 1),
                             now - (w - 1) + 500, now, w, NEEDED));
    }

    /** A configured count below 2 is raised, so one window can never ban. */
    @Test
    public void testRequiredStrikesClampedToTwo() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        for (int bad : new int[] { -1, 0, 1 }) {
            assertEquals("count " + bad + " must be raised to 2, not ban on one strike",
                         ConnectionManager.SynBurstAction.BAN,
                         ConnectionManager.synBurstStrikeAction(
                                 new ConnectionManager.SynStrike(now - 1000, now - 1000),
                                 now - 500, now, w, bad));
        }
    }

    @Test
    public void testStrikeAgedOutForgives() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        // at or past the window boundary the old strike is forgiven: a fresh
        // strike is recorded instead of banning
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(now - w, now - w),
                                                            now - w + 500, now, w, NEEDED));
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(now - (w + 1), now - (w + 1)),
                                                            now - (w + 1) + 500, now, w, NEEDED));
        // clock skew (prior strike in the future) must not ban
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(now + 5000, now + 5000),
                                                            now - 500, now, w, NEEDED));
    }

    @Test
    public void testStrikeWindowDisabledNeverActs() {
        long now = 1000000;
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.synBurstStrikeAction(new ConnectionManager.SynStrike(now - 1, now - 1),
                                                            now - 500, now, 0, NEEDED));
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.synBurstStrikeAction(null, now - 500, now, -1, NEEDED));
    }

    /**
     * Forgiveness ages from the strike TIME, not the burst window that caused
     * it: a window is only milliseconds long, so keying age to the window start
     * (the pre-0.9.71+ value) forgave a strike almost immediately and the
     * multi-strike autoban never fired.
     */
    @Test
    public void testStrikeAgesFromStrikeTimeNotWindowStart() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        // windowStart far older than the strike window, but the strike was
        // recorded moments ago: still a live strike -> BAN on a new window
        assertEquals("age must come from strikeTime, not windowStart",
                     ConnectionManager.SynBurstAction.BAN,
                     ConnectionManager.synBurstStrikeAction(
                             new ConnectionManager.SynStrike(now - (w + 10000), now - 1000, NEEDED - 1),
                             now - 500, now, w, NEEDED));
        // windowStart recent but the strike itself recorded before the window:
        // forgiven -> RECORD
        assertEquals("a recent windowStart must not keep an old strike alive",
                     ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(
                             new ConnectionManager.SynStrike(now - 100, now - w - 500),
                             now - 500, now, w, NEEDED));
    }

    /**
     * Policy pin for the first abusive window: it trips the gate (so the burst
     * is counted) but NEVER bans — only {@link #NEEDED} distinct windows inside
     * the strike window do. The grace holds however far over threshold the first
     * window goes.
     */
    @Test
    public void testFirstWindowTripsButNeverBans() {
        long now = 1000000;
        long w = ConnectionManager.STRIKE_WINDOW_MS;
        // far over threshold (10000 SYNs vs burst 10) inside the window
        assertTrue(ConnectionManager.synBurstTripped(Long.valueOf(now - 100), 10000, now, 500, 10));
        assertEquals("first window records, never bans",
                     ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.synBurstStrikeAction(null, now - 100, now, w, NEEDED));
    }

    /* applySynBurstStrike (atomic strike recording) */

    @Test
    public void testApplyRecordsThenIgnoresSameWindow() {
        ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        Hash h = new Hash(new byte[32]);
        long w1 = 1000000;
        long sw = ConnectionManager.STRIKE_WINDOW_MS;
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1, w1 + 100, sw, NEEDED));
        assertEquals(w1, strikes.get(h).windowStart);
        assertEquals(w1 + 100, strikes.get(h).strikeTime);
        assertEquals(ConnectionManager.SynBurstAction.IGNORE,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1, w1 + 200, sw, NEEDED));
        assertEquals(w1, strikes.get(h).windowStart);
        assertEquals(w1 + 100, strikes.get(h).strikeTime);
    }

    @Test
    public void testApplyBansOnRequiredWindowKeepsOriginalStrike() {
        ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        Hash h = new Hash(new byte[32]);
        long w1 = 1000000;
        long sw = ConnectionManager.STRIKE_WINDOW_MS;
        // first window records strike 1
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1, w1 + 100, sw, NEEDED));
        assertEquals(1, strikes.get(h).count);
        // walk up to the ban one window at a time: every window before the last
        // must only record, and the recorded count must advance with it
        for (int i = 2; i < NEEDED; i++) {
            assertEquals("window " + i + " of " + NEEDED + " must only record",
                         ConnectionManager.SynBurstAction.RECORD,
                         ConnectionManager.applySynBurstStrike(strikes, h, w1 + (i - 1) * 500,
                                                              w1 + (i - 1) * 500 + 100, sw, NEEDED));
            assertEquals("strike count must advance", i, strikes.get(h).count);
        }
        assertEquals(ConnectionManager.SynBurstAction.BAN,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1 + 500 * NEEDED,
                                                          w1 + 500 * NEEDED + 100, sw, NEEDED));
        // A BAN does not mutate the entry: it is left exactly as the last
        // recording trip found it, and the ban itself supersedes any further
        // strikes, so there is nothing left to record.
        assertEquals(w1 + 500, strikes.get(h).windowStart);
        assertEquals(w1 + 600, strikes.get(h).strikeTime);
        assertEquals(NEEDED - 1, strikes.get(h).count);
    }

    /**
     * Forgiving a stale strike must also reset the count, so a dest cannot
     * accumulate one strike per window forever and ban on a total that spans
     * hours of unrelated traffic.
     */
    @Test
    public void testForgivenessResetsStrikeCount() {
        ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        Hash h = new Hash(new byte[32]);
        long w1 = 1000000;
        long sw = ConnectionManager.STRIKE_WINDOW_MS;
        // one short of the ban
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1, w1 + 100, sw, NEEDED));
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1 + 500, w1 + 600, sw, NEEDED));
        assertEquals(NEEDED - 1, strikes.get(h).count);
        // next window is past the strike window: forgiven, and the count restarts
        long w2 = w1 + sw + 1000;
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w2, w2 + 100, sw, NEEDED));
        assertEquals("forgiveness must reset the count", 1, strikes.get(h).count);
        // and a following window inside the new window is not a ban
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w2 + 500, w2 + 600, sw, NEEDED));
    }

    @Test
    public void testApplyForgivesStaleStrike() {
        ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        Hash h = new Hash(new byte[32]);
        long w1 = 1000000;
        long sw = ConnectionManager.STRIKE_WINDOW_MS;
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w1, w1 + 100, sw, NEEDED));
        long w2 = w1 + sw + 1000;
        assertEquals(ConnectionManager.SynBurstAction.RECORD,
                     ConnectionManager.applySynBurstStrike(strikes, h, w2, w2 + 100, sw, NEEDED));
        assertEquals(w2, strikes.get(h).windowStart);
        assertEquals(w2 + 100, strikes.get(h).strikeTime);
    }

    @Test
    public void testApplySameWindowConcurrentTripsStrikeOnce() throws InterruptedException {
        final ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        final Hash h = new Hash(new byte[32]);
        final long w1 = 1000000;
        final long now = w1 + 100;
        final long sw = ConnectionManager.STRIKE_WINDOW_MS;
        final int threads = 8;
        final int trips = 250;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger records = new AtomicInteger();
        final AtomicInteger bans = new AtomicInteger();
        final AtomicInteger ignores = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    return;
                }
                for (int j = 0; j < trips; j++) {
                    ConnectionManager.SynBurstAction a =
                            ConnectionManager.applySynBurstStrike(strikes, h, w1, now, sw, NEEDED);
                    if (a == ConnectionManager.SynBurstAction.RECORD)
                        records.incrementAndGet();
                    else if (a == ConnectionManager.SynBurstAction.BAN)
                        bans.incrementAndGet();
                    else
                        ignores.incrementAndGet();
                }
            });
            ts[i].start();
        }
        start.countDown();
        for (int i = 0; i < threads; i++)
            ts[i].join();
        assertEquals("exactly one strike recorded for the window", 1, records.get());
        assertEquals("a single burst must never ban", 0, bans.get());
        assertEquals(threads * trips - 1, ignores.get());
        assertEquals(w1, strikes.get(h).windowStart);
    }

    @Test
    public void testApplyDistinctWindowsConcurrentBans() throws InterruptedException {
        final ConcurrentHashMap<Hash, ConnectionManager.SynStrike> strikes =
                new ConcurrentHashMap<Hash, ConnectionManager.SynStrike>();
        final Hash h = new Hash(new byte[32]);
        final long now = 1000000;
        final long sw = ConnectionManager.STRIKE_WINDOW_MS;
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger records = new AtomicInteger();
        final AtomicInteger bans = new AtomicInteger();
        final AtomicInteger ignores = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final long windowStart = now - 1000 + i * 10; // distinct windows, all inside the strike window
            ts[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    return;
                }
                ConnectionManager.SynBurstAction a =
                        ConnectionManager.applySynBurstStrike(strikes, h, windowStart, now, sw, NEEDED);
                if (a == ConnectionManager.SynBurstAction.RECORD)
                    records.incrementAndGet();
                else if (a == ConnectionManager.SynBurstAction.BAN)
                    bans.incrementAndGet();
                else
                    ignores.incrementAndGet();
            });
            ts[i].start();
        }
        start.countDown();
        for (int i = 0; i < threads; i++)
            ts[i].join();
        // Racing distinct bursts can neither lose a strike nor double-record one.
        // NEEDED-1 of the 8 windows record (the count climbs 1, 2, ...), the one
        // that reaches the count bans, and because a BAN leaves the entry as it
        // found it, every later window keeps resolving to BAN too.
        assertEquals("each strike before the ban is recorded exactly once",
                     NEEDED - 1, records.get());
        assertEquals("every remaining distinct window bans",
                     threads - (NEEDED - 1), bans.get());
        assertEquals(0, ignores.get());
        assertEquals("a ban leaves the strike count at its pre-ban value",
                     NEEDED - 1, strikes.get(h).count);
    }

    /* tooManyStreamsForDest (per-dest stream budget) */

    @Test
    public void testPerDestOverBudgetAtCeiling() {
        // a dest reaches its boundary exactly at max -> no more streams
        assertTrue(ConnectionManager.tooManyStreamsForDest(512, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(513, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(1000, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(1, 1));
    }

    @Test
    public void testPerDestUnderBudget() {
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 512));
        assertFalse(ConnectionManager.tooManyStreamsForDest(511, 512));
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 0));
    }

    @Test
    public void testPerDestBudgetDisabled() {
        // a non-positive ceiling must never refuse, regardless of stream count
        assertFalse(ConnectionManager.tooManyStreamsForDest(100000, 0));
        assertFalse(ConnectionManager.tooManyStreamsForDest(100000, -1));
    }

    @Test
    public void testPerDestIsolation() {
        // two dests are independent: one at the ceiling does not affect a fresh one
        assertTrue(ConnectionManager.tooManyStreamsForDest(50, 50));
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 50));
    }
}
