package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.i2p.crypto.SHA1;
import org.junit.Test;

/**
 * Tests for <code>Storage.digestPiece</code>, the windowed hashing the
 * creation-time hash pass uses. A piece is hashed in windows of at most
 * <code>buf.length</code> bytes so a 64 MB piece never needs a 64 MB buffer, and
 * the windows must tile the piece exactly, in order, with a short final window:
 * a window skipped, repeated, or read out of order hashes the wrong bytes, and
 * the whole torrent is then re-downloaded because the hash check rejects every
 * file.
 *
 * @since 0.9.72+
 */
public class DigestWindowTest {

    /**
     * A fixture group for the windowed piece hash; it holds no state.
     */
    public DigestWindowTest() {}

    /** The 20-byte hash length every piece contributes. */
    private static final int HASH_LEN = 20;

    /**
     * A reader over one piece held in memory.
     *
     * @param data the piece's bytes
     * @return a reader copying windows of data into the caller's buffer
     */
    private static Storage.PieceWindowReader pieceSource(final byte[] data) {
        return (piece, buf, begin, length) -> {
            System.arraycopy(data, begin, buf, 0, length);
            return length;
        };
    }

    /**
     * A reader over a whole torrent of fixed-size pieces, so a test can hash
     * several pieces into one output array as the creation pass does.
     *
     * @param data the torrent's bytes
     * @param pieceSize the size of every piece in data
     * @return a reader copying windows of the given piece into the buffer
     */
    private static Storage.PieceWindowReader torrentSource(final byte[] data, final int pieceSize) {
        return (piece, buf, begin, length) -> {
            System.arraycopy(data, piece * pieceSize + begin, buf, 0, length);
            return length;
        };
    }

    /**
     * The SHA-1 of a byte array, computed independently of the code under test.
     *
     * @param data the bytes to hash
     * @return the 20-byte digest
     */
    private static byte[] sha1(byte[] data) {
        MessageDigest digest = SHA1.getInstance();
        digest.update(data);
        return digest.digest();
    }

    /**
     * Deterministic filler, so a fixture is reproducible across runs.
     *
     * @param len how many bytes to build
     * @return bytes counting up in a pattern seeded by len
     */
    private static byte[] data(int len) {
        byte[] rv = new byte[len];
        for (int i = 0; i < len; i++) {
            rv[i] = (byte) ((i * 31 + len) & 0xff);
        }
        return rv;
    }

    /**
     * Hash a piece and return the 20 bytes written.
     *
     * @param data the piece's bytes
     * @param window the buffer size, which is the window size
     * @return the 20-byte hash the windowed pass produced
     * @throws Exception if the piece cannot be hashed
     */
    private static byte[] digestOf(byte[] data, int window) throws Exception {
        byte[] buf = new byte[window];
        byte[] hashes = new byte[HASH_LEN];
        Storage.digestPiece(SHA1.getInstance(), pieceSource(data), 0, data.length, buf, hashes, 0);
        return hashes;
    }

    /**
     * A piece that fits in one window hashes to the same value as the whole
     * array, the case that worked before windowing and must not regress.
     *
     * @throws Exception if the fixture piece cannot be hashed
     */
    @Test
    public void testSingleWindowMatchesWholePiece() throws Exception {
        byte[] data = data(1000);
        assertArrayEquals(sha1(data), digestOf(data, 4096));
    }

    /**
     * A piece that is an exact multiple of the window hashes the same as the
     * whole array: the loop must stop at the boundary, not read a zero-filled
     * extra window.
     *
     * @throws Exception if the fixture piece cannot be hashed
     */
    @Test
    public void testExactMultipleWindows() throws Exception {
        byte[] data = data(4096);
        assertArrayEquals(sha1(data), digestOf(data, 1024));
    }

    /**
     * A piece with a partial tail window hashes the same as the whole array: the
     * short final window must be read at its own length, not the full window.
     *
     * @throws Exception if the fixture piece cannot be hashed
     */
    @Test
    public void testPartialTailWindow() throws Exception {
        byte[] data = data(4096 + 452);
        assertArrayEquals(sha1(data), digestOf(data, 1024));
    }

    /**
     * The degenerate window of one byte still produces the right hash, so the
     * window size is never assumed to divide the piece.
     *
     * @throws Exception if the fixture piece cannot be hashed
     */
    @Test
    public void testWindowOfOne() throws Exception {
        byte[] data = data(64);
        assertArrayEquals(sha1(data), digestOf(data, 1));
    }

    /**
     * A zero-length piece hashes as the empty string and reads nothing, the case
     * an empty torrent's single piece hits.
     *
     * @throws Exception if the piece cannot be hashed
     */
    @Test
    public void testZeroLengthPiece() throws Exception {
        byte[] hashes = new byte[HASH_LEN];
        Storage.digestPiece(SHA1.getInstance(), pieceSource(new byte[0]), 0, 0, new byte[16],
                            hashes, 0);
        assertArrayEquals(sha1(new byte[0]), hashes);
    }

    /**
     * The windows tile the piece exactly: contiguous, in order, none longer than
     * the buffer, and the last one short.
     *
     * @throws Exception if the piece cannot be hashed
     */
    @Test
    public void testWindowsTileThePiece() throws Exception {
        final List<String> calls = new ArrayList<>();
        Storage.PieceWindowReader recorder = (piece, buf, begin, length) -> {
            calls.add(piece + ":" + begin + ":" + length);
            Arrays.fill(buf, 0, length, (byte) piece);
            return length;
        };
        byte[] buf = new byte[700];
        Storage.digestPiece(SHA1.getInstance(), recorder, 3, 5000, buf, new byte[HASH_LEN], 0);
        assertEquals(
                Arrays.asList(
                        "3:0:700", "3:700:700", "3:1400:700", "3:2100:700", "3:2800:700",
                        "3:3500:700", "3:4200:700", "3:4900:100"),
                calls);
    }

    /**
     * At a large piece size the windows cover the piece exactly once, without
     * materializing it: 8 MiB in 256 KiB windows is 32 windows, contiguous and
     * in order, which is the shape a 64 MB piece produces at the shipped window.
     *
     * @throws Exception if the piece cannot be hashed
     */
    @Test
    public void testWindowsCoverALargePieceOnce() throws Exception {
        final int window = 256 * 1024;
        final int pieceLength = 8 * 1024 * 1024;
        final long[] covered = new long[pieceLength / window];
        final int[] count = new int[pieceLength / window];
        Storage.PieceWindowReader counter = (piece, buf, begin, length) -> {
            assertTrue("window starts past the piece: " + begin, begin < pieceLength);
            assertTrue("window overruns the piece: " + begin + " + " + length,
                       begin + length <= pieceLength);
            assertTrue("window longer than the buffer: " + length, length <= buf.length);
            int idx = begin / window;
            covered[idx] += length;
            count[idx]++;
            return length;
        };
        Storage.digestPiece(SHA1.getInstance(), counter, 0, pieceLength, new byte[window],
                            new byte[HASH_LEN], 0);
        for (int i = 0; i < covered.length; i++) {
            assertEquals("window " + i + " covered twice", 1, count[i]);
            assertEquals("window " + i + " not covered once", window, covered[i]);
        }
    }

    /**
     * The creation pass writes every piece's hash into one array at 20 * piece,
     * so each slot must hold that piece's own digest.
     *
     * @throws Exception if a piece cannot be hashed
     */
    @Test
    public void testHashesLandAtTheirOwnOffsets() throws Exception {
        int pieceSize = 1000;
        int pieces = 4;
        byte[] torrent = data(pieceSize * pieces);
        byte[] hashes = new byte[HASH_LEN * pieces];
        byte[] buf = new byte[300];
        Storage.PieceWindowReader reader = torrentSource(torrent, pieceSize);
        for (int i = 0; i < pieces; i++) {
            Storage.digestPiece(SHA1.getInstance(), reader, i, pieceSize, buf, hashes,
                                HASH_LEN * i);
        }
        for (int i = 0; i < pieces; i++) {
            byte[] expect = sha1(Arrays.copyOfRange(torrent, i * pieceSize, (i + 1) * pieceSize));
            byte[] got = Arrays.copyOfRange(hashes, i * HASH_LEN, (i + 1) * HASH_LEN);
            assertArrayEquals("piece " + i, expect, got);
        }
    }

    /**
     * A window read short is an error, not a hash over whatever the buffer
     * already held: a silent short read would corrupt one piece's hash and send
     * the whole file back for re-download.
     *
     * @throws Exception if the piece cannot be hashed
     */
    @Test
    public void testShortReadThrows() throws Exception {
        Storage.PieceWindowReader shortReader = (piece, buf, begin, length) -> length - 1;
        try {
            Storage.digestPiece(SHA1.getInstance(), shortReader, 7, 100, new byte[16],
                                new byte[HASH_LEN], 0);
            fail("a short window read must not hash a partial piece");
        } catch (IOException expected) {
            // the piece is refused, and the caller turns it into an IOException
            assertTrue(expected.getMessage().contains("piece 7"));
        }
    }

    /**
     * The reader is never asked for more than the buffer holds, so no window can
     * write past the end of the reused buffer.
     *
     * @throws Exception if the piece cannot be hashed
     */
    @Test
    public void testReaderNeverAskedForMoreThanTheBuffer() throws Exception {
        final int window = 64;
        Storage.PieceWindowReader checker = (piece, buf, begin, length) -> {
            assertTrue("window of " + length + " into a " + buf.length + " byte buffer",
                       length <= buf.length);
            Arrays.fill(buf, 0, length, (byte) 1);
            return length;
        };
        Storage.digestPiece(SHA1.getInstance(), checker, 0, 1000, new byte[window],
                            new byte[HASH_LEN], 0);
    }
}
