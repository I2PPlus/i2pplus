package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the signal layer behind the NetDB search-budget params: the failure
 * ratio of our own outgoing searches, and the per-peer reply budget implied by
 * a completed successful search.
 *
 * <p>The regression this pins: the search budgets ({@code netdb.searchLimit},
 * {@code netdb.maxSearchTime}, {@code netdb.singleSearchTime}) were keyed on
 * {@code transport.sendProcessingTime} — a local send-queue lifetime recorded
 * only for successful sends — and on {@code netDb.lookupsMatched} /
 * {@code netDb.lookupsMatchedLeaseSet}, which
 * {@link net.i2p.router.networkdb.HandleDatabaseLookupMessageJob} writes when
 * this router ANSWERS another router's lookup. On a non-floodfill those inbound
 * counters sit near zero, so "no matches" read as "every lookup failed" and each
 * branch that followed shrank the budget. A router with LeaseSet lookups timing
 * out therefore ended up with every search budget pinned at its floor, which is
 * where the live values were found (1000 ms per peer, 10 s total, 11 peers).
 *
 * <p>The replacement rule: a lookup failure must widen the budget the next
 * lookup gets, never narrow it, so the ratio is built from the per-search
 * outcome stats ({@code netDb.successTime} / {@code netDb.failedTime}) and a
 * window with too few completed searches yields no decision at all.
 *
 * @since 0.9.72+
 */
public class TunerSearchBudgetTest {

    @Test
    public void testRatioNeedsSamples() {
        // One failure on a quiet router is not a trend; NaN means "no decision",
        // which keeps the budgets where they are instead of walking them down.
        assertTrue(Double.isNaN(Tuner.searchFailureRatio(0, 1)));
        assertTrue(Double.isNaN(Tuner.searchFailureRatio(1, 1)));
        assertTrue(Double.isNaN(Tuner.searchFailureRatio(0, 0)));
        // Both sides absent is not "0% failing" — it is no signal.
        assertTrue(Double.isNaN(Tuner.searchFailureRatio(Double.NaN, Double.NaN)));
    }

    @Test
    public void testRatioAtSampleFloor() {
        assertEquals(0.0, Tuner.searchFailureRatio(Tuner.MIN_SEARCH_SAMPLES, 0), 0.0001);
        assertEquals(1.0, Tuner.searchFailureRatio(0, Tuner.MIN_SEARCH_SAMPLES), 0.0001);
        assertEquals(0.5, Tuner.searchFailureRatio(2, 2), 0.0001);
    }

    @Test
    public void testMissingSideReadsAsZeroEvents() {
        // A stat with no traffic in the window must not skew the ratio; it just
        // lowers the sample count.
        assertEquals(1.0, Tuner.searchFailureRatio(Double.NaN, 8), 0.0001);
        assertEquals(0.0, Tuner.searchFailureRatio(8, Double.NaN), 0.0001);
    }

    @Test
    public void testAllFailuresIsTheMaximumRatio() {
        // This is the case the old logic inverted: every search failing must read
        // as failing, and must never be mistaken for "nothing to do".
        double ratio = Tuner.searchFailureRatio(0, 40);
        assertEquals(1.0, ratio, 0.0001);
        assertTrue(ratio > Tuner.SEARCH_FAIL_RATIO);
    }

    @Test
    public void testPerPeerBudgetDividesByRounds() {
        // 6s total across 3 peers queried (2 extra rounds) is 2s per round, and
        // the doubled budget leaves headroom for one slow round.
        assertEquals(4000.0, Tuner.perPeerSearchTimeMs(6000, 2), 0.0001);
        // No retries recorded: the whole search was one round.
        assertEquals(12000.0, Tuner.perPeerSearchTimeMs(6000, 0), 0.0001);
        // Unknown rounds must not be guessed as many rounds.
        assertEquals(12000.0, Tuner.perPeerSearchTimeMs(6000, Double.NaN), 0.0001);
    }

    @Test
    public void testPerPeerBudgetNeedsSignal() {
        assertTrue(Double.isNaN(Tuner.perPeerSearchTimeMs(Double.NaN, 2)));
        assertTrue(Double.isNaN(Tuner.perPeerSearchTimeMs(0, 2)));
        assertTrue(Double.isNaN(Tuner.perPeerSearchTimeMs(-1, 2)));
    }

    @Test
    public void testOutcomeRatioHonoursSampleFloor() {
        assertTrue(Double.isNaN(Tuner.outcomeRatio(1, 1, 4)));
        assertEquals(0.5, Tuner.outcomeRatio(2, 2, 4), 0.0001);
        assertEquals(0.5, Tuner.outcomeRatio(20, 20, 4), 0.0001);
    }
}
