package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins that a peer choked on a pass is not unchoked again on that same pass. The checker
 * choked the worst uploader and then called the optimistic unchoke, which re-read the
 * allowed uploader count - now back up one after the choke - and picked in peer-list order,
 * while the choked peers were moved to the tail of the list only afterwards. The peer just
 * choked was usually the next one picked, so the pass spent a CHOKE and an UNCHOKE on it and
 * cancelled its outstanding requests and its partial pieces for nothing.
 *
 * @since 0.9.72+
 */
public class SamePassUnchokeTest {

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** Pieces in the fixture torrent. */
    private static final int PIECES = 64;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SamePassUnchokeTest() {}

    /**
     * A bencoded single-file torrent with the given number of pieces.
     *
     * @param pieces how many pieces the torrent has
     * @return the bencoded torrent bytes
     */
    private static byte[] torrentBytes(int pieces) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append((long) PIECE_LENGTH * pieces).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        sb.append("6:pieces").append(20 * pieces).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] hashes = new byte[20 * pieces];
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return rv;
    }

    /** The fixture metainfo, rebuilt per call so callers own their own copy. */
    private static MetaInfo metainfo() throws Throwable {
        return new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
    }

    /**
     * A coordinator over a torrent with nothing on disk and no coordinator listener, so
     * the global upload limit is never reported exceeded. The caller must halt it to stop
     * the checker timer.
     *
     * @param mi the metainfo to build the storage from
     * @return a coordinator with no uploaders yet
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(MetaInfo mi) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Storage st = new Storage(util, new File("/tmp/snark-unchoke-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A peer that is choking us and wants pieces from us, so a candidate for the
     * optimistic unchoke. Its peer state is attached directly; the peer has no socket.
     *
     * @param util the snark util the peer ids are built against
     * @param mi the torrent the peer is on
     * @param n the peer number, used as its id so peers sort into a known order
     * @return a socketless peer that is choking us and wants pieces from us
     * @throws Throwable on peer id failure
     */
    private static Peer interestedPeer(I2PSnarkUtil util, MetaInfo mi, int n) throws Throwable {
        byte[] id = new byte[32];
        id[0] = (byte) n;
        PeerID pID = new PeerID(id, util);
        Peer peer = new Peer(pID, new byte[20], mi.getInfoHash(), mi);
        PeerConnectionOut out =
                new PeerConnectionOut(peer, new DataOutputStream(new ByteArrayOutputStream()));
        PeerState state = new PeerState(peer, null, null, mi, null, out);
        state.interested = true;
        peer.state = state;
        return peer;
    }

    /**
     * A bandwidth listener that reports no limit, so the uploader ceiling is the plain
     * per-torrent cap.
     */
    private static class NullBWL implements BandwidthListener {

        @Override
        public long getUploadRate() {
            return 0;
        }

        @Override
        public long getDownloadRate() {
            return 0;
        }

        @Override
        public void uploaded(int size) { /* no-op */ }

        @Override
        public void downloaded(int size) { /* no-op */ }

        @Override
        public boolean shouldSend(int size) {
            return true;
        }

        @Override
        public boolean shouldRequest(Peer peer, int size) {
            return true;
        }

        @Override
        public long getUpBWLimit() {
            return Long.MAX_VALUE;
        }

        @Override
        public long getDownBWLimit() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean overUpBWLimit() {
            return false;
        }

        @Override
        public boolean overDownBWLimit() {
            return false;
        }
    }

    /**
     * A peer choked moments ago is left choking, and the slot goes to a peer that was not
     * just choked. This is the pair the checker used to produce every pass it choked the
     * worst uploader.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testPeerChokedThisPassStaysChoking() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            Peer first = interestedPeer(util, mi, 1);
            Peer second = interestedPeer(util, mi, 2);
            pc.peers.add(first);
            pc.peers.add(second);
            assertTrue(first.isChoking());
            assertTrue(second.isChoking());
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.singleton(first));
            }
            assertTrue(first.isChoking());
            assertFalse(second.isChoking());
            assertEquals(1, pc.getUploaders());
        } finally {
            pc.halt();
        }
    }

    /**
     * With nothing choked on the pass, the unchoke still opens its one slot, so the skip
     * list does not suppress the optimistic unchoke the checker relies on.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testWithoutASkipTheHeadIsUnchoked() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            Peer first = interestedPeer(util, mi, 1);
            Peer second = interestedPeer(util, mi, 2);
            pc.peers.add(first);
            pc.peers.add(second);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(first.isChoking());
            assertTrue(second.isChoking());
            assertEquals(1, pc.getUploaders());
        } finally {
            pc.halt();
        }
    }

    /**
     * When the only peer waiting for a slot is the one just choked, nothing is unchoked on
     * this pass rather than the same peer being opened again, and no upload slot is spent.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testNoOscillationWhenOnlyCandidateWasJustChoked() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            Peer only = interestedPeer(util, mi, 1);
            pc.peers.add(only);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.singleton(only));
            }
            assertTrue(only.isChoking());
            assertEquals(0, pc.getUploaders());
        } finally {
            pc.halt();
        }
    }

    /**
     * Every peer choked on the pass is skipped, not just the last one, so a pass that
     * choked several uploaders to make room does not immediately hand the slots back to
     * those same peers.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testEveryPeerChokedThisPassIsSkipped() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            Peer one = interestedPeer(util, mi, 1);
            Peer two = interestedPeer(util, mi, 2);
            Peer three = interestedPeer(util, mi, 3);
            pc.peers.add(one);
            pc.peers.add(two);
            pc.peers.add(three);
            List<Peer> choked = Arrays.asList(one, two);
            synchronized (pc.peers) {
                pc.unchokePeer(choked);
            }
            assertTrue(one.isChoking());
            assertTrue(two.isChoking());
            assertFalse(three.isChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * The unchoke opens one slot per pass, which is what made the re-choke loop possible:
     * the count the unchoke works to is the current uploader count plus one.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testOneSlotOpensPerPass() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi);
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            pc.peers.add(interestedPeer(util, mi, 1));
            pc.peers.add(interestedPeer(util, mi, 2));
            pc.peers.add(interestedPeer(util, mi, 3));
            assertEquals(1, pc.allowedUploaders());
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertEquals(1, pc.getUploaders());
        } finally {
            pc.halt();
        }
    }
}
