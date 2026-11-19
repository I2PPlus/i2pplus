package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;

import org.junit.Test;

/**
 * Tests for the buffer pool behind the three classes that handle one chunk of a piece: the
 * piece writer, the assembler and the reader. ByteCache keys its caches by entry size, so all
 * three get the same object, and the capacity of that object is whatever the last class to
 * ask for it needed - two of them used to ask for a quarter of what the third needed.
 *
 * <p>The chunk size is configurable and the console can change it while a download runs, so
 * the pool has to be found by the size in use now. Each of the three classes used to bind its
 * pool once, in a static field, at class-load time; after a chunk size change they kept
 * comparing request lengths against the size they were loaded with, so no request matched,
 * every block became a fresh array and nothing went back - the exact outcome the pool exists
 * to prevent.
 *
 * @since 0.9.72+
 */
public class BlockCacheSizingTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public BlockCacheSizingTest() {}

    /** A chunk size no other component asks for, so the pool counters below stay readable. */
    private static final int RESIZED = 40 * 1024;

    /** A piece long enough to be file-backed rather than kept in the in-memory buffer. */
    private static final int ASSEMBLY_PIECE = 1024 * 1024 + PartialPiece.SUB_PARTSIZE;

    /** A torrent piece long enough to hold two whole chunks, for the reader case. */
    private static final int SERVED_PIECE = RESIZED * 2;

    /**
     * Every pool ByteCache currently holds, by entry size.
     *
     * @return the live caches
     * @throws Exception if the private field cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, ByteCache> allCaches() throws Exception {
        Field f = ByteCache.class.getDeclaredField("_caches");
        f.setAccessible(true);
        return (Map<Integer, ByteCache>) f.get(null);
    }

    /**
     * The pipeline bounds, chunk size and per-request cap as they were before a case moved
     * them, in the order {@link PeerState#setPipelineParams} takes them.
     *
     * @return the four current values
     */
    private static int[] pipelineParams() {
        return new int[] {PeerState.MIN_PIPELINE, PeerState.MAX_PIPELINE, PeerState.PARTSIZE,
                          PeerState.MAX_PARTSIZE};
    }

    /**
     * @param saved the values from {@link #pipelineParams()}, in the same order
     */
    private static void restore(int[] saved) {
        PeerState.setPipelineParams(saved[0], saved[1], saved[2], saved[3]);
    }

    /**
     * One entry size has one pool for the life of the JVM, and a different size is a different
     * pool - which is why a pool bound at class load to a size we no longer ask for is never
     * used again.
     */
    @Test
    public void testOneSizeHasOnePool() {
        assertSame(PeerState.blockCache(RESIZED), PeerState.blockCache(RESIZED));
        assertNotSame(PeerState.blockCache(RESIZED), PeerState.blockCache(RESIZED + 1));
    }

    /**
     * The pool keeps the capacity all three classes ask for, not the capacity of whichever one
     * asked last.
     */
    @Test
    public void testThePoolKeepsTheAgreedCapacity() {
        ByteCache pool = PeerState.blockCache(PeerState.PARTSIZE);
        assertEquals(PeerState.BLOCK_CACHE_OBJECTS, pool.getCapacity());
    }

    /**
     * Changing the chunk size pools for the new size straight away, so the first block after
     * the change is not allocated fresh.
     *
     * @throws Exception if the live cache map cannot be read
     */
    @Test
    public void testAChunkSizeChangePoolsForTheNewSize() throws Exception {
        int[] saved = pipelineParams();
        try {
            PeerState.setPipelineParams(saved[0], saved[1], RESIZED, saved[3]);
            assertEquals(RESIZED, PeerState.PARTSIZE);
            assertTrue("no pool for the new chunk size", allCaches().containsKey(RESIZED));
        } finally {
            restore(saved);
        }
    }

    /**
     * The assembler takes its chunk buffer from the pool for the chunk size in use, not the
     * one that happened to be set when its class loaded.
     *
     * @throws Exception on piece or pool failure
     */
    @Test
    public void testTheAssemblerPoolsForTheChunkSizeInUse() throws Exception {
        int[] saved = pipelineParams();
        File tempDir = new File("/tmp/opencode/snark-block-cache-assembler");
        assertTrue("could not create " + tempDir, tempDir.mkdirs() || tempDir.isDirectory());
        try {
            PeerState.setPipelineParams(saved[0], saved[1], RESIZED, saved[3]);
            PartialPiece pp = new PartialPiece(new Piece(0), ASSEMBLY_PIECE, tempDir);
            assertNull("the piece should be file-backed, not kept in RAM", inMemoryData(pp));
            ByteCache pool = PeerState.blockCache(RESIZED);
            long before = pool.getAcquireCount();
            byte[] chunk = new byte[RESIZED];
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(chunk));
            pp.read(in, 0, RESIZED, new NullBWL());
            assertEquals("the assembler took its buffer from outside the pool for the current "
                         + "chunk size", before + 1, pool.getAcquireCount());
            pp.release();
        } finally {
            restore(saved);
        }
    }

    /**
     * The reader that serves a block to a peer takes its buffer from the same pool and puts
     * it back there.
     *
     * @throws Throwable on storage or pool failure
     */
    @Test
    public void testTheReaderPoolsForTheChunkSizeInUse() throws Throwable {
        int[] saved = pipelineParams();
        File dataDir = new File("/tmp/opencode/snark-block-cache-reader");
        try {
            PeerState.setPipelineParams(saved[0], saved[1], RESIZED, saved[3]);
            Storage st = storage(dataDir);
            PartialPiece pp = new PartialPiece(new Piece(0), SERVED_PIECE, dataDir);
            pp.read(new DataInputStream(new ByteArrayInputStream(new byte[SERVED_PIECE])), 0,
                    SERVED_PIECE, new NullBWL());
            assertTrue("the piece was rejected", st.putPiece(pp));
            ByteCache pool = PeerState.blockCache(RESIZED);
            long acquires = pool.getAcquireCount();
            ByteArray ba = st.getPiece(0, 0, PeerState.PARTSIZE);
            assertNotNull("the reader declined to serve the piece", ba);
            assertEquals("the reader took its buffer from outside the pool for the current "
                         + "chunk size", acquires + 1, pool.getAcquireCount());
            assertEquals(RESIZED, ba.getData().length);
            long releases = pool.getReleaseCount();
            Message.releaseBuffer(ba);
            assertEquals("the buffer was not returned to the pool for the current chunk size",
                         releases + 1, pool.getReleaseCount());
        } finally {
            restore(saved);
        }
    }

    /**
     * A storage over a two-piece single-file torrent, with its files created.
     *
     * @param dataDir where the torrent file goes
     * @return the storage, checked and ready for a piece write
     * @throws Throwable on metainfo or storage failure
     */
    private static Storage storage(File dataDir) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(2, SERVED_PIECE)));
        Storage st = new Storage(util, dataDir, mi, null, false);
        st.check();
        return st;
    }

    /**
     * The BEncoded bytes of a single-file torrent.
     *
     * @param pieces how many pieces the torrent declares
     * @param pieceLength the piece length in bytes
     * @return the metainfo bytes
     */
    private static byte[] torrentBytes(int pieces, int pieceLength) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append((long) pieceLength * pieces).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(pieceLength).append('e');
        sb.append("6:pieces").append(20 * pieces).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] hashes = new byte[20 * pieces];
        byte[] digest = sha1OfZeros(pieceLength);
        for (int i = 0; i < pieces; i++) {
            System.arraycopy(digest, 0, hashes, 20 * i, 20);
        }
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return rv;
    }

    /**
     * The SHA-1 the metainfo must carry for a piece of all zeros, so the fixture's pieces
     * pass {@link MetaInfo#checkPiece}.
     *
     * @param length the piece length in bytes
     * @return the 20-byte digest
     */
    private static byte[] sha1OfZeros(int length) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(new byte[length]);
        } catch (NoSuchAlgorithmException nsae) {
            throw new AssertionError(nsae);
        }
    }

    /**
     * The buffer of a piece that kept its data in RAM, read through the field so a case can
     * tell which of the two paths it is exercising.
     *
     * @param pp the piece to look at
     * @return the buffer, or null when the piece is file-backed
     * @throws Exception if the private field cannot be read
     */
    private static byte[] inMemoryData(PartialPiece pp) throws Exception {
        Field f = PartialPiece.class.getDeclaredField("bs");
        f.setAccessible(true);
        return (byte[]) f.get(pp);
    }

    /**
     * A listener that counts nothing and never throttles, for reads with no torrent behind
     * them.
     */
    private static final class NullBWL implements BandwidthListener {
        /** Nothing to count. */
        NullBWL() {}

        /**
         * @return 0, nothing is measured
         */
        @Override
        public long getUploadRate() {
            return 0;
        }

        /**
         * @return 0, nothing is measured
         */
        @Override
        public long getDownloadRate() {
            return 0;
        }

        /**
         * @param size the bytes that were sent
         */
        @Override
        public void uploaded(int size) {}

        /**
         * @param size the bytes that were received
         */
        @Override
        public void downloaded(int size) {}

        /**
         * @param size the bytes to send
         * @return true, nothing is throttled
         */
        @Override
        public boolean shouldSend(int size) {
            return true;
        }

        /**
         * @param peer the peer the bytes would be requested from
         * @param size the bytes to request
         * @return true, nothing is throttled
         */
        @Override
        public boolean shouldRequest(Peer peer, int size) {
            return true;
        }

        /**
         * @return Long.MAX_VALUE, no limit is set
         */
        @Override
        public long getUpBWLimit() {
            return Long.MAX_VALUE;
        }

        /**
         * @return Long.MAX_VALUE, no limit is set
         */
        @Override
        public long getDownBWLimit() {
            return Long.MAX_VALUE;
        }

        /**
         * @return false, nothing is measured
         */
        @Override
        public boolean overUpBWLimit() {
            return false;
        }

        /**
         * @return false, nothing is measured
         */
        @Override
        public boolean overDownBWLimit() {
            return false;
        }
    }
}
