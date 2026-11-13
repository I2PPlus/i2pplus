package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Pins the lazy info-dictionary copy in {@link MagnetState}. A torrent that starts
 * out complete used to hold a second full copy of its bencoded info dictionary for
 * the whole life of the torrent, 20 bytes of piece hash per piece, and it survived
 * stopping the torrent. Now the copy is made only when a peer asks for a metadata
 * chunk, and {@link MagnetState#release()} drops it again while the torrent is
 * stopped, without disturbing the accumulator of a fetch still in progress.
 *
 * @since 0.9.72+
 */
public class MagnetStateReleaseTest {

    /** Enough pieces that the info dictionary spans more than one metadata chunk. */
    private static final int PIECES = 1000;

    /** Piece length in bytes, chosen so the piece hashes dominate the dictionary. */
    private static final int PIECE_LENGTH = 16 * 1024;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public MagnetStateReleaseTest() {}

    /**
     * A bencoded torrent with the given number of pieces, whose info dictionary is
     * dominated by the 20-byte piece hashes.
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
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = (byte) i;
        }
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return rv;
    }

    /**
     * The fixture torrent's info dictionary, big enough to need two metadata chunks.
     *
     * @return a parsed MetaInfo
     * @throws Throwable on bencode parse failure
     */
    private static MetaInfo metaInfo() throws Throwable {
        return new MetaInfo(new ByteArrayInputStream(torrentBytes(PIECES)));
    }

    /**
     * A torrent that starts out complete serves the same chunk bytes as the
     * dictionary MetaInfo holds, whether or not the copy has been released, so
     * making the copy lazy does not change what peers receive.
     *
     * @throws Throwable on metainfo parse failure
     */
    @Test
    public void testChunksMatchTheInfoDictionary() throws Throwable {
        MetaInfo mi = metaInfo();
        byte[] info = mi.getInfoBytes();
        int chunks = (info.length + MagnetState.CHUNK_SIZE - 1) / MagnetState.CHUNK_SIZE;
        assertTrue("the fixture must span more than one chunk", chunks > 1);

        MagnetState state = new MagnetState(mi.getInfoHash(), mi);
        for (int chunk = 0; chunk < chunks; chunk++) {
            assertChunk(info, chunk, state.getChunk(chunk));
        }
        state.release();
        for (int chunk = 0; chunk < chunks; chunk++) {
            assertChunk(info, chunk, state.getChunk(chunk));
        }
    }

    /**
     * A completed magnet keeps its metadata chunks serving after release(), which is
     * what lets a stopped torrent free the copy and a restarted one keep answering
     * peers without re-fetching the metadata.
     *
     * @throws Throwable on metainfo parse or chunk save failure
     */
    @Test
    public void testCompletedMagnetStillServesAfterRelease() throws Throwable {
        MetaInfo mi = metaInfo();
        byte[] info = mi.getInfoBytes();
        MagnetState state = new MagnetState(mi.getInfoHash(), null);
        state.initialize(info.length);
        int first = Math.min(MagnetState.CHUNK_SIZE, info.length);
        state.saveChunk(0, info, 0, first);
        state.saveChunk(1, info, MagnetState.CHUNK_SIZE, info.length - MagnetState.CHUNK_SIZE);
        state.buildMetaInfo();

        state.release();
        assertChunk(info, 1, state.getChunk(1));
    }

    /**
     * release() leaves the accumulator of a fetch that is still in progress alone, so
     * a stopped torrent mid-magnet-fetch does not lose the chunks it has already paid
     * to download.
     *
     * @throws Throwable on metainfo parse or chunk save failure
     */
    @Test
    public void testReleaseKeepsAnIncompleteAccumulator() throws Throwable {
        MetaInfo mi = metaInfo();
        byte[] info = mi.getInfoBytes();
        MagnetState state = new MagnetState(mi.getInfoHash(), null);
        state.initialize(info.length);
        state.saveChunk(0, info, 0, MagnetState.CHUNK_SIZE);

        state.release();

        // the second chunk still lands in the same accumulator, and the whole
        // dictionary still decodes to the right info hash
        boolean done = state.saveChunk(1, info, MagnetState.CHUNK_SIZE,
                                       info.length - MagnetState.CHUNK_SIZE);
        assertTrue(done);
        MetaInfo built = state.buildMetaInfo();
        assertArrayEquals(mi.getInfoHash(), built.getInfoHash());
    }

    /**
     * Compare one metadata chunk against the slice of the info dictionary it must hold.
     *
     * @param info the whole bencoded info dictionary
     * @param chunk the chunk number to compare
     * @param got the bytes the state served
     */
    private static void assertChunk(byte[] info, int chunk, byte[] got) {
        int off = chunk * MagnetState.CHUNK_SIZE;
        int len = Math.min(MagnetState.CHUNK_SIZE, info.length - off);
        assertEquals("chunk length", len, got.length);
        byte[] want = new byte[len];
        System.arraycopy(info, off, want, 0, len);
        assertArrayEquals(want, got);
    }
}
