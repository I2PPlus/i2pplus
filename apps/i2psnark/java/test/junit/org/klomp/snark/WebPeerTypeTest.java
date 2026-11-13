package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins that a BEP 19 web seed says it is one, and that PEX therefore does not offer it to
 * peers. A web seed's peer id carries a hash of its URL, not an I2P destination, so a peer
 * told about a web seed by PEX is handed a hash it has no way to connect to, and spends a
 * destination lookup and a dial failing.
 *
 * @since 0.9.72+
 */
public class WebPeerTypeTest {

    /** Pieces in the fixture torrent. */
    private static final int PIECES = 16;

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** The web seed URL the fixture peer id is hashed from. */
    private static final String SEED_URL = "http://seed.example.i2p/files/test.file";

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public WebPeerTypeTest() {}

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
     * @param util the snark util the coordinator is built with
     * @return a coordinator whose every piece of the fixture torrent is wanted
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator(I2PSnarkUtil util) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        Storage st = new Storage(util, new File("/tmp/snark-webpeer-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A bandwidth listener that reports nothing, so the coordinator's rate decisions
     * stay neutral.
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
     * A web seed says it is one. It used to answer false, which is how it ended up being
     * advertised by PEX at all.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testWebSeedKnowsWhatItIs() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            Peer seed = webPeer(util, pc);
            assertTrue(seed.isWebPeer());
        } finally {
            pc.halt();
        }
    }

    /**
     * A normal peer is not a web seed, so the base answer still stands and the web seed
     * override is the only thing that changes.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testNormalPeerIsNotAWebSeed() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            assertFalse(normalPeer(util, 1).isWebPeer());
        } finally {
            pc.halt();
        }
    }

    /**
     * PEX does not offer a web seed to a peer, and does offer a normal peer.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testPEXSkipsWebSeeds() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            Peer told = normalPeer(util, 1);
            assertFalse(PeerCoordinator.pexCandidate(webPeer(util, pc), told));
            assertTrue(PeerCoordinator.pexCandidate(normalPeer(util, 2), told));
        } finally {
            pc.halt();
        }
    }

    /**
     * A peer is never told about itself, whatever kind of peer it is.
     *
     * @throws Throwable on fixture construction failure
     */
    @Test
    public void testNeverToldAboutItself() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        PeerCoordinator pc = coordinator(util);
        try {
            Peer one = normalPeer(util, 1);
            assertFalse(PeerCoordinator.pexCandidate(one, one));
        } finally {
            pc.halt();
        }
    }

    /**
     * A BEP 19 web seed over the fixture torrent, with the peer id the tracker code builds
     * for one: a hash of the URL, not a destination.
     *
     * @param util the snark util
     * @param pc the coordinator that owns the seed
     * @return the web seed
     * @throws Throwable on metainfo or peer id failure
     */
    private static Peer webPeer(I2PSnarkUtil util, PeerCoordinator pc) throws Throwable {
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        byte[] urlHash = new byte[32];
        byte[] url = SEED_URL.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(url, 0, urlHash, 0, Math.min(url.length, 32));
        PeerID pID = new PeerID(urlHash, util);
        return new WebPeer(pc, new URI(SEED_URL), pID, mi);
    }

    /**
     * A normal peer over the fixture torrent.
     *
     * @param util the snark util
     * @param n the peer number, used as its id
     * @return a peer with no socket
     * @throws Throwable on peer id or metainfo failure
     */
    private static Peer normalPeer(I2PSnarkUtil util, int n) throws Throwable {
        byte[] id = new byte[32];
        id[0] = (byte) n;
        PeerID pID = new PeerID(id, (I2PSnarkUtil) null);
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        return new Peer(pID, new byte[20], mi.getInfoHash(), mi);
    }
}
