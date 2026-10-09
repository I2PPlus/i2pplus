package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.web.GraphGenerator.DriftCause;
import net.i2p.router.web.GraphGenerator.HealAction;
import net.i2p.router.web.GraphGenerator.ReportThrottle;
import org.junit.Test;

/**
 * Tests for the graph heal ladder and the watchdog report that drives it.
 *
 * <p>The incident these pin down froze 25 of 25 graph listeners at the same instant
 * with no warning of any kind, and the console could not say which of three
 * mechanisms did it: the coalesce path stopped, the delivery consumer wedged, or the
 * early return started swallowing every call. They look identical from the listener
 * side, so the ladder's cost ordering and the mechanism classifier are both pure
 * functions that have to be pinned here, away from a router, an RRD file and a clock.
 *
 * @since 0.9.71+
 */
public class GraphHealLadderDecisionTest {

    /** Report throttle constants mirroring the generator's. */
    private static final long REPEAT_MS = 120_000L;

    ///////////// chooseHeal

    /**
     * A listener that is mapped, attached, registered and writable with nothing
     * missing needs no action at all. Every other rung is more expensive and none of
     * them is warranted.
     */
    @Test
    public void testHealthyListenerNeedsNothing() {
        assertEquals(HealAction.NONE, GraphGenerator.chooseHeal(true, false, true, true, false));
    }

    /**
     * The cost ordering, cheapest rung last in the list but tried first: a dead
     * listener is rebuilt, a dead handle is merely reopened, and lost steps that the
     * rate still remembers are backfilled rather than rebuilt over.
     */
    @Test
    public void testCheapestApplicableRungWins() {
        // dead handle only
        assertEquals(HealAction.REOPEN, GraphGenerator.chooseHeal(true, false, true, false, false));
        // lost steps only
        assertEquals(HealAction.BACKFILL, GraphGenerator.chooseHeal(true, false, true, true, true));
        // both a dead handle and lost steps: reopening is cheaper and loses nothing,
        // the steps are backfillable again as soon as there is a handle to write them to
        assertEquals(HealAction.REOPEN, GraphGenerator.chooseHeal(true, false, true, false, true));
    }

    /**
     * A listener the map has lost, one whose RRD is closed, and one the Rate no longer
     * points at are all beyond repair in place: only a fresh listener from the same
     * file recovers them, and it preserves the recorded history.
     */
    @Test
    public void testUnusableStatesAllRebuild() {
        // map lost the listener entirely
        assertEquals(HealAction.REBUILD, GraphGenerator.chooseHeal(false, false, false, false, false));
        assertEquals(HealAction.REBUILD, GraphGenerator.chooseHeal(false, true, true, false, false));
        // RRD closed
        assertEquals(HealAction.REBUILD, GraphGenerator.chooseHeal(true, true, true, false, false));
        // attached but orphaned by a newer listener
        assertEquals(HealAction.REBUILD, GraphGenerator.chooseHeal(true, false, false, true, false));
    }

    /**
     * Spec-churn guard. A changed {@code stat.summaries} string re-reads every rate
     * each sync tick, and it must not be able to close and reopen a healthy RRD: a
     * mapped, attached, registered listener can only ever be NONE or BACKFILL, however
     * often the spec string is re-parsed.
     */
    @Test
    public void testSpecChurnCannotRebuildHealthyListener() {
        for (boolean drift : new boolean[] {false, true}) {
            HealAction action = GraphGenerator.chooseHeal(true, false, true, true, drift);
            assertTrue("spec churn must not rebuild a healthy listener, got " + action,
                       action == HealAction.NONE || action == HealAction.BACKFILL);
        }
    }

    /**
     * {@link HealAction#REARM} is in the ladder's shape but is deliberately never
     * returned: a lost registration is repaired by REBUILD, because re-pointing the
     * Rate at a listener another instance may have claimed would start two listeners
     * fighting over one registration. Pinning the absence keeps it from creeping in.
     */
    @Test
    public void testRearmIsNeverChosen() {
        boolean[] flags = {false, true};
        for (boolean mapped : flags) {
            for (boolean detached : flags) {
                for (boolean registered : flags) {
                    for (boolean writable : flags) {
                        for (boolean backfillable : flags) {
                            assertFalse("REARM must never be chosen",
                                        GraphGenerator.chooseHeal(mapped, detached, registered,
                                                                  writable, backfillable)
                                        == HealAction.REARM);
                        }
                    }
                }
            }
        }
    }

    /**
     * {@link GraphGenerator#needsRevive} stays the coarser test it already was, and
     * agrees with the ladder's first rung on every state where a listener exists at
     * all: anything it would rebuild is also something the ladder would rebuild.
     */
    @Test
    public void testNeedsReviveAgreesWithTheLadderFirstRung() {
        assertFalse(GraphGenerator.needsRevive(true, false, true));
        assertEquals(HealAction.NONE, GraphGenerator.chooseHeal(true, false, true, true, false));
        assertTrue(GraphGenerator.needsRevive(true, false, false));
        assertEquals(HealAction.REBUILD, GraphGenerator.chooseHeal(true, false, false, true, false));
    }

    ///////////// backfillWindow

    /**
     * The request is bounded by the drift, so a listener one step behind does not
     * copy a whole ring of samples every tick to find one of them.
     */
    @Test
    public void testBackfillWindowIsBoundedByDrift() {
        assertEquals(1, GraphGenerator.backfillWindow(1));
        assertEquals(2, GraphGenerator.backfillWindow(2));
        assertEquals(8, GraphGenerator.backfillWindow(8));
    }

    /**
     * A listener thousands of steps behind does not ask for a list thousands long.
     * The excess is exactly the part that is permanently gone, so asking for it is
     * pure waste.
     */
    @Test
    public void testBackfillWindowIsCapped() {
        assertEquals(GraphGenerator.MAX_BACKFILL_STEPS, GraphGenerator.backfillWindow(9));
        assertEquals(GraphGenerator.MAX_BACKFILL_STEPS, GraphGenerator.backfillWindow(100_000L));
        assertEquals(8, GraphGenerator.MAX_BACKFILL_STEPS);
    }

    /** Zero drift still yields a valid request rather than an empty window. */
    @Test
    public void testBackfillWindowIsNeverBelowOne() {
        assertEquals(1, GraphGenerator.backfillWindow(0));
        assertEquals(1, GraphGenerator.backfillWindow(-5));
    }

    ///////////// classifyDrift

    /**
     * Samples queuing with the oldest one older than a watchdog tick means the
     * consumer stopped draining. This is the one mechanism that the coalesce counters
     * look completely normal for, which is why it has to be named from the queue's
     * own state rather than inferred.
     */
    @Test
    public void testBackedUpQueueNamesTheConsumer() {
        assertEquals(DriftCause.DELIVERY_BACKED_UP,
                     GraphGenerator.classifyDrift(4, 45_000L, 0, true));
    }

    /**
     * The queue boundary is exclusive at one watchdog period: anything younger than
     * that is ordinary in-flight delivery, since the watchdog reads the counters at an
     * arbitrary point in the period.
     */
    @Test
    public void testQueueLagBoundaryIsExclusive() {
        assertEquals(DriftCause.WRITE_PATH,
                     GraphGenerator.classifyDrift(1, 10_000L, 0, true));
        assertEquals(DriftCause.DELIVERY_BACKED_UP,
                     GraphGenerator.classifyDrift(1, 10_001L, 0, true));
        // an empty queue cannot be backed up however old the last sample was
        assertEquals(DriftCause.WRITE_PATH,
                     GraphGenerator.classifyDrift(0, 600_000L, 0, true));
    }

    /**
     * An overrun means samples were evicted outright, which is the one delivery
     * fault that is unambiguously loss, so it outranks the queue-depth reading.
     */
    @Test
    public void testOverrunOutranksQueueDepth() {
        assertEquals(DriftCause.DELIVERY_OVERRUN,
                     GraphGenerator.classifyDrift(4, 45_000L, 17, true));
    }

    /**
     * No rate coalesced at all: the loss cannot be in delivery or in the write path,
     * because nothing was produced to deliver. This is the mechanism the stalled-graph
     * report could never distinguish from the other two.
     */
    @Test
    public void testStalledCoalesceIsNamed() {
        assertEquals(DriftCause.COALESCING_STOPPED,
                     GraphGenerator.classifyDrift(0, 0, 0, false));
    }

    /**
     * Coalesces are happening, the queue is draining, and steps are still missing: the
     * write path. Named explicitly rather than left to default, because it is the one
     * case where the six counters all look healthy and only the ledger knows.
     */
    @Test
    public void testEverythingHealthyMeansTheWritePath() {
        assertEquals(DriftCause.WRITE_PATH,
                     GraphGenerator.classifyDrift(0, 0, 0, true));
    }

    /** Each mechanism gets its own wording, so the line cannot read as a generic "lost steps". */
    @Test
    public void testEveryMechanismIsNamedDistinctly() {
        String previous = null;
        for (DriftCause cause : DriftCause.values()) {
            String desc = cause.describe();
            assertTrue("mechanism must name itself: " + cause, desc != null && desc.length() > 8);
            assertFalse("a specific mechanism must not reuse the write-path wording: " + cause,
                        cause != DriftCause.WRITE_PATH && desc.equals(DriftCause.WRITE_PATH.describe()));
            assertFalse("mechanism wording must be unique: " + cause, desc.equals(previous));
            previous = desc;
        }
    }

    ///////////// formatLedgerDrift

    /**
     * The whole point of the line: all six StatManager counters are present with
     * greppable names. Without them the next occurrence is another open question,
     * because the console cannot tell a wedged consumer from a rate that stopped
     * coalescing from the coalesce early return.
     */
    @Test
    public void testLineCarriesEveryDiagnosticCounter() {
        String msg = driftLine(7, 12_000L);
        assertTrue(msg, msg.startsWith("RRD ledger drift:"));
        assertTrue(msg, msg.contains("overruns=3"));
        assertTrue(msg, msg.contains("superseded=41"));
        assertTrue(msg, msg.contains("pending=7"));
        assertTrue(msg, msg.contains("oldest_pending_ms=12000"));
        assertTrue(msg, msg.contains("backlog_collapses=5"));
        assertTrue(msg, msg.contains("coalesce_skips=180"));
    }

    /** The ledger figures are all in the line too, so the loss is quantified. */
    @Test
    public void testLineCarriesTheLedgerFigures() {
        String msg = driftLine(7, 12_000L);
        assertTrue(msg, msg.contains("3/25 listeners"));
        assertTrue(msg, msg.contains("max 4"));
        assertTrue(msg, msg.contains("permanent 2"));
        assertTrue(msg, msg.contains("backfilled 1"));
        assertTrue(msg, msg.contains("rebuilt=0"));
        assertTrue(msg, msg.contains("reopened=1"));
        assertTrue(msg, msg.contains("coalesced=1800"));
        assertTrue(msg, msg.contains("mechanism=" + DriftCause.DELIVERY_BACKED_UP.describe()));
    }

    /** One line only: a log entry that wraps is a log entry nobody greps. */
    @Test
    public void testLineStaysOnOneLine() {
        assertFalse(driftLine(7, 12_000L).contains("\n"));
    }

    private static String driftLine(int pending, long oldestPendingMs) {
        return GraphGenerator.formatLedgerDrift(25, 3, 4, 2, 1, 0, 1, 1800,
                                                DriftCause.DELIVERY_BACKED_UP,
                                                3, 41, pending, oldestPendingMs, 5, 180);
    }

    ///////////// ReportThrottle

    /**
     * The first occurrence is never throttled. This is what makes the promise the
     * watchdog exists to keep - the next stall is named within one period - true even
     * though the same report is produced by both the watchdog and the sync task.
     */
    @Test
    public void testFirstReportIsNeverThrottled() {
        ReportThrottle throttle = new ReportThrottle();
        assertTrue(throttle.allow(0, 5, REPEAT_MS));
    }

    /** An unchanged report repeats on the timer rather than every ten seconds. */
    @Test
    public void testUnchangedReportRepeatsOnTheTimer() {
        ReportThrottle throttle = new ReportThrottle();
        assertTrue(throttle.allow(1000, 5, REPEAT_MS));
        assertFalse(throttle.allow(2000, 5, REPEAT_MS));
        assertFalse(throttle.allow(REPEAT_MS + 999, 5, REPEAT_MS));
        assertTrue(throttle.allow(REPEAT_MS + 1000, 5, REPEAT_MS));
    }

    /**
     * A changed report is never delayed, which is the case that matters: a growing
     * backlog or a rising permanent-loss count has to reach the log while it is
     * happening, not up to two minutes after.
     */
    @Test
    public void testChangedReportIsNeverDelayed() {
        ReportThrottle throttle = new ReportThrottle();
        assertTrue(throttle.allow(1000, 5, REPEAT_MS));
        assertTrue(throttle.allow(2000, 6, REPEAT_MS));
        assertFalse(throttle.allow(3000, 6, REPEAT_MS));
        assertTrue(throttle.allow(4000, 7, REPEAT_MS));
    }

    /** Two reports are independent, so a stall and a drift can each be logged once. */
    @Test
    public void testThrottlesAreIndependent() {
        ReportThrottle stall = new ReportThrottle();
        ReportThrottle drift = new ReportThrottle();
        assertTrue(stall.allow(1000, 1, REPEAT_MS));
        assertTrue(drift.allow(1000, 1, REPEAT_MS));
        assertFalse(stall.allow(1001, 1, REPEAT_MS));
        assertFalse(drift.allow(1001, 1, REPEAT_MS));
    }
}
