package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import net.i2p.util.FileUtil;
import org.junit.Test;

/**
 * Tests for the threshold below which a partial piece is kept in memory rather than a temp
 * file. It is meant to fall when a piece allocation runs the process out of memory, so the
 * pieces after it go to disk instead of trying the allocation that just failed - the constructor
 * used to compare the piece length against the default threshold rather than the reduced one, so
 * the reduction changed nothing but the number in the warning.
 *
 * @since 0.9.72+
 */
public class PartialPieceMemoryTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PartialPieceMemoryTest() {}

    /**
     * Reads the live threshold.
     *
     * @return the piece length up to which pieces are kept in memory, in bytes
     * @throws Exception if the private field cannot be read
     */
    private static int threshold() throws Exception {
        Field f = PartialPiece.class.getDeclaredField("maxInMem");
        f.setAccessible(true);
        return f.getInt(null);
    }

    /**
     * Writes the live threshold.
     *
     * @param len the piece length up to which pieces are kept in memory, in bytes
     * @throws Exception if the private field cannot be written
     */
    private static void setThreshold(int len) throws Exception {
        Field f = PartialPiece.class.getDeclaredField("maxInMem");
        f.setAccessible(true);
        f.setInt(null, len);
    }

    /**
     * The in-memory storage a piece holds, null when it uses a temp file.
     *
     * @param pp the piece to look inside
     * @return the array holding the piece, or null
     * @throws Exception if the private field cannot be read
     */
    private static Object storageOf(PartialPiece pp) throws Exception {
        Field f = PartialPiece.class.getDeclaredField("bs");
        f.setAccessible(true);
        return f.get(pp);
    }

    /**
     * A piece over the reduced threshold is not allocated in memory.
     *
     * @throws Exception if the threshold cannot be read or written
     */
    @Test
    public void testAPieceOverTheThresholdUsesATempFile() throws Exception {
        int saved = threshold();
        File tmp = new File(System.getProperty("java.io.tmpdir"),
                            "snark-partial-mem-" + System.nanoTime());
        assertTrue(tmp.mkdirs());
        try {
            setThreshold(1024);
            PartialPiece pp = new PartialPiece(new Piece(0), 4096, tmp);
            assertNull("a piece over the threshold was allocated in memory", storageOf(pp));
        } finally {
            setThreshold(saved);
            FileUtil.rmdir(tmp, false);
        }
    }

    /**
     * A piece under it still is, so the reduction degrades rather than disables memory use.
     *
     * @throws Exception if the threshold cannot be read or written
     */
    @Test
    public void testAPieceUnderTheThresholdStaysInMemory() throws Exception {
        int saved = threshold();
        try {
            setThreshold(1024);
            PartialPiece pp = new PartialPiece(new Piece(0), 512, null);
            assertNotNull("a piece under the threshold lost its memory", storageOf(pp));
        } finally {
            setThreshold(saved);
        }
    }
}
