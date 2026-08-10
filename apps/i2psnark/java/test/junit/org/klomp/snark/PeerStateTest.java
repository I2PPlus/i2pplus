package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the BEP 6 allowed fast message handling in PeerState: range checks and the
 * receiver-side verification of advertised indices against the set derived from our own
 * destination hash.
 *
 * @since 0.9.71+
 */
public class PeerStateTest {

    private static final int PIECE_LENGTH = 16384;
    private static final long TOTAL_LENGTH = 30000L;

    /** Build a bencoded single-file torrent byte stream. */
    private static byte[] buildTorrentBytes(byte[] pieceHashes) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append(TOTAL_LENGTH).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        sb.append("6:pieces").append(pieceHashes.length).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + pieceHashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(pieceHashes, 0, rv, head.length, pieceHashes.length);
        System.arraycopy(tail, 0, rv, head.length + pieceHashes.length, tail.length);
        return rv;
    }

    /** Peer with no socket, so getI2PSocket() returns null. */
    private static Peer peerFor(MetaInfo mi) throws Exception {
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        return new Peer(pid, new byte[20], mi.getInfoHash(), mi);
    }

    /** PeerState for a two-piece torrent, unchoked and with the given expected set. */
    private static PeerState stateFor(Set<Integer> expected) throws Exception {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(new byte[40])));
        PeerState ps = new PeerState(peerFor(mi), null, null, mi, null, null);
        ps.choked = false;
        ps._expectedAllowedFast = expected;
        return ps;
    }

    @Test
    public void testRejectsOutOfRangePieces() throws Exception {
        PeerState ps = stateFor(new HashSet<>(Arrays.asList(0)));
        ps.allowedFastMessage(-1);
        ps.allowedFastMessage(2);
        assertTrue(ps._peerAllowedFast.isEmpty());
    }

    @Test
    public void testDropsUnexpectedPieces() throws Exception {
        PeerState ps = stateFor(new HashSet<>(Arrays.asList(0)));
        ps.allowedFastMessage(1);
        assertTrue(ps._peerAllowedFast.isEmpty());
        ps.allowedFastMessage(0);
        assertEquals(new HashSet<>(Arrays.asList(0)), ps._peerAllowedFast);
    }

    @Test
    public void testUnverifiableDestinationAcceptsAll() throws Exception {
        PeerState ps = stateFor(null);
        ps.allowedFastMessage(1);
        assertEquals(new HashSet<>(Arrays.asList(1)), ps._peerAllowedFast);
    }

    @Test
    public void testGenerateAllowedFastSetInvariants() throws Exception {
        byte[] ih = new byte[20];
        Arrays.fill(ih, (byte) 0x13);
        byte[] h1 = new byte[32];
        Arrays.fill(h1, (byte) 0x42);
        byte[] h2 = new byte[32];
        Arrays.fill(h2, (byte) 0x24);
        int pieces = 1000;
        Set<Integer> a = PeerState.generateAllowedFastSet(h1, ih, pieces);
        assertEquals(a, PeerState.generateAllowedFastSet(h1, ih, pieces));
        assertTrue(a.size() > 0 && a.size() <= 10);
        for (Integer i : a) {
            assertTrue(i.intValue() >= 0 && i.intValue() < pieces);
        }
        assertFalse(a.equals(PeerState.generateAllowedFastSet(h2, ih, pieces)));
        assertTrue(PeerState.generateAllowedFastSet(h1, ih, 0).isEmpty());
    }
}
