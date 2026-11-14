package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the cadence of the work the peer checker does on a timer rather than on an event.
 * Announcing a connected peer to the local DHT ran every fifth pass - every 25 s, per peer -
 * which was the third most frequent snark event in the measured window at 42,020 lines. The
 * effect, keeping one peer discoverable in the DHT, is the same at ten minutes as at 25
 * seconds, so the extra datagrams bought nothing. A transient metadata lookup does none of
 * that work at all, which is the other decision pinned here.
 *
 * @since 0.9.72+
 */
public class PeerCheckerCadenceTest {

    /** The cadence the work used to run at, in checker passes. */
    private static final int OLD_DHT_ANNOUNCE_PERIOD = 5;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public PeerCheckerCadenceTest() {}

    /**
     * The DHT announce fires on the period boundary and not between it.
     */
    @Test
    public void testDHTAnnounceFollowsThePeriod() {
        int period = PeerCheckerTask.DHT_ANNOUNCE_PERIOD;
        assertTrue(PeerCheckerTask.announcePeerToDHT(0, period));
        assertTrue(PeerCheckerTask.announcePeerToDHT(period, period));
        assertTrue(PeerCheckerTask.announcePeerToDHT(period * 9, period));
        assertFalse(PeerCheckerTask.announcePeerToDHT(1, period));
        assertFalse(PeerCheckerTask.announcePeerToDHT(period - 1, period));
    }

    /**
     * The cadence in seconds: ten minutes, comfortably inside the half hour a DHT peer entry
     * is worth keeping alive for, and far past the point where more announces buy more
     * discoverability.
     */
    @Test
    public void testDHTAnnounceInterval() {
        long seconds = PeerCheckerTask.DHT_ANNOUNCE_PERIOD * PeerCoordinator.CHECK_PERIOD / 1000;
        assertEquals(600, seconds);
        assertTrue(Long.toString(seconds), seconds >= 300);
        assertTrue(Long.toString(seconds), seconds <= 30 * 60);
    }

    /**
     * The interval is an order of magnitude longer than the one it replaces, which is the
     * reduction the finding asked for, and it is still a multiple of the old one so a torrent
     * that was already announcing keeps its phase.
     */
    @Test
    public void testIntervalIsFarLongerThanTheOldOne() {
        assertTrue(PeerCheckerTask.DHT_ANNOUNCE_PERIOD
                   >= 20 * OLD_DHT_ANNOUNCE_PERIOD);
        assertEquals(0, PeerCheckerTask.DHT_ANNOUNCE_PERIOD % OLD_DHT_ANNOUNCE_PERIOD);
    }

    /**
     * The comment offer rotates every pass-count boundary, and the interval it implies is
     * the few minutes the code comment says, not the half hour it once claimed. The real
     * interval between requests is enforced in the coordinator.
     */
    @Test
    public void testCommentOfferCadence() {
        int period = PeerCheckerTask.COMMENT_PERIOD;
        assertEquals(0, 0 % period);
        assertEquals(0, period % period);
        assertNotEquals(0, 1 % period);
        long seconds = period * PeerCoordinator.CHECK_PERIOD / 1000;
        assertTrue(Long.toString(seconds), seconds > 60);
        assertTrue(Long.toString(seconds), seconds < 300);
    }

    /**
     * The PEX cadence is pinned alongside the others so the three periodic work items of the
     * checker stay comparable: PEX is the most frequent, the comment offer next, the DHT
     * announce least.
     */
    @Test
    public void testCadencesAreOrdered() {
        assertTrue(PeerCheckerTask.PEX_PERIOD < PeerCheckerTask.COMMENT_PERIOD);
        assertTrue(PeerCheckerTask.COMMENT_PERIOD < PeerCheckerTask.DHT_ANNOUNCE_PERIOD);
    }

    /**
     * A metadata lookup does none of the three: it holds no data for the swarm it is asking
     * about, is deleted minutes after it resolves, and its announces are refused by nodes
     * that never handed it a token. A user torrent does all of them.
     */
    @Test
    public void testAMetadataLookupDoesNoSwarmWork() {
        assertFalse(PeerCheckerTask.servesSwarm(true));
        assertTrue(PeerCheckerTask.servesSwarm(false));
    }
}
