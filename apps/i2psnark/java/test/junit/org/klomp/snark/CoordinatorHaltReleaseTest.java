package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins what {@link PeerCoordinator#halt()} releases and what {@link
 * PeerCoordinator#restart()} rebuilds. A stopped torrent used to keep its whole
 * wanted-piece graph: one Piece object per piece still wanted, each with its own set
 * of peers, plus the PEX peer set and the swarm tally. At Storage.MAX_PIECES that is
 * the bulk of the memory a paused torrent holds, and it was never freed until the
 * torrent was removed.
 *
 * @since 0.9.72+
 */
public class CoordinatorHaltReleaseTest {

    /** Pieces in the fixture torrent, all of them wanted. */
    private static final int PIECES = 64;

    /** Piece length in bytes. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public CoordinatorHaltReleaseTest() {}

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
     * A coordinator over a torrent with nothing on disk, so every piece is wanted.
     * The caller must halt it to stop the checker timer.
     *
     * @return a coordinator whose every piece of the fixture torrent is wanted
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        Storage st = new Storage(util, new File("/tmp/snark-halt-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * Stopping the torrent drops the wanted-piece graph, which is what a paused
     * large torrent was holding on to.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testHaltDropsTheWantedPieces() throws Throwable {
        PeerCoordinator pc = coordinator();
        assertEquals(PIECES, pc.wantedPieceCount());
        pc.halt();
        assertEquals(0, pc.wantedPieceCount());
    }

    /**
     * Restarting rebuilds the wanted set from storage, so clearing it on halt does
     * not leave a resumed torrent wanting nothing.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testRestartRebuildsTheWantedPieces() throws Throwable {
        PeerCoordinator pc = coordinator();
        pc.halt();
        assertEquals(0, pc.wantedPieceCount());
        pc.restart();
        assertEquals(PIECES, pc.wantedPieceCount());
        assertEquals((long) PIECES * PIECE_LENGTH, pc.getNeededLength());
        pc.halt();
    }

    /**
     * The byte count the console shows for a paused torrent survives the halt, so
     * stopping a download does not make its remaining size read as zero.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testHaltKeepsTheNeededLength() throws Throwable {
        PeerCoordinator pc = coordinator();
        long before = pc.getNeededLength();
        assertEquals((long) PIECES * PIECE_LENGTH, before);
        pc.halt();
        assertEquals(before, pc.getNeededLength());
    }
}
