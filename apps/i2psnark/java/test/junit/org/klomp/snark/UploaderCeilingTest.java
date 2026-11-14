package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Pins the uploader count the peer checker compares its own count against before it starts
 * choking peers to make room. It used to read {@code allowedUploaders()}, which answers
 * "may one more peer be unchoked" and so always sits one above the count the torrent
 * holds; comparing the count against it is never true, so the whole choke cascade -
 * uninterested peer, peer choking us, snubbing peer, peer that sent nothing - was inert
 * except while the upload bandwidth limit was exceeded. Uninterested peers kept holding
 * upload slots that interested peers were waiting for.
 *
 * @since 0.9.72+
 */
public class UploaderCeilingTest {

    /** Piece length in bytes for the fixture torrent. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /** Pieces in the fixture torrent. */
    private static final int PIECES = 64;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public UploaderCeilingTest() {}

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
     * A coordinator over a torrent with nothing on disk and no coordinator listener, so
     * the global upload limit is never reported exceeded. The caller must halt it to stop
     * the checker timer.
     *
     * @return a coordinator with no uploaders yet
     * @throws Throwable on metainfo or storage failure
     */
    private static PeerCoordinator coordinator() throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
        Storage st = new Storage(util, new File("/tmp/snark-ceiling-test"), mi, null, false);
        return new PeerCoordinator(
                util, new byte[20], mi.getInfoHash(), mi, st, null, null, new NullBWL());
    }

    /**
     * A bandwidth listener that reports no limit, so nothing else narrows the ceiling.
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
     * Away from the global upload limit the ceiling is the per-torrent cap, whatever the
     * current count is, so the count can reach it.
     */
    @Test
    public void testCeilingIsTheHardCap() {
        int cap = PeerCoordinator.MAX_UPLOADERS;
        assertEquals(cap, PeerCoordinator.uploaderCeiling(false, 0, cap));
        assertEquals(cap, PeerCoordinator.uploaderCeiling(false, 3, cap));
        assertEquals(cap, PeerCoordinator.uploaderCeiling(false, cap - 1, cap));
        assertEquals(cap, PeerCoordinator.uploaderCeiling(false, cap, cap));
    }

    /**
     * The count can actually meet the ceiling, which is what makes the cascade reachable.
     * The value the checker used before sits one above the count for every count below the
     * cap, so the count could never meet it.
     */
    @Test
    public void testTheCountCanMeetTheCeiling() {
        int cap = PeerCoordinator.MAX_UPLOADERS;
        for (int up = 0; up <= cap; up++) {
            int old = Math.min(up + 1, cap);
            if (up < cap) {
                assertNotEquals(up, old);
            }
        }
        assertEquals(cap, PeerCoordinator.uploaderCeiling(false, cap, cap));
        assertTrue(PeerCheckerTask.makesRoom(cap, cap, 1));
    }

    /**
     * While the global upload limit is exceeded the ceiling sits one below the current
     * count, so the checker keeps choking until the count drops. This is the one case that
     * already worked, and it has to keep working.
     */
    @Test
    public void testOverTheGlobalLimitTheCeilingIsBelowTheCount() {
        int cap = PeerCoordinator.MAX_UPLOADERS;
        assertEquals(4, PeerCoordinator.uploaderCeiling(true, 5, cap));
        assertEquals(cap - 1, PeerCoordinator.uploaderCeiling(true, cap, cap));
        assertTrue(PeerCheckerTask.makesRoom(5, 4, 0));
    }

    /**
     * Room is made at the ceiling when peers are waiting for a slot, and not made below it
     * no matter how many peers are waiting - a torrent below its cap opens a slot instead
     * of taking one.
     */
    @Test
    public void testMakesRoomBoundaries() {
        int cap = PeerCoordinator.MAX_UPLOADERS;
        assertTrue(PeerCheckerTask.makesRoom(cap, cap, 1));
        assertFalse(PeerCheckerTask.makesRoom(cap, cap, 0));
        assertFalse(PeerCheckerTask.makesRoom(cap - 1, cap, 9));
        assertTrue(PeerCheckerTask.makesRoom(cap + 1, cap, 0));
    }

    /**
     * A torrent with no uploaders has the whole cap available and no room to make, and its
     * uploader ceiling is well above the one-slot target its unchoke path uses.
     *
     * @throws Throwable on coordinator construction failure
     */
    @Test
    public void testFreshTorrent() throws Throwable {
        PeerCoordinator pc = coordinator();
        try {
            assertEquals(0, pc.getUploaders());
            assertEquals(PeerCoordinator.MAX_UPLOADERS, pc.uploaderCeiling());
            assertEquals(1, pc.allowedUploaders());
            assertFalse(PeerCheckerTask.makesRoom(pc.getUploaders(), pc.uploaderCeiling(), 0));
        } finally {
            pc.halt();
        }
    }
}
