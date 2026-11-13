package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins that a torrent which already holds every piece keeps looking for peers, and keeps
 * telling its peers about the others it knows. A completed torrent used to fail the
 * "do we want bytes" test that gated every route to a new peer - PEX intake, the DHT
 * fetch, the backup trackers and the dial loop - and it never sent PEX to a peer that
 * was itself a seed. The result is a seed with a peer list that never refills and nobody
 * to upload to.
 *
 * @since 0.9.72+
 */
public class SeedPeerAcquisitionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public SeedPeerAcquisitionTest() {}

    /**
     * A torrent below its connection margin wants another outbound connection whether or
     * not it still wants pieces - that is the whole fix.
     */
    @Test
    public void testBelowTheMarginWantsPeers() {
        assertTrue(PeerCoordinator.needOutboundPeers(false, 0, 16, false));
        assertTrue(PeerCoordinator.needOutboundPeers(false, 13, 16, false));
    }

    /**
     * The two-connection margin still stops the dialling, so a seed cannot push the
     * torrent past its cap chasing uploaders.
     */
    @Test
    public void testTheMarginStillStopsIt() {
        assertFalse(PeerCoordinator.needOutboundPeers(false, 14, 16, false));
        assertFalse(PeerCoordinator.needOutboundPeers(false, 15, 16, false));
        assertFalse(PeerCoordinator.needOutboundPeers(false, 16, 16, false));
    }

    /**
     * A halted coordinator wants nothing, and a torrent whose storage is rechecking the
     * whole file waits rather than adding peers mid-scan.
     */
    @Test
    public void testHaltedAndCheckingAreRefused() {
        assertFalse(PeerCoordinator.needOutboundPeers(true, 0, 16, false));
        assertFalse(PeerCoordinator.needOutboundPeers(false, 0, 16, true));
    }

    /**
     * A tiny torrent's cap is honoured: four connections means two outbound dials, not
     * the global maximum.
     */
    @Test
    public void testTinyTorrentCap() {
        assertTrue(PeerCoordinator.needOutboundPeers(false, 0, 4, false));
        assertTrue(PeerCoordinator.needOutboundPeers(false, 1, 4, false));
        assertFalse(PeerCoordinator.needOutboundPeers(false, 2, 4, false));
    }

    /**
     * A cap at or below the two-connection margin leaves no room for an outbound dial at
     * all, rather than wrapping into a negative bound that admits everything.
     */
    @Test
    public void testCapAtOrBelowTheMarginIsFull() {
        assertFalse(PeerCoordinator.needOutboundPeers(false, 0, 2, false));
        assertFalse(PeerCoordinator.needOutboundPeers(false, 0, 1, false));
    }

    /**
     * PEX goes out on the period boundary and to every peer, whatever that peer's own
     * completion state is.
     */
    @Test
    public void testPEXFollowsThePeriod() {
        int period = PeerCheckerTask.PEX_PERIOD;
        assertTrue(PeerCheckerTask.sendPEX(0, period));
        assertTrue(PeerCheckerTask.sendPEX(period, period));
        assertTrue(PeerCheckerTask.sendPEX(period * 7, period));
        assertFalse(PeerCheckerTask.sendPEX(1, period));
        assertFalse(PeerCheckerTask.sendPEX(period - 1, period));
    }

    /**
     * The PEX cadence in seconds, pinned because the comment above the send used to
     * claim twelve minutes while the period it computed was a little over one.
     */
    @Test
    public void testPEXIntervalMatchesItsComment() {
        long seconds = PeerCheckerTask.PEX_PERIOD * PeerCoordinator.CHECK_PERIOD / 1000;
        assertEquals(85, seconds);
        // a minute and a half, not the twelve minutes the old comment said
        assertTrue(Long.toString(seconds), seconds < 120);
    }

    /**
     * A comment request is due once the interval has passed, and not before, so the
     * failsafe that pushes the timestamp forward when a swarm does not support comments
     * actually buys the interval it is meant to buy.
     */
    @Test
    public void testCommentInterval() {
        long interval = PeerCoordinator.COMMENT_REQ_INTERVAL;
        long base = 1_700_000_000_000L;
        assertFalse(PeerCoordinator.commentsDue(base, base, interval));
        assertFalse(PeerCoordinator.commentsDue(base + interval - 1, base, interval));
        assertTrue(PeerCoordinator.commentsDue(base + interval, base, interval));
        assertTrue(PeerCoordinator.commentsDue(base + interval * 3, base, interval));
    }

    /**
     * A timestamp set in the future, which the jitter at construction can produce for a
     * torrent whose first request is still an hour away, is not due.
     */
    @Test
    public void testFutureTimestampIsNotDue() {
        long base = 1_700_000_000_000L;
        assertFalse(PeerCoordinator.commentsDue(base, base + 3600_000L,
                                                PeerCoordinator.COMMENT_REQ_INTERVAL));
    }
}
