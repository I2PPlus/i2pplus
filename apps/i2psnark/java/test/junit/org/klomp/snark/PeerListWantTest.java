package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins how many peers a discovery round asks for. Both the tracker announce and the DHT
 * fetch asked for {@code I2PSnarkUtil.MAX_CONNECTIONS}, 300, while a torrent's own
 * connection cap is 4, 10 or 16 by piece count, so the rest of the reply was parsed into
 * peer ids, each with a blocking destination lookup behind it, and never used.
 *
 * @since 0.9.72+
 */
public class PeerListWantTest {

    /** Piece length in bytes for the fixtures. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeerListWantTest() {}

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
     * A coordinator over a torrent with nothing on disk. The caller must halt it to
     * stop the checker timer.
     *
     * @param pieces how many pieces the fixture torrent has
     * @return a coordinator whose every piece of the fixture torrent is wanted
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(int pieces) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(pieces)));
        Storage st = new Storage(util, new File("/tmp/snark-peerlist-test"), mi, null, false);
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
     * The peer count asked for follows the torrent's own connection cap, which is set by
     * piece count: four for a tiny torrent, ten for a small one, sixteen for a modest
     * one.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testFollowsTheTorrentsOwnCap() throws Throwable {
        assertPeerListWant(4, 8);
        assertPeerListWant(10, 20);
        assertPeerListWant(16, 64);
    }

    /**
     * The ask never exceeds the global maximum, and never falls to zero for a torrent
     * with no pieces, so a tracker or DHT reply is always asked for.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testStaysWithinBounds() throws Throwable {
        PeerCoordinator pc = coordinator(1);
        try {
            assertTrue(Integer.toString(pc.peerListWant()), pc.peerListWant() >= 1);
            assertTrue(Integer.toString(pc.peerListWant()),
                       pc.peerListWant() <= I2PSnarkUtil.MAX_CONNECTIONS);
        } finally {
            pc.halt();
        }
    }

    /**
     * The ask is far below the global maximum the code used before, which is the whole
     * point: the addresses past the torrent's own cap cost a peer id and a destination
     * lookup each and are never connected to.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testIsFarBelowTheGlobalMaximum() throws Throwable {
        PeerCoordinator pc = coordinator(64);
        try {
            assertTrue(Integer.toString(pc.peerListWant()),
                       pc.peerListWant() * 8 < I2PSnarkUtil.MAX_CONNECTIONS);
        } finally {
            pc.halt();
        }
    }

    /**
     * Ask for the given count for a torrent of the given piece count.
     *
     * @param want the peer count expected from {@code peerListWant()}
     * @param pieces how many pieces the fixture torrent has
     * @throws Throwable on coordinator construction failure
     */
    private static void assertPeerListWant(int want, int pieces) throws Throwable {
        PeerCoordinator pc = coordinator(pieces);
        try {
            assertEquals(pieces + " pieces", want, pc.peerListWant());
        } finally {
            pc.halt();
        }
    }

    /**
     * A torrent that cannot use another peer still asks for one spare, because the DHT
     * fetch reads a count of one back as "non-seeds only" and a count of zero would
     * lose that.
     */
    @Test
    public void testNoPeersWantedStillAsksForOne() {
        assertEquals(1, TrackerClient.peerListWant(false, 16));
        assertEquals(1, TrackerClient.peerListWant(false, 300));
        assertEquals(1, TrackerClient.peerListWant(false, 0));
    }

    /**
     * When peers are wanted the torrent's own cap is the ask, and a cap of zero or less
     * still asks for one rather than nothing.
     */
    @Test
    public void testPeersWantedUsesTheTorrentsCap() {
        assertEquals(4, TrackerClient.peerListWant(true, 4));
        assertEquals(16, TrackerClient.peerListWant(true, 16));
        assertEquals(1, TrackerClient.peerListWant(true, 0));
        assertEquals(1, TrackerClient.peerListWant(true, -5));
    }
}
