package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins what happens to a torrent connection that carries no traffic. Streaming was told to
 * disconnect after 3 idle minutes, while snark's own policy drops an idle peer at 5 minutes
 * and an idle seed at 3, and keep-alives are only sent under a condition that excludes a
 * seed talking to a seed. The transport therefore ended the connection before snark's policy
 * could run, discarding the negotiated BT state - choke flags, allowed-fast set, request
 * pipeline - earlier than the code intends.
 *
 * @since 0.9.72+
 */
public class InactivityPolicyTest {

    /** Streaming's own default idle timeout, in ms. */
    private static final long STREAMING_DEFAULT_TIMEOUT = 300000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * values under test are constants and carry no state.
     *
     * @since 0.9.72+
     */
    public InactivityPolicyTest() {}

    /**
     * An idle stream is pinged, not dropped. Dropping it is snark's decision to make, and
     * snark makes it from the peer's own activity, not from the socket's.
     */
    @Test
    public void testIdleStreamIsPingedNotDropped() {
        assertEquals(2, I2PSnarkUtil.STREAMING_INACTIVITY_ACTION);
        assertNotEquals(1, I2PSnarkUtil.STREAMING_INACTIVITY_ACTION);
        assertNotEquals(0, I2PSnarkUtil.STREAMING_INACTIVITY_ACTION);
    }

    /**
     * The ping fires no later than snark's own deadline for an idle peer, so the stream is
     * still open when that policy runs and the peer is dropped for the reason snark chose
     * rather than for an idle socket.
     */
    @Test
    public void testPingPrecedesThePeerDeadline() {
        assertTrue(I2PSnarkUtil.STREAMING_INACTIVITY_MS <= PeerCoordinator.MAX_INACTIVE);
        assertTrue(I2PSnarkUtil.STREAMING_INACTIVITY_MS <= PeerCoordinator.MAX_SEED_INACTIVE);
    }

    /**
     * The idle timeout is a real interval: positive, well short of the connect budget so it
     * can actually fire on an established connection, and no longer than streaming's own
     * default so a router that tunes nothing still behaves.
     */
    @Test
    public void testIdleTimeoutIsSane() {
        assertTrue(I2PSnarkUtil.STREAMING_INACTIVITY_MS > 0);
        assertTrue(I2PSnarkUtil.STREAMING_INACTIVITY_MS < I2PSnarkUtil.CONNECT_TIMEOUT_MS);
        assertTrue(I2PSnarkUtil.STREAMING_INACTIVITY_MS <= STREAMING_DEFAULT_TIMEOUT);
    }

    /**
     * The peer deadlines themselves are pinned, because the invariant above is only as good
     * as the numbers it compares against: five minutes for a peer we may still download from,
     * three for a seed we have no reason to keep.
     */
    @Test
    public void testPeerDeadlines() {
        assertEquals(300000L, PeerCoordinator.MAX_INACTIVE);
        assertEquals(180000L, PeerCoordinator.MAX_SEED_INACTIVE);
        assertTrue(PeerCoordinator.MAX_SEED_INACTIVE <= PeerCoordinator.MAX_INACTIVE);
    }
}
