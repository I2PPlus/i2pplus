package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import net.i2p.I2PAppContext;

import org.junit.Test;

/**
 * Tests for the TrackerInfo peer set contract: getPeers() never returns null and never
 * returns a set a caller can modify.
 *
 * <p>A failure response carries no peer list at all, and a successful one is built from a
 * freshly decoded HashSet. Callers iterate the result without checking, so a null or a
 * mutable set is a latent NPE or a data race, not a style problem.
 *
 * @since 0.9.71+
 */
public class TrackerInfoTest {

    private static final byte[] MY_ID = new byte[20];
    private static final byte[] INFOHASH = new byte[20];

    private static TrackerInfo parse(String bencoded) throws Throwable {
        InputStream in = new ByteArrayInputStream(bencoded.getBytes(StandardCharsets.ISO_8859_1));
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        return new TrackerInfo(in, MY_ID, INFOHASH, null, util);
    }

    /** A 32-byte compact peer entry, as a tracker sends it. */
    private static String compactPeer(char fill) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(fill);
        }
        return sb.toString();
    }

    /** A failure response has no peers, and must still hand back an empty set. */
    @Test
    public void testFailureResponseHasEmptyPeerSet() throws Throwable {
        TrackerInfo info = parse("d14:failure reason7:go awaye");
        assertEquals("go away", info.getFailureReason());
        assertNotNull(info.getPeers());
        assertTrue(info.getPeers().isEmpty());
        assertEquals(0, info.getPeerCount());
    }

    /** A success response with no peers key hands back an empty set. */
    @Test
    public void testNoPeersKeyGivesEmptySet() throws Throwable {
        TrackerInfo info = parse("d8:intervali1800ee");
        assertNull(info.getFailureReason());
        assertNotNull(info.getPeers());
        assertTrue(info.getPeers().isEmpty());
        assertEquals(1800, info.getInterval());
    }

    /** A compact peer list is decoded and handed back unmodifiable. */
    @Test
    public void testCompactPeersAreUnmodifiable() throws Throwable {
        TrackerInfo info = parse("d8:intervali1800e5:peers32:" + compactPeer('a') + "e");
        Set<Peer> peers = info.getPeers();
        assertNotNull(peers);
        assertEquals(1, peers.size());
        assertEquals(1, info.getPeerCount());
        try {
            peers.add(null);
            fail("peer set must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    /** Two compact peer entries are both decoded. */
    @Test
    public void testTwoCompactPeers() throws Throwable {
        TrackerInfo info =
                parse("d8:intervali900e5:peers64:" + compactPeer('a') + compactPeer('b') + "e");
        assertEquals(2, info.getPeers().size());
    }

    /**
     * The peer count still reflects the scrape numbers when they exceed the peer list size.
     * The sum has one subtracted because it counts us as well.
     */
    @Test
    public void testPeerCountPrefersTheLargerScrape() throws Throwable {
        TrackerInfo info =
                parse("d8:intervali1800e5:peers32:" + compactPeer('a') + "8:completei50ee");
        assertEquals(1, info.getPeers().size());
        assertEquals(50, info.getSeedCount());
        assertEquals(49, info.getPeerCount());
    }

    /** A response with no interval is rejected, as before. */
    @Test(expected = Exception.class)
    public void testMissingIntervalRejected() throws Throwable {
        parse("d5:peers0:e");
    }
}
