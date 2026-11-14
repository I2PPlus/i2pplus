package org.klomp.snark.dht;

import static org.junit.Assert.*;

import org.junit.Test;
import org.klomp.snark.I2PSnarkUtil;

/**
 * Tests for how many peer addresses one get_peers message carries, in each direction. BEP 5
 * bounds neither, so the number is ours: every entry is a 32-byte hash inside a UDP packet,
 * and the requester queries the closest nodes and asks again later, so one reply never has to
 * hold the whole swarm. This used to be three times the operator's connection cap, 900
 * addresses, or 28 KB in a single reply.
 *
 * @since 0.9.72+
 */
public class PeersPerQueryTest {

    /** Length of a peer's destination hash, the unit the limit is counted in. */
    private static final int HASH_LENGTH = 32;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeersPerQueryTest() {}

    /**
     * Addresses past the limit are dropped rather than decoded, and a reply that already fits
     * is kept whole.
     */
    @Test
    public void testReceivedPeersAreCapped() {
        assertEquals(0, KRPC.peersToRead(0));
        assertEquals(1, KRPC.peersToRead(1));
        assertEquals(KRPC.MAX_PEERS, KRPC.peersToRead(KRPC.MAX_PEERS));
        assertEquals(KRPC.MAX_PEERS, KRPC.peersToRead(KRPC.MAX_PEERS + 1));
        assertEquals(KRPC.MAX_PEERS, KRPC.peersToRead(900));
    }

    /**
     * The limit is its own number, not the connection cap: the cap is about how many
     * connections a torrent holds and is an operator setting, while this is about the size of
     * one routing-table reply.
     */
    @Test
    public void testTheLimitIsNotTheConnectionCap() {
        assertNotEquals(I2PSnarkUtil.MAX_CONNECTIONS * 3, KRPC.MAX_PEERS);
        assertNotEquals(I2PSnarkUtil.MAX_CONNECTIONS, KRPC.MAX_PEERS);
    }

    /**
     * A reply stays a small packet, and a few of them still cover even the global connection
     * cap, which is what makes the cut safe.
     */
    @Test
    public void testAReplyStaysSmallAndFiveCoverTheSwarm() {
        assertTrue(KRPC.MAX_PEERS * HASH_LENGTH <= 2048);
        assertTrue(KRPC.MAX_PEERS * 5 >= I2PSnarkUtil.MAX_CONNECTIONS);
    }
}
