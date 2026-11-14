package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Pins when the checker says anything about a peer. Its per-peer matrix - what we want
 * from the peer, what we do to it, what it wants and does to us, and the rates - used to
 * print for every connected peer on every pass, whatever had changed: ten thousand lines
 * in a two and a half hour window, nearly all of them repeating the last. What a reader
 * wants is the moment the treatment of a peer changes, so the pass compares the four bits
 * against the last ones it reported and stays quiet when they hold still.
 *
 * @since 0.9.72+
 */
public class CheckerStateTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public CheckerStateTest() {}

    /**
     * A peer with no socket, for the state the checker carries on it.
     *
     * @return a socketless peer
     * @throws Throwable on a bencode error in the fixture
     */
    private static Peer peer() throws Throwable {
        byte[] hashes = new byte[2 * 20];
        StringBuilder sb = new StringBuilder(256);
        sb.append("d4:infod6:lengthi2048e4:name9:test.file12:piece lengthi1024e6:pieces");
        sb.append(hashes.length).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, raw, 0, head.length);
        System.arraycopy(hashes, 0, raw, head.length, hashes.length);
        System.arraycopy(tail, 0, raw, head.length + hashes.length, tail.length);
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(raw));
        return new Peer(new PeerID(new byte[32], (I2PSnarkUtil) null), new byte[20],
                        mi.getInfoHash(), mi);
    }

    /**
     * Every combination of the four flags packs to its own value, so a pass that leaves
     * one of them moving is never mistaken for one that changed nothing.
     */
    @Test
    public void testEveryCombinationIsItsOwnState() {
        Set<Integer> seen = new HashSet<>();
        for (int bits = 0; bits < 16; bits++) {
            int state = PeerCheckerTask.checkState((bits & 1) != 0, (bits & 2) != 0,
                                                   (bits & 4) != 0, (bits & 8) != 0);
            assertTrue("combination " + bits + " collides", seen.add(state));
        }
        assertEquals(16, seen.size());
    }

    /**
     * A peer the checker has not reported on yet is reported on: nothing it has recorded
     * matches any state the pass can pack.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testTheFirstPassAlwaysReports() throws Throwable {
        Peer peer = peer();
        assertEquals(-1, peer.checkedState());
        for (int bits = 0; bits < 16; bits++) {
            int state = PeerCheckerTask.checkState((bits & 1) != 0, (bits & 2) != 0,
                                                   (bits & 4) != 0, (bits & 8) != 0);
            assertTrue("state " + state + " matches the unset state",
                       state != peer.checkedState());
        }
    }

    /**
     * A pass that reports a peer's state leaves it recorded, so the next pass with the
     * same four flags has nothing to say - and a pass with one flag moved does.
     *
     * @throws Throwable on a bencode error in the fixture
     */
    @Test
    public void testAnUnchangedPeerIsNotReportedAgain() throws Throwable {
        Peer peer = peer();
        int state = PeerCheckerTask.checkState(true, false, true, false);
        peer.setCheckedState(state);
        assertEquals(state, peer.checkedState());
        assertEquals(state, PeerCheckerTask.checkState(true, false, true, false));
        assertNotEquals(state, PeerCheckerTask.checkState(true, true, true, false));
    }
}
