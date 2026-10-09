package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Accounting for a keepalive cycle.
 *
 * <p>Added because the top-tier candidate pool was observed shrinking from roughly 395 peers
 * per cycle to roughly 203, while the pre-existing log line reported only what was
 * kept alive and never what was dropped on the way. A tally that accounts for every candidate
 * peer is what turns "the pool is eroding" into "the pool is eroding because of X".
 *
 * <p>The original field names called the candidate count "delivered", which is the error
 * this class now pins shut: the count is fixed before the cycle sends anything, so it
 * measures supply. Read as delivery it turned a shrinking surplus above the 200-peer
 * action budget into an apparent halving of successful keepalives.
 *
 * @since 0.9.71+
 */
public class KeepAliveTallyTest {

    /**
     * Every peer the selector offered for keepalive must land in exactly one bucket. A peer
     * that falls through every branch unaccounted would let a real leak hide behind a
     * plausible-looking log line.
     *
     * <p>The fields are named for what they measure: {@code candidates} is fixed before the
     * cycle sends anything, so it is supply, and {@code actedOn()} is delivery. The previous
     * single "delivered" field sat on the candidate count and read as a fall in successful
     * keepalives when only the pool above budget had shrunk.
     */
    private static TunnelPeerSelector.KeepAliveTally full(int candidates) {
        TunnelPeerSelector.KeepAliveTally t = new TunnelPeerSelector.KeepAliveTally();
        t.budget = 400;
        t.candidates = candidates;
        return t;
    }

    @Test
    public void everyCandidateIsAccountedFor() {
        TunnelPeerSelector.KeepAliveTally t = full(210);
        t.keepalived = 20;
        t.preConnected = 30;
        t.skipCooldown = 10;
        t.skipStale = 5;
        t.skipRecent = 100;
        t.skipNoRouterInfo = 4;
        t.skipNoAddress = 6;
        t.skipNoTransport = 2;
        t.unprocessed = 33;
        assertEquals("acted + skipped + unprocessed must equal candidates",
                     t.candidates, t.keepalived + t.preConnected + t.skipCooldown + t.skipStale
                                  + t.skipRecent + t.skipNoRouterInfo + t.skipNoAddress
                                  + t.skipNoTransport + t.unprocessed);
        assertEquals(0, t.unaccounted());
    }

    @Test
    public void unaccountedCountsOnlyTheMissingPeers() {
        TunnelPeerSelector.KeepAliveTally t = full(10);
        t.keepalived = 3;
        t.skipStale = 2;
        assertEquals("five peers were never given a disposition", 5, t.unaccounted());
    }

    @Test
    public void anIdleCycleAccountsForNothing() {
        TunnelPeerSelector.KeepAliveTally t = new TunnelPeerSelector.KeepAliveTally();
        t.budget = 400;
        t.candidates = 0;
        assertEquals(0, t.unaccounted());
    }

    /**
     * The erosion case this was built to expose: the pool cannot fill what it asks for.
     * The line must name supply and delivery separately, since conflating them is what
     * let the collapse of the surplus above budget be read as a fall in keepalives.
     */
    @Test
    public void summarySeparatesCandidatesFromActedOn() {
        TunnelPeerSelector.KeepAliveTally t = full(210);
        t.keepalived = 20;
        t.preConnected = 30;
        String line = t.describe(true);
        assertTrue(line, line.contains("50 acted of 210 candidates from a 400 budget"));
        assertTrue(line, line.contains("aggressive"));
        // The old wording claimed candidates were delivered; that is the misreading this guards.
        assertFalse(line, line.contains("delivered"));
    }

    /**
     * Delivery is the figure that answers "did we reach the peers", and it is counted
     * after the cycle runs, not before.
     */
    @Test
    public void actedOnIsDeliveryNotSupply() {
        TunnelPeerSelector.KeepAliveTally t = full(400);
        assertEquals("nothing acted yet", 0, t.actedOn());
        t.keepalived = 7;
        assertEquals(7, t.actedOn());
        t.preConnected = 13;
        assertEquals(20, t.actedOn());
    }

    @Test
    public void summaryNamesEverySkipReason() {
        TunnelPeerSelector.KeepAliveTally t = full(100);
        t.skipCooldown = 1;
        t.skipStale = 2;
        t.skipRecent = 3;
        t.skipNoRouterInfo = 4;
        t.skipNoAddress = 5;
        t.skipNoTransport = 6;
        t.unprocessed = 7;
        String line = t.describe(false);
        assertTrue(line, line.contains("normal"));
        assertTrue(line, line.contains("cooldown=1"));
        assertTrue(line, line.contains("stale=2"));
        assertTrue(line, line.contains("recent=3"));
        assertTrue(line, line.contains("noRouterInfo=4"));
        assertTrue(line, line.contains("noAddress=5"));
        assertTrue(line, line.contains("noTransport=6"));
        assertTrue(line, line.contains("unprocessed=7"));
    }

    /**
     * A zero-filled tally still has to say something useful, because the cycle that matters
     * most is the one where nothing was selectable.
     */
    @Test
    public void anEmptyCycleStillReports() {
        TunnelPeerSelector.KeepAliveTally t = full(0);
        String line = t.describe(false);
        assertTrue(line, line.contains("0 acted of 0 candidates from a 400 budget"));
        assertTrue(line, line.contains("noAddress=0"));
    }

    @Test
    public void unprocessedIsReportedSeparatelyFromSkips() {
        // A peer the budget never reached was not rejected for any fault of its own,
        // which is a different problem from a peer that was rejected.
        TunnelPeerSelector.KeepAliveTally t = full(400);
        t.keepalived = 200;
        t.unprocessed = 200;
        String line = t.describe(false);
        assertTrue(line, line.contains("unprocessed=200"));
        assertEquals(0, t.unaccounted());
    }
}
