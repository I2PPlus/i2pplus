package org.rrd4j.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;

/**
 * Tests for the bounded heap area an NIO backend batches its writes through.
 *
 * <p>The staging area used to be one fixed-name file under the system temporary directory, shared
 * by every backend in the process. Its ownership belonged to whichever process touched it first, so
 * a second router running as a different user could not open it for writing at all, and every
 * writable database in that router failed to open - 33 of 33 persistent graphs at once. Staging is
 * heap now, sized from the free heap, so no filesystem permission can take a graph down.
 *
 * <p>What is pinned here is the budget arithmetic, which decides between staging, publishing early
 * to make room, and publishing on its own; and the two properties that make the heap area safe to
 * use at all: a published block reaches the file, and the bytes it held leave the accounting so the
 * budget is not spent twice.
 */
public class RrdNioStagingTest {

    /**
     * Runs one throwaway NIO database at a time, created fresh for each test.
     */
    public RrdNioStagingTest() {}

    /** Name of the datasource the throwaway database holds. */
    private static final String DS_NAME = "series";
    /** Step of the throwaway database, in seconds. */
    private static final long STEP = 300;
    /** Rows in the throwaway database's single archive. */
    private static final int ROWS = 40;
    /** Wall-clock second the throwaway database ends at. */
    private static final long END = 1700000000L;

    /** The database under test, on the NIO backend. */
    private RrdDb db;
    /** The file the database is stored in. */
    private File file;

    /**
     * Creates a throwaway NIO-backed database in a temporary file.
     *
     * @throws IOException if the file cannot be created
     */
    @Before
    public void setUp() throws IOException {
        file = File.createTempFile("rrd-nio-staging-test", ".jrb");
        // the def's own path is what the builder uses, so the temp file's name goes there;
        // the empty placeholder is removed so the database is created, not opened
        file.delete();
        RrdDef def = new RrdDef(file.getAbsolutePath());
        def.setStartTime(END - (long) ROWS * STEP);
        def.setStep(STEP);
        def.addDatasource(new DsDef(DS_NAME, DsType.GAUGE, STEP * 10, 0, Double.MAX_VALUE));
        def.addArchive(new ArcDef(ConsolFun.AVERAGE, 0.5, 1, ROWS));
        db = RrdDb.getBuilder().setRrdDef(def)
                             .setBackendFactory(RrdBackendFactory.getFactory("NIO"))
                             .build();
    }

    /**
     * Closes the database, clears any staging budget a test pinned, and removes the file.
     *
     * @throws IOException if the database cannot be closed
     */
    @After
    public void tearDown() throws IOException {
        RrdNioBackend.setStagingBudgetBytes(0);
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (file != null) {
            file.delete();
        }
    }

    ///////////// the budget

    /**
     * A JVM with less than the floor of free heap does not stage at all: the point of the budget is
     * to stop a stalled flush becoming an allocation failure, so a heap that cannot spare the floor
     * publishes every write as it comes.
     */
    @Test
    public void testHeapBelowTheFloorStagesNothing() {
        assertEquals(0, RrdNioBackend.stagingBudgetBytes(RrdNioBackend.MIN_FREE_HEAP_BYTES - 1));
        assertEquals(0, RrdNioBackend.stagingBudgetBytes(0));
        assertEquals(0, RrdNioBackend.stagingBudgetBytes(-1));
    }

    /**
     * Above the floor the budget is a fixed fraction of what the JVM can still take, so a small
     * heap stages a small batch and a large one stages a large batch without either being asked how
     * many databases happen to be open.
     */
    @Test
    public void testBudgetTracksFreeHeap() {
        long free = 16L * 1024 * 1024;
        assertEquals(free / RrdNioBackend.STAGING_HEAP_DIVISOR, RrdNioBackend.stagingBudgetBytes(free));
        assertTrue(RrdNioBackend.stagingBudgetBytes(free * 4) > RrdNioBackend.stagingBudgetBytes(free));
    }

    /**
     * A server heap does not get an unbounded staging area. What is staged is the delta being
     * written rather than the file, so the ceiling costs nothing in the common case and only bites
     * when a flush has stopped running - which is exactly when a huge reservation would be wasted.
     */
    @Test
    public void testBudgetIsCappedOnALargeHeap() {
        assertEquals(RrdNioBackend.MAX_STAGING_BYTES,
                     RrdNioBackend.stagingBudgetBytes(Long.MAX_VALUE / 2));
        assertEquals(RrdNioBackend.MAX_STAGING_BYTES,
                     RrdNioBackend.stagingBudgetBytes((long) RrdNioBackend.MAX_STAGING_BYTES
                                                      * RrdNioBackend.STAGING_HEAP_DIVISOR));
    }

    /**
     * A block that fits the budget exactly is staged; one byte more is not. The boundary is what
     * decides whether a write is batched or published on its own, so it has to be the stated one.
     */
    @Test
    public void testBudgetBoundaryIsInclusive() {
        assertFalse(RrdNioBackend.mustPublishBeforeStaging(100, 100, 200));
        assertTrue(RrdNioBackend.mustPublishBeforeStaging(101, 100, 200));
        assertTrue(RrdNioBackend.mustPublishBeforeStaging(0, 1, 0));
        // an empty block spends nothing, so it is staged even with the budget shut off
        assertFalse(RrdNioBackend.mustPublishBeforeStaging(0, 0, 0));
    }

    ///////////// staging into the file

    /**
     * A staged block is not in the file until the flush, and is in it afterwards - at the offset it
     * was given, not appended.
     *
     * @throws IOException if the staged block cannot be written
     */
    @Test
    public void testStagedBlockReachesTheFileOnFlush() throws IOException {
        RrdNioBackend backend = (RrdNioBackend) db.getRrdBackend();
        long tail = backend.getLength() - 16;
        byte[] block = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        backend.bufferStatFile(ByteBuffer.wrap(block), tail);
        assertEquals(block.length, RrdNioBackend.stagedBytes());
        // the archive tail is initialised to NaN, not zeros, so what is pinned is that the
        // staged bytes are not there yet, not what is there instead
        assertFalse("the staged block reached the file before the flush",
                    Arrays.equals(readAt(tail, 16), block));

        backend.flushBufferedStatFiles();

        assertArrayEquals(block, readAt(tail, 16));
        // the heap the block held is out of the accounting, so the budget is not spent twice
        assertEquals(0, RrdNioBackend.stagedBytes());
    }

    /**
     * A block too large for the budget is published on its own rather than pushing the staging area
     * past it, and it lands in the file without waiting for a flush.
     *
     * @throws IOException if the block cannot be written
     */
    @Test
    public void testBlockOverTheBudgetIsPublishedAtOnce() throws IOException {
        RrdNioBackend.setStagingBudgetBytes(8);
        RrdNioBackend backend = (RrdNioBackend) db.getRrdBackend();
        long tail = backend.getLength() - 16;
        byte[] block = "fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        backend.bufferStatFile(ByteBuffer.wrap(block), tail);
        assertEquals(0, RrdNioBackend.stagedBytes());
        assertArrayEquals(block, readAt(tail, 16));
    }

    /**
     * A budget of zero or less hands the decision back to the free-heap calculation rather than
     * shutting staging off, so an embedder that pinned a budget can give it back again.
     *
     * @throws IOException if the block cannot be staged
     */
    @Test
    public void testNonPositiveBudgetReturnsToFreeHeapSizing() throws IOException {
        RrdNioBackend.setStagingBudgetBytes(-1);
        RrdNioBackend backend = (RrdNioBackend) db.getRrdBackend();
        long tail = backend.getLength() - 8;
        byte[] block = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
        backend.bufferStatFile(ByteBuffer.wrap(block), tail);
        // a test JVM has far more free heap than the floor, so the block is staged, not published
        assertEquals(block.length, RrdNioBackend.stagedBytes());
        backend.flushBufferedStatFiles();
        assertArrayEquals(block, readAt(tail, 8));
    }

    /**
     * A block that no longer fits its mapping is dropped and reported, and the rest of the batch
     * still lands. Throwing instead would end the scheduled flush that runs this, which is how a
     * single bad offset once stopped every later write to the file.
     *
     * @throws IOException if a block cannot be staged
     */
    @Test
    public void testOutOfRangeBlockDoesNotCostTheRestOfTheBatch() throws IOException {
        RrdNioBackend backend = (RrdNioBackend) db.getRrdBackend();
        long length = backend.getLength();
        byte[] good = "good-block".getBytes(StandardCharsets.US_ASCII);
        byte[] bad = "bad-block!".getBytes(StandardCharsets.US_ASCII);
        backend.bufferStatFile(ByteBuffer.wrap(good), length - 10);
        backend.bufferStatFile(ByteBuffer.wrap(bad), length);
        assertEquals(good.length + bad.length, RrdNioBackend.stagedBytes());

        backend.flushBufferedStatFiles();

        assertArrayEquals(good, readAt(length - 10, 10));
        assertEquals(0, RrdNioBackend.stagedBytes());
    }

    /**
     * Closing the database publishes what is still staged, so a batch never has to wait for its
     * period to be seen in the file.
     *
     * @throws IOException if a block cannot be staged
     */
    @Test
    public void testClosePublishesWhatIsStaged() throws IOException {
        RrdNioBackend backend = (RrdNioBackend) db.getRrdBackend();
        long tail = backend.getLength() - 12;
        byte[] block = "closed-batch".getBytes(StandardCharsets.US_ASCII);
        backend.bufferStatFile(ByteBuffer.wrap(block), tail);
        db.close();
        assertArrayEquals(block, readAt(tail, 12));
        assertEquals(0, RrdNioBackend.stagedBytes());
    }

    /**
     * Reads raw bytes back out of the file behind the database.
     *
     * @param offset where in the file to start
     * @param length how many bytes to read
     * @return the bytes at that offset
     * @throws IOException if the file cannot be read
     */
    private byte[] readAt(long offset, int length) throws IOException {
        byte[] buf = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(buf);
        }
        return buf;
    }
}
