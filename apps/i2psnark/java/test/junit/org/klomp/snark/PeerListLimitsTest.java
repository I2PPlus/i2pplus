package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for how many peer addresses one PEX message carries, in each direction. BEP 11 bounds
 * neither side, so both numbers are ours: the send side is payload multiplied out across every
 * peer we advertise to, and the receive side is one {@link PeerID} with a destination lookup
 * behind it per address a peer chooses to name. Both used to be derived from the operator's
 * connection cap, so an operator who set <code>i2psnark.maxConnections</code> to 8 capped how
 * many addresses a peer could tell us about at 7.
 *
 * @since 0.9.72+
 */
public class PeerListLimitsTest {

    /** Length of a peer's destination hash, the unit both limits are counted in. */
    private static final int HASH_LENGTH = 32;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeerListLimitsTest() {}

    /**
     * What we advertise is capped, and a list that already fits is passed through untouched.
     */
    @Test
    public void testAdvertisedPeersAreCapped() {
        assertEquals(0, ExtensionHandler.pexPeersToSend(0));
        assertEquals(1, ExtensionHandler.pexPeersToSend(1));
        assertEquals(ExtensionHandler.MAX_PEX_PEERS, ExtensionHandler.pexPeersToSend(64));
        assertEquals(ExtensionHandler.MAX_PEX_PEERS, ExtensionHandler.pexPeersToSend(65));
        assertEquals(ExtensionHandler.MAX_PEX_PEERS, ExtensionHandler.pexPeersToSend(300));
    }

    /**
     * What we read is capped, and a trailing partial hash is not counted as an address, so a
     * truncated message cannot make us decode a hash that is not there.
     */
    @Test
    public void testReceivedPeersAreCappedAndPartialHashesIgnored() {
        assertEquals(0, ExtensionHandler.pexPeersToRead(0));
        assertEquals(0, ExtensionHandler.pexPeersToRead(HASH_LENGTH - 1));
        assertEquals(1, ExtensionHandler.pexPeersToRead(HASH_LENGTH));
        assertEquals(1, ExtensionHandler.pexPeersToRead(HASH_LENGTH * 2 - 1));
        assertEquals(ExtensionHandler.MAX_PEX_PEERS,
                     ExtensionHandler.pexPeersToRead(HASH_LENGTH * 64));
        assertEquals(ExtensionHandler.MAX_PEX_PEERS,
                     ExtensionHandler.pexPeersToRead(HASH_LENGTH * 5000));
    }

    /**
     * The limit is its own number, not the connection cap: the cap is about how many
     * connections a torrent holds, and it is an operator setting that can be anything.
     */
    @Test
    public void testTheLimitIsNotTheConnectionCap() {
        assertNotEquals(I2PSnarkUtil.MAX_CONNECTIONS - 1, ExtensionHandler.MAX_PEX_PEERS);
        assertNotEquals(I2PSnarkUtil.MAX_CONNECTIONS, ExtensionHandler.MAX_PEX_PEERS);
    }

    /**
     * One message does not have to carry the whole swarm, but a handful must be able to: a
     * peer hears from several peers, each advertising its own newest ones, so five PEX rounds
     * have to cover even the global connection cap.
     */
    @Test
    public void testFiveRoundsCoverTheGlobalConnectionCap() {
        assertTrue(ExtensionHandler.MAX_PEX_PEERS * 5 >= I2PSnarkUtil.MAX_CONNECTIONS);
    }

    /**
     * The payload stays a small message: 32 bytes per address, and a PEX message goes out to
     * every peer that supports the extension.
     */
    @Test
    public void testAdvertisedPayloadStaysSmall() {
        assertTrue(ExtensionHandler.MAX_PEX_PEERS * HASH_LENGTH <= 2048);
    }
}
