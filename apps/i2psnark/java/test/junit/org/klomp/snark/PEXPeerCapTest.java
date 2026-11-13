package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins the bound on the PEX-advertised peers a torrent remembers. The set was written to
 * with no cap and drained only when the torrent could use another connection, so a
 * torrent sitting at its connection cap kept every peer the swarm ever advertised to it -
 * each one holding a full I2P destination - for the whole life of the torrent.
 *
 * @since 0.9.72+
 */
public class PEXPeerCapTest {

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** Pieces in the fixture torrent, enough for the 16-connection tier. */
    private static final int PIECES = 64;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PEXPeerCapTest() {}

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

    /**
     * A coordinator over a torrent with nothing on disk. The caller must halt it to stop
     * the checker timer.
     *
     * @return a coordinator whose every piece of the fixture torrent is wanted
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        Storage st = new Storage(util, new File("/tmp/snark-pexcap-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A bandwidth listener that reports no limit, so the connection cap is set by the
     * torrent's piece count alone.
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
     * The bound follows the torrent's own peer ask, so a torrent that would ask a tracker
     * for four peers does not retain hundreds of destinations, and one that asks for
     * sixteen retains more.
     */
    @Test
    public void testCapFollowsTheTorrentsAsk() {
        assertEquals(16, PeerCoordinator.pexPeerCap(4));
        assertEquals(40, PeerCoordinator.pexPeerCap(10));
        assertEquals(64, PeerCoordinator.pexPeerCap(16));
        assertTrue(PeerCoordinator.pexPeerCap(16) > PeerCoordinator.pexPeerCap(4));
    }

    /**
     * A tiny torrent still keeps a usable reserve, so a PEX reply that arrives while the
     * torrent holds its full connection set is not thrown away entirely.
     */
    @Test
    public void testCapHasAFloor() {
        assertEquals(16, PeerCoordinator.pexPeerCap(0));
        assertEquals(16, PeerCoordinator.pexPeerCap(1));
        assertEquals(16, PeerCoordinator.pexPeerCap(3));
    }

    /**
     * The bound stays a bound: far below the global connection maximum of 300, so the set
     * cannot grow into the unbounded retention it replaces.
     */
    @Test
    public void testCapStaysSmall() {
        assertTrue(Integer.toString(PeerCoordinator.pexPeerCap(16)),
                   PeerCoordinator.pexPeerCap(16) < I2PSnarkUtil.MAX_CONNECTIONS);
    }

    /**
     * Room is left for the whole set when it is empty, none when it is exactly full, and
     * a negative value once it has been overrun, which the caller treats as full.
     */
    @Test
    public void testRoomAccounting() {
        assertEquals(64, PeerCoordinator.pexPeerRoom(0, 64));
        assertEquals(1, PeerCoordinator.pexPeerRoom(63, 64));
        assertEquals(0, PeerCoordinator.pexPeerRoom(64, 64));
        assertTrue(PeerCoordinator.pexPeerRoom(100, 64) < 0);
    }

    /**
     * The bound a real torrent gets is derived from that torrent's own ask, not from a
     * constant shared by every torrent.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testRealTorrentUsesItsOwnAsk() throws Throwable {
        PeerCoordinator pc = coordinator();
        try {
            assertEquals(16, pc.peerListWant());
            assertEquals(64, PeerCoordinator.pexPeerCap(pc.peerListWant()));
        } finally {
            pc.halt();
        }
    }
}
