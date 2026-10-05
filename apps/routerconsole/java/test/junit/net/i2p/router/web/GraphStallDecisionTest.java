package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.web.GraphGenerator.CauseTally;
import net.i2p.router.web.GraphGenerator.StaleCause;
import net.i2p.stat.RateSummaryListener;
import org.junit.Test;

/**
 *  Tests for the graph write-stall decision logic: which listeners are judged stalled and
 *  why, which of them get rebuilt, and how the diagnosis is worded in the log.
 *
 *  <p>The production symptom this pins down was 25 of 25 listeners reported as "not written
 *  within 2x their rate period" with an age growing 90s per report and nothing else logged.
 *  That single number hid three unrelated faults, so the rules that separate them are the
 *  point of these tests. Everything under test is static and free of router context.
 */
public class GraphStallDecisionTest {

    /** Fixed clock so nothing depends on wall time. Graphing "began" at 1,000,000. */
    private static final long STARTED = 1_000_000L;
    /** "Now" well past any threshold these tests exercise. */
    private static final long NOW = STARTED + 3_600_000L;
    /** A typical 60s rate period. */
    private static final long PERIOD = 60_000L;

    /** A write that just landed, so only the elapsed time can make it stale. */
    private static long freshWrite() { return NOW - 1000L; }

    ///////////// classifyStaleness

    /** A listener writing on schedule is not reported. */
    @Test
    public void testFreshWriteIsHealthy() {
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, freshWrite(), NOW, STARTED, PERIOD));
    }

    /**
     *  The 2x boundary is exclusive: at exactly twice the period the next sample is still
     *  merely due, so nothing is reported. One millisecond later it is a genuine stall.
     */
    @Test
    public void testTwoPeriodBoundaryIsExclusive() {
        long atBoundary = NOW - 2 * PERIOD;
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, atBoundary, NOW, STARTED, PERIOD));
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, atBoundary - 1, NOW, STARTED, PERIOD));
    }

    /**
     *  Registered and previously written, but writes stopped: the ambiguous case, and the
     *  one that has to report the real write age rather than the instance age.
     */
    @Test
    public void testStoppedWritesAreDistinctFromNeverWritten() {
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, NOW - 5 * PERIOD, NOW, STARTED, PERIOD));
    }

    /**
     *  The lost-registration fault (A2): still attached with an open RRD, so it renders and
     *  is not detached, but the Rate points elsewhere and it can never be called again.
     *  Reported with no age threshold at all - the fault exists the moment ownership is lost.
     */
    @Test
    public void testUnregisteredIsReportedImmediatelyDespiteFreshWrite() {
        assertEquals(StaleCause.UNREGISTERED,
                     GraphGenerator.classifyStaleness(false, false, freshWrite(), NOW, STARTED, PERIOD));
    }

    /**
     *  An unregistered listener is reported as unregistered even when it has also never
     *  written: the registration is the actionable cause, and re-arming fixes both symptoms.
     */
    @Test
    public void testUnregisteredOutranksNeverWritten() {
        assertEquals(StaleCause.UNREGISTERED,
                     GraphGenerator.classifyStaleness(false, false, 0L, NOW, STARTED, PERIOD));
    }

    /**
     *  Never written since the listener attached is its own cause, and the age that matters is the
     *  instance age. Until 2x the period has elapsed since the listener attached there is nothing to
     *  judge, so a startup window is not mistaken for a stall.
     */
    @Test
    public void testNeverWrittenUsesInstanceAgeAndStartupGrace() {
        long insideGrace = STARTED + 2 * PERIOD;
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, 0L, insideGrace, STARTED, PERIOD));
        assertEquals(StaleCause.NEVER_WRITTEN,
                     GraphGenerator.classifyStaleness(false, true, 0L, insideGrace + 1, STARTED, PERIOD));
    }

    /** A detached listener is rebuilt on the same tick, so it is never counted as stalled. */
    @Test
    public void testDetachedIsNotReportedAsStalled() {
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(true, true, 0L, NOW, STARTED, PERIOD));
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(true, false, freshWrite(), NOW, STARTED, PERIOD));
    }

    /**
     *  Sub-minute periods are floored, so a fast rate that coalesces every 250ms is judged
     *  against 60s rather than 500ms and is not flagged from its first tick.
     */
    @Test
    public void testMinimumPeriodFloor() {
        assertEquals(60_000L, GraphGenerator.MIN_STALENESS_PERIOD_MS);
        long fast = 250L;
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, freshWrite(), NOW, STARTED, fast));
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, NOW - 2 * GraphGenerator.MIN_STALENESS_PERIOD_MS - 1,
                                                      NOW, STARTED, fast));
    }

    /** A longer period is used as-is, not floored down to {@link GraphGenerator#MIN_STALENESS_PERIOD_MS}. */
    @Test
    public void testLongerPeriodIsUsedUnchanged() {
        long fiveMin = 300_000L;
        // 10 minutes since the last write is only 2x a 5-minute period: healthy. Against the
        // 60s floor the same 10 minutes would be a 10x overrun and wrongly reported.
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, NOW - 2 * fiveMin, NOW, STARTED, fiveMin));
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, NOW - 2 * fiveMin - 1, NOW, STARTED, fiveMin));
    }

    ///////////// staleAge

    /** With a write on record the age is measured from that write. */
    @Test
    public void testStaleAgeMeasuredFromLastWrite() {
        long lastWrite = NOW - 900_000L;
        assertEquals(900_000L, GraphGenerator.staleAge(NOW, STARTED, lastWrite));
    }

    /** With no write on record the age is measured from graphing start, never negative. */
    @Test
    public void testStaleAgeFallsBackToInstanceAge() {
        assertEquals(900_000L, GraphGenerator.staleAge(STARTED + 900_000L, STARTED, 0L));
        // A clock that reads behind the recorded start must not produce a negative age
        assertEquals(0L, GraphGenerator.staleAge(STARTED - 5_000L, STARTED, 0L));
    }

    /** Each age is labelled with the event it is measured from. */
    @Test
    public void testStaleAgeOriginWording() {
        assertEquals("since last write", GraphGenerator.staleAgeOrigin(true));
        assertEquals("since the listener attached", GraphGenerator.staleAgeOrigin(false));
    }

    ///////////// refineForIdleRate

    /**
     *  A rate that never coalesced since attach had no sample to write, so nothing is lost.
     *
     *  <p>This is the split that stops the false alarm: a stat nothing in the router ever
     *  updates produced a permanent, unfixable ERROR claiming its graphs were stalled.
     */
    @Test
    public void testIdleRateIsNotAFault() {
        assertEquals(StaleCause.RATE_IDLE,
                     GraphGenerator.refineForIdleRate(StaleCause.NEVER_WRITTEN, 0L));
    }

    /** Once the rate has coalesced there was a sample to write, so the write path is at fault. */
    @Test
    public void testFedRateThatNeverWroteIsStillAFault() {
        assertEquals(StaleCause.NEVER_WRITTEN,
                     GraphGenerator.refineForIdleRate(StaleCause.NEVER_WRITTEN, 1L));
    }

    /** Refinement only ever touches a never-written listener. */
    @Test
    public void testRefinementLeavesOtherCausesAlone() {
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.refineForIdleRate(StaleCause.WRITES_STOPPED, 0L));
        assertEquals(StaleCause.UNREGISTERED,
                     GraphGenerator.refineForIdleRate(StaleCause.UNREGISTERED, 0L));
        assertEquals(StaleCause.COALESCE_STALLED,
                     GraphGenerator.refineForIdleRate(StaleCause.COALESCE_STALLED, 0L));
        assertEquals(StaleCause.OK, GraphGenerator.refineForIdleRate(StaleCause.OK, 0L));
    }

    /** An idle rate records nothing because nothing is happening, so it is not a fault. */
    @Test
    public void testIsFaultExcludesIdleRate() {
        assertFalse(StaleCause.RATE_IDLE.isFault());
        assertFalse(StaleCause.OK.isFault());
        assertTrue(StaleCause.NEVER_WRITTEN.isFault());
        assertTrue(StaleCause.WRITES_STOPPED.isFault());
        assertTrue(StaleCause.UNREGISTERED.isFault());
        assertTrue(StaleCause.COALESCE_STALLED.isFault());
    }

    ///////////// CauseTally names

    /**
     *  A count with no names leaves the operator to match every rate on the page against
     *  the stalled graphs by hand, which is the work the report exists to save.
     */
    @Test
    public void testNamedTallyListsTheOffendingStats() {
        CauseTally tally = new CauseTally(StaleCause.NEVER_WRITTEN);
        tally.record(120_000L, false, "bw.sendRate");
        tally.record(600_000L, false, "tunnel.lifetime");
        assertEquals(2, tally.count());
        assertEquals("bw.sendRate, tunnel.lifetime", tally.names());
        assertEquals("never_written=2 (oldest 600s since the listener attached):"
                     + " bw.sendRate, tunnel.lifetime", tally.describe());
    }

    /** Past a handful of names, the rest are summarised rather than left to guesswork. */
    @Test
    public void testNamedTallyTruncatesLongLists() {
        CauseTally tally = new CauseTally(StaleCause.WRITES_STOPPED);
        for (int i = 0; i < GraphGenerator.MAX_NAMED + 3; i++) {
            tally.record(1000L * (i + 1), true, "stat" + i);
        }
        assertEquals(GraphGenerator.MAX_NAMED + 3, tally.count());
        assertEquals("stat0, stat1, stat2, stat3, stat4 and 3 more", tally.names());
    }

    /** An unnamed tally keeps the original wording, so nothing is appended for its own sake. */
    @Test
    public void testUnnamedTallyIsUnchanged() {
        CauseTally tally = new CauseTally(StaleCause.NEVER_WRITTEN);
        tally.record(900_000L, true);
        assertEquals("", tally.names());
        assertEquals("never_written=1 (oldest 900s since last write)", tally.describe());
    }

    ///////////// ownsRegistration

    /** The live listener owns the registration. */
    @Test
    public void testOwnsRegistrationWhenSelfIsRegistered() {
        RateSummaryListener self = stubListener();
        assertTrue(GraphListener.ownsRegistration(self, self));
    }

    /**
     *  The A2 defect in one assertion: a teardown that is not the registered instance must
     *  not clear the registration, or the live listener is silenced with no error anywhere.
     */
    @Test
    public void testOwnsRegistrationIsIdentityNotEquality() {
        RateSummaryListener live = stubListener();
        RateSummaryListener other = stubListener();
        assertFalse(GraphListener.ownsRegistration(live, other));
        assertFalse(GraphListener.ownsRegistration(other, live));
    }

    /** Nobody registered is not ownership either. */
    @Test
    public void testOwnsRegistrationFalseWhenUnregistered() {
        RateSummaryListener self = stubListener();
        assertFalse(GraphListener.ownsRegistration(null, self));
    }

    ///////////// needsRevive

    /** A mapped, attached, registered listener is healthy and must not be rebuilt. */
    @Test
    public void testNeedsReviveFalseWhenHealthy() {
        assertFalse(GraphGenerator.needsRevive(true, false, true));
    }

    /** Every fault that silently stops recording asks for a rebuild. */
    @Test
    public void testNeedsReviveCoversAllThreeFaults() {
        // mapping lost its listener entirely
        assertTrue(GraphGenerator.needsRevive(false, false, false));
        // RRD closed after repeated write failures
        assertTrue(GraphGenerator.needsRevive(true, true, true));
        // attached but no longer registered on its Rate
        assertTrue(GraphGenerator.needsRevive(true, false, false));
        // detached and unregistered together
        assertTrue(GraphGenerator.needsRevive(true, true, false));
    }

    ///////////// formatStaleness

    /** The grep-able prefix and single-line shape survive the rewrite. */
    @Test
    public void testMessageKeepsPrefixAndUsesTheLogContinuationForm() {
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        neverWritten.record(120_000L, false);
        String msg = GraphGenerator.formatStaleness(25, neverWritten, unregistered, writesStopped);
        assertTrue("prefix must survive", msg.startsWith("RRD data stalled:"));
        assertTrue("must say how many are stalled", msg.contains("1/25 graph listeners"));
        // The per-cause tally is deliberately a second line, using the same
        // continuation convention the rest of the router's multi-line log entries
        // use ("* Gateway: ...", "* Peers: ..."), so it reads correctly in the
        // log file rather than being crammed onto the summary line.
        String[] lines = msg.split("\n");
        for (int i = 1; i < lines.length; i++) {
            assertTrue("continuation line must use the '* ' log prefix: " + lines[i],
                       lines[i].startsWith("* "));
        }
    }

    /** Counts are attributed per cause, and the total is their sum. */
    @Test
    public void testCountsAttributedPerCause() {
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        neverWritten.record(120_000L, false);
        neverWritten.record(240_000L, false);
        unregistered.record(300_000L, false);
        writesStopped.record(600_000L, true);
        writesStopped.record(90_000L, true);
        assertEquals(2, neverWritten.count());
        assertEquals(1, unregistered.count());
        assertEquals(2, writesStopped.count());
        String msg = GraphGenerator.formatStaleness(5, neverWritten, unregistered, writesStopped);
        assertTrue(msg, msg.contains("5/5 graph listeners"));
        assertTrue(msg, msg.contains("never_written=2"));
        assertTrue(msg, msg.contains("unregistered=1"));
        assertTrue(msg, msg.contains("writes_stopped=2"));
    }

    /**
     *  A listener that has never written is quoted with its instance age, so the report
     *  cannot be read as "the last write was 30 minutes ago" for a graph that never wrote
     *  at all.
     */
    @Test
    public void testNeverWrittenReportsInstanceAge() {
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        neverWritten.record(1_830_000L, false);
        String msg = GraphGenerator.formatStaleness(25, neverWritten, unregistered, writesStopped);
        assertTrue(msg, msg.contains("never_written=1 (oldest 1830s since the listener attached)"));
    }

    /** Writes that stopped are quoted with the real write age. */
    @Test
    public void testStoppedWritesReportWriteAge() {
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        writesStopped.record(900_000L, true);
        String msg = GraphGenerator.formatStaleness(25, neverWritten, unregistered, writesStopped);
        assertTrue(msg, msg.contains("writes_stopped=1 (oldest 900s since last write)"));
    }

    /** An empty cause reports its count only, with no misleading zero-second age. */
    @Test
    public void testEmptyCauseOmitsAge() {
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        String msg = GraphGenerator.formatStaleness(25, neverWritten, unregistered, writesStopped);
        assertTrue(msg, msg.contains("never_written=0"));
        assertFalse(msg, msg.contains("oldest 0s"));
    }

    /** A tally keeps the oldest age, not the most recent one recorded. */
    @Test
    public void testTallyKeepsOldestAge() {
        CauseTally tally = new CauseTally(StaleCause.WRITES_STOPPED);
        tally.record(600_000L, true);
        tally.record(90_000L, true);
        tally.record(300_000L, true);
        assertEquals("writes_stopped=3 (oldest 600s since last write)", tally.describe());
    }

    /**
     *  End to end over the decision path: a mixed listener set must be tallied into the
     *  three causes and named in the line, which is the whole point of the rewrite.
     */
    @Test
    public void testMixedListenerSetProducesNamedCauses() {
        Tallies tallies = new Tallies();
        // 4 healthy listeners, within one period of their last write
        for (int i = 0; i < 4; i++) {
            tallies.record(false, true, NOW - 1000L);
        }
        // 1 registered but nothing was ever written
        tallies.record(false, true, 0L);
        // 2 lost their registration, one of them also well past its last write
        tallies.record(false, false, NOW - 300_000L);
        tallies.record(false, false, NOW - 1000L);
        // 1 detached: rebuilt on the same tick, so it must not be counted
        tallies.record(true, false, NOW - 300_000L);
        // 2 registered with writes that stopped
        tallies.record(false, true, NOW - 900_000L);
        tallies.record(false, true, NOW - 300_000L);

        assertEquals(1, tallies.neverWritten.count());
        assertEquals(2, tallies.unregistered.count());
        assertEquals(2, tallies.writesStopped.count());
        String msg = GraphGenerator.formatStaleness(10, tallies.neverWritten, tallies.unregistered,
                                                    tallies.writesStopped);
        assertEquals("RRD data stalled: 5/10 graph listeners not writing within 2x their rate period"
                     + " \n* coalesce_stalled=0,"
                     + " never_written=1 (oldest 3600s since the listener attached),"
                     + " unregistered=2 (oldest 300s since last write),"
                     + " writes_stopped=2 (oldest 900s since last write)", msg);
    }

    /**
     *  The per-listener attribution the sync task performs, mirroring its loop over
     *  {@link GraphGenerator#classifyStaleness} so the tally wiring is covered too.
     */
    private static final class Tallies {
        final CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        final CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        final CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);

        void record(boolean detached, boolean registered, long lastUpdateSuccess) {
            StaleCause cause = GraphGenerator.classifyStaleness(detached, registered, lastUpdateSuccess,
                                                                 NOW, STARTED, PERIOD);
            if (cause == StaleCause.OK) {
                return;
            }
            CauseTally tally;
            switch (cause) {
                case NEVER_WRITTEN:
                    tally = neverWritten;
                    break;
                case UNREGISTERED:
                    tally = unregistered;
                    break;
                default:
                    tally = writesStopped;
                    break;
            }
            tally.record(GraphGenerator.staleAge(NOW, STARTED, lastUpdateSuccess), lastUpdateSuccess > 0);
        }
    }

    /** A summary listener stub; identity is all the ownership test cares about. */
    private static RateSummaryListener stubListener() {
        return new RateSummaryListener() {
            @Override
            public void add(double totalValue, long eventCount, double totalEventTime, long period) {
                // no-op
            }
        };
    }
}
