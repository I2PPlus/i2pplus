package org.klomp.snark;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;
import net.i2p.util.ByteCache;
import org.junit.Test;

/**
 * Tests for the buffer pool shared by the three classes that handle one chunk of a piece: the
 * piece writer, the assembler and the reader. ByteCache keys its caches by entry size, so all
 * three get the same object, and the capacity of that object is whatever the last class to
 * initialise asked for - two of them used to ask for a quarter of what the third needed. A
 * chunk-size change has to pool for the new size as well, or every block allocation after one
 * is a fresh array.
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

    /**
     * The pool a class holds in its private static field.
     *
     * @param clazz the class whose pool is asked for
     * @return the cache that class supplies buffers from
     * @throws Exception if the private field cannot be read
     */
    private static ByteCache cacheOf(Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField("_cache");
        f.setAccessible(true);
        return (ByteCache) f.get(null);
    }

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
     * The three classes hold the same pool, which is what makes its capacity a single number
     * rather than a race between three.
     *
     * @throws Exception if a class's private pool field cannot be read
     */
    @Test
    public void testTheThreeClassesShareOneCache() throws Exception {
        ByteCache writer = cacheOf(Storage.class);
        ByteCache assembler = cacheOf(PartialPiece.class);
        ByteCache reader = cacheOf(Message.class);
        assertSame(writer, assembler);
        assertSame(assembler, reader);
    }

    /**
     * That one pool keeps the capacity all three ask for, not the capacity of whichever class
     * was loaded last.
     *
     * @throws Exception if a class's private pool field cannot be read
     */
    @Test
    public void testTheSharedPoolKeepsTheAgreedCapacity() throws Exception {
        assertEquals(PeerState.BLOCK_CACHE_OBJECTS, cacheOf(Storage.class).getCapacity());
        assertEquals(PeerState.BLOCK_CACHE_OBJECTS, cacheOf(PartialPiece.class).getCapacity());
        assertEquals(PeerState.BLOCK_CACHE_OBJECTS, cacheOf(Message.class).getCapacity());
    }

    /**
     * Changing the chunk size pools for the new size too, so blocks of the new size are not
     * allocated fresh every time.
     *
     * @throws Exception if the live cache map cannot be read
     */
    @Test
    public void testAChunkSizeChangePoolsForTheNewSize() throws Exception {
        int partsize = PeerState.PARTSIZE;
        int maxPartsize = PeerState.MAX_PARTSIZE;
        int min = PeerState.MIN_PIPELINE;
        int max = PeerState.MAX_PIPELINE;
        int resized = 32 * 1024;
        try {
            PeerState.setPipelineParams(min, max, resized, maxPartsize);
            assertEquals(resized, PeerState.PARTSIZE);
            assertTrue("no pool for the new chunk size", allCaches().containsKey(resized));
        } finally {
            PeerState.setPipelineParams(min, max, partsize, maxPartsize);
        }
    }
}
