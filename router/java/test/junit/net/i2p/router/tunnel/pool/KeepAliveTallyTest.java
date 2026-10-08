package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 *  Accounting for a keepalive cycle.
 *
 *  <p>Added because the top-tier candidate pool was observed shrinking from roughly 400 peers
 *  delivered per cycle to roughly 210, while the pre-existing log line reported only what was
 *  kept alive and never what was dropped on the way. A tally that accounts for every delivered
 *  peer is what turns "the pool is eroding" into "the pool is eroding because of X".
 *
 *  @since 0.9.71+
 */
public class KeepAliveTallyTest {

    private static TunnelPeerSelector.KeepAliveTally full(int delivered) {
        TunnelPeerSelector.KeepAliveTally t = new TunnelPeerSelector.KeepAliveTally();
        t.requested = 400;
        t.delivered = delivered;
        return t;
    }

    /**
     *  Every peer the selector delivered must land in exactly one bucket. A peer that falls
     *  through every branch unaccounted would let a real leak hide behind a plausible-looking
     *  log line.
     */
    @Test
    public void everyDeliveredPeerIsAccountedFor() {
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
        assertEquals("acted + skipped + unprocessed must equal delivered",
                     t.delivered, t.keepalived + t.preConnected + t.skipCooldown + t.skipStale
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
        t.requested = 400;
        t.delivered = 0;
        assertEquals(0, t.unaccounted());
    }

    /** The erosion case this was built to expose: the pool cannot fill what it asks for. */
    @Test
    public void summaryNamesDeliveredVersusRequested() {
        TunnelPeerSelector.KeepAliveTally t = full(210);
        String line = t.describe(true);
        assertTrue(line, line.contains("210 delivered of 400 requested"));
        assertTrue(line, line.contains("aggressive"));
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
     *  A zero-filled tally still has to say something useful, because the cycle that matters
     *  most is the one where nothing was selectable.
     */
    @Test
    public void anEmptyCycleStillReports() {
        TunnelPeerSelector.KeepAliveTally t = full(0);
        String line = t.describe(false);
        assertTrue(line, line.contains("0 delivered of 400 requested"));
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