package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins how the count of peers waiting for an upload slot is maintained. The unchoke set the
 * counter from a fresh scan, and the checker then added the peers it had just choked on top
 * of a scan that had already seen them, so every choke was counted twice. And when the
 * upload limit was reached the unchoke returned before the scan at all, leaving the counter
 * to grow by the checker's additions alone for as long as we stayed over the limit - an
 * inflated count the choke cascade then read as peers waiting for a slot.
 *
 * @since 0.9.72+
 */
public class InterestedChokingCountTest {

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** Pieces in the fixture torrent. */
    private static final int PIECES = 64;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public InterestedChokingCountTest() {}

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

    /** The fixture metainfo, rebuilt per call so each test owns its own copy. */
    private static MetaInfo metainfo() throws Throwable {
        return new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
    }

    /**
     * A coordinator over a torrent with nothing on disk and no coordinator listener. The
     * caller must halt it to stop the checker timer.
     *
     * @param mi the metainfo to build the storage from
     * @param overUpLimit true to have the bandwidth listener report snark over its upload
     *                    limit, which stops the unchoke opening any slot
     * @return a coordinator with no uploaders yet
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(MetaInfo mi, boolean overUpLimit) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Storage st = new Storage(util, new File("/tmp/snark-count-test"), mi, null, false);
        return new PeerCoordinator(util, new byte[20], mi.getInfoHash(), mi, st, null, null,
                                   new FixedBWL(overUpLimit));
    }

    /**
     * A bandwidth listener reporting no limit, or the upload limit exceeded, so the unchoke
     * can be made to take its early path on purpose.
     */
    private static class FixedBWL implements BandwidthListener {

        /** Whether this listener reports snark over its upload limit. */
        private final boolean _overUpLimit;

        /**
         * @param overUpLimit true to report the upload limit exceeded
         */
        FixedBWL(boolean overUpLimit) {
            _overUpLimit = overUpLimit;
        }

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
            return _overUpLimit;
        }

        @Override
        public boolean overDownBWLimit() {
            return false;
        }
    }

    /**
     * A socketless peer on the fixture torrent, with its choke and interest flags set
     * directly.
     *
     * @param mi the torrent the peer is on
     * @param n the peer number, used as its id so peers sort into a known order
     * @param interested true if the peer wants pieces from us
     * @return a socketless peer that is choking us
     * @throws Throwable on peer id failure
     */
    private static Peer peer(MetaInfo mi, int n, boolean interested) throws Throwable {
        byte[] id = new byte[32];
        id[0] = (byte) n;
        PeerID pID = new PeerID(id, (I2PSnarkUtil) null);
        Peer peer = new Peer(pID, new byte[20], mi.getInfoHash(), mi);
        PeerConnectionOut out =
                new PeerConnectionOut(peer, new DataOutputStream(new ByteArrayOutputStream()));
        PeerState state = new PeerState(peer, null, new FixedBWL(false), mi, null, out);
        state.interested = interested;
        peer.state = state;
        return peer;
    }

    /**
     * The count is a recount, not a running total: two passes over the same peers report the
     * same number, where the checker's addition on top used to grow it every pass.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testCountIsARecountNotADelta() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi, false);
        try {
            pc.peers.add(peer(mi, 1, true));
            pc.peers.add(peer(mi, 2, true));
            pc.peers.add(peer(mi, 3, true));
            // no slot free, so nothing is unchoked and the count is purely what is waiting
            pc.setUploaders(PeerCoordinator.MAX_UPLOADERS, PeerCoordinator.MAX_UPLOADERS);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
                assertEquals(3, pc.getInterestedAndChoking());
                pc.unchokePeer(Collections.<Peer>emptySet());
                assertEquals(3, pc.getInterestedAndChoking());
                pc.unchokePeer(Collections.<Peer>emptySet());
                assertEquals(3, pc.getInterestedAndChoking());
            }
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer choked earlier on this same pass is waiting for a slot like any other, so it is
     * counted even though it is not eligible to be unchoked again until the next pass.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testPeerChokedThisPassIsStillCounted() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi, false);
        try {
            Peer choked = peer(mi, 1, true);
            pc.peers.add(choked);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.singleton(choked));
            }
            assertEquals(1, pc.getInterestedAndChoking());
            assertTrue(choked.isChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * Over the upload limit the unchoke opens no slot, but it still recounts, so the count
     * the choke cascade reads does not climb for as long as we stay over the limit.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testCountIsFreshOverTheUploadLimit() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi, true);
        try {
            Peer one = peer(mi, 1, true);
            Peer two = peer(mi, 2, true);
            pc.peers.add(one);
            pc.peers.add(two);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertEquals(2, pc.getInterestedAndChoking());
            assertTrue(one.isChoking());
            assertTrue(two.isChoking());
            assertEquals(0, pc.getUploaders());
            // and it follows the state down rather than only ever going up
            two.state.interested = false;
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertEquals(1, pc.getInterestedAndChoking());
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer the pass unchoked stops being counted, and a peer that does not want anything
     * from us was never counted at all.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testUnchokedAndUninterestedPeersAreNotCounted() throws Throwable {
        MetaInfo mi = metainfo();
        PeerCoordinator pc = coordinator(mi, false);
        try {
            Peer wants = peer(mi, 1, true);
            Peer idle = peer(mi, 2, false);
            pc.peers.add(wants);
            pc.peers.add(idle);
            synchronized (pc.peers) {
                pc.unchokePeer(Collections.<Peer>emptySet());
            }
            assertFalse(wants.isChoking());
            assertEquals(0, pc.getInterestedAndChoking());
        } finally {
            pc.halt();
        }
    }
}
