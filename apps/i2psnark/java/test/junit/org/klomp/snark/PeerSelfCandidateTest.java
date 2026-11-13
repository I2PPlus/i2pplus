package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Pins the self-connect predicate behind the guard in
 * {@link PeerCoordinator#addPeer(Peer)}: the tracker, PEX and the DHT all report
 * our own destination back to us while we are in the swarm, so the predicate has
 * to refuse a hash it does not know rather than treat two unknowns as a match,
 * which is what the array-equality helper it wraps does.
 *
 * @since 0.9.72+
 */
public class PeerSelfCandidateTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the predicate under test is static and carries no state.
     *
     * @since 0.9.72+
     */
    public PeerSelfCandidateTest() {}

    /**
     * 32-byte destination hash stand-in with every byte set to the given value.
     *
     * @param val the byte value written into all 32 bytes
     * @return the 32-byte stand-in hash, every byte set to val
     */
    private static byte[] dhash(int val) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        Arrays.fill(b, (byte) val);
        return b;
    }

    /**
     * Two known hashes of one destination match, so the peer is dropped before a
     * socket is opened.
     */
    @Test
    public void testSameDestinationIsSelf() {
        assertTrue(PeerCoordinator.isSelf(dhash(1), dhash(1)));
    }

    /**
     * Two known destinations that differ do not match, not even when one is a
     * prefix of the other.
     */
    @Test
    public void testDifferentDestinationIsNotSelf() {
        assertFalse(PeerCoordinator.isSelf(dhash(1), dhash(2)));
        byte[] truncated = Arrays.copyOf(dhash(1), Hash.HASH_LENGTH / 2);
        assertFalse(PeerCoordinator.isSelf(dhash(1), truncated));
    }

    /**
     * A hash we do not know is never a match. Both sides unknown is the case that
     * matters: the array-equality helper reports two nulls as equal, and reading
     * that as "this peer is us" would drop every peer whose destination has not
     * been resolved yet.
     */
    @Test
    public void testUnknownHashIsNotSelf() {
        assertFalse(PeerCoordinator.isSelf(null, dhash(1)));
        assertFalse(PeerCoordinator.isSelf(dhash(1), null));
        assertFalse(PeerCoordinator.isSelf(null, null));
    }

    /**
     * A zero-length hash is a value of the wrong size, not our own destination.
     */
    @Test
    public void testEmptyHashIsNotSelf() {
        assertFalse(PeerCoordinator.isSelf(dhash(1), new byte[0]));
        assertFalse(PeerCoordinator.isSelf(new byte[0], dhash(1)));
    }
}
