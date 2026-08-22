package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the BEP 6 allowed-fast set generation in
 * {@link PeerState#generateAllowedFastSet}: determinism, bounds, small-torrent
 * termination (the modular loop cannot reach ten distinct indices when the
 * torrent has fewer pieces), and symmetry between the sending side's
 * computation and the receiving side's expected set.
 *
 * @since 0.9.71+
 */
public class AllowedFastSetTest {

    /** 32-byte destination hash stand-in with every byte set to the given value. */
    private static byte[] dhash(int val) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) val);
        return b;
    }

    private static byte[] infohash(int val) {
        byte[] b = new byte[20];
        Arrays.fill(b, (byte) val);
        return b;
    }

    @Test
    public void deterministicForSameInputs() {
        Set<Integer> a = PeerState.generateAllowedFastSet(dhash(1), infohash(2), 5000);
        Set<Integer> b = PeerState.generateAllowedFastSet(dhash(1), infohash(2), 5000);
        assertEquals(a, b);
    }

    @Test
    public void differentPeersGetDifferentSets() {
        // not a strict requirement of BEP 6 but a property of a healthy hash
        // spread; two identical sets for different peers would defeat the
        // load-spreading purpose of the per-peer derivation
        assertNotEquals(PeerState.generateAllowedFastSet(dhash(3), infohash(4), 5000),
                        PeerState.generateAllowedFastSet(dhash(5), infohash(4), 5000));
    }

    @Test
    public void indicesWithinBoundsAndBoundedSize() {
        int pieces = 4096;
        Set<Integer> set = PeerState.generateAllowedFastSet(dhash(6), infohash(7), pieces);
        assertFalse(set.isEmpty());
        assertTrue("set must be capped at ten", set.size() <= 10);
        for (int p : set) {
            assertTrue("index out of range: " + p, p >= 0 && p < pieces);
        }
    }

    @Test
    public void smallTorrentReturnsEveryPiece() {
        // fewer pieces than the target set size: all pieces, exactly once,
        // and - critically - termination
        Set<Integer> set = PeerState.generateAllowedFastSet(dhash(8), infohash(9), 5);
        assertEquals(5, set.size());
        for (int i = 0; i < 5; i++) {
            assertTrue("piece " + i + " missing from full set", set.contains(i));
        }
    }

    @Test
    public void singlePieceTorrentTerminates() {
        Set<Integer> set = PeerState.generateAllowedFastSet(dhash(10), infohash(11), 1);
        assertEquals(1, set.size());
        assertTrue(set.contains(0));
    }

    @Test
    public void senderAndReceiverComputeSameSet() {
        // the sender derives from the receiver's destination hash, the
        // receiver validates against its own - both must agree
        byte[] receiverHash = dhash(12);
        Set<Integer> sent = PeerState.generateAllowedFastSet(receiverHash, infohash(13), 3000);
        Set<Integer> validated = PeerState.generateAllowedFastSet(receiverHash, infohash(13), 3000);
        assertEquals(sent, validated);
    }

    @Test
    public void nullInputsYieldEmptySet() {
        assertNotNull(PeerState.generateAllowedFastSet(null, infohash(1), 100));
        assertNotNull(PeerState.generateAllowedFastSet(dhash(1), null, 100));
        assertNotNull(PeerState.generateAllowedFastSet(dhash(1), infohash(1), 0));
        assertTrue(PeerState.generateAllowedFastSet(null, null, 0).isEmpty());
    }

    // ---- libtorrent interop (zeroed IPv4 prefix) ----------------------------

    @Test
    public void zeroPrefixSetIsDeterministic() {
        // libtorrent derives the same torrent-wide set for every i2p peer;
        // two computations must agree
        byte[] zeros = new byte[4];
        Set<Integer> a = PeerState.generateAllowedFastSet(zeros, infohash(20), 3000);
        Set<Integer> b = PeerState.generateAllowedFastSet(zeros, infohash(20), 3000);
        assertEquals(a, b);
    }

    @Test
    public void zeroPrefixSetBoundedAndInRange() {
        byte[] zeros = new byte[4];
        Set<Integer> set = PeerState.generateAllowedFastSet(zeros, infohash(21), 4096);
        assertFalse(set.isEmpty());
        assertTrue(set.size() <= 10);
        for (int p : set) {
            assertTrue("index out of range: " + p, p >= 0 && p < 4096);
        }
    }

    @Test
    public void zeroPrefixSmallTorrentReturnsAllPieces() {
        byte[] zeros = new byte[4];
        Set<Integer> set = PeerState.generateAllowedFastSet(zeros, infohash(22), 7);
        assertEquals(7, set.size());
        for (int i = 0; i < 7; i++) {
            assertTrue(set.contains(i));
        }
    }

    @Test
    public void zeroPrefixDiffersFromDestHashDerivation() {
        // the two accepted derivations must produce different sets for a
        // typical torrent, otherwise the union adds nothing
        Set<Integer> ltStyle = PeerState.generateAllowedFastSet(new byte[4], infohash(23), 4096);
        Set<Integer> destStyle = PeerState.generateAllowedFastSet(dhash(24), infohash(23), 4096);
        assertNotEquals(ltStyle, destStyle);
    }
}
