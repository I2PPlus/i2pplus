package org.klomp.snark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The pooled block buffers, and what happens to them when the chunk size changes
 * under a running download.
 *
 * <p>The hazard the caching introduces: the pool is kept in a field instead of being
 * resolved from the global cache map per block, because that resolution takes a
 * static monitor and the block path reaches it twice per block from every peer
 * thread. A cached pool that stops matching the chunk size in force is worse than
 * the lock it saves - every buffer acquired at one size and handed to a pool of
 * another is dropped with a warning, and every block goes back to allocating.
 *
 * <p>No router and no network: the pool is asked for directly, and the chunk size is
 * moved with the same entry point the console uses.
 *
 * @since 0.9.72+
 */
public class PeerStateBlockCacheTest {

    /** A chunk size to start at, well away from the other one used here. */
    private static final int CHUNK_A = 16 * 1024;

    /** The chunk size a running download is moved to. */
    private static final int CHUNK_B = 32 * 1024;

    /** The per-request cap both cases run under. */
    private static final int MAX_PART = 1024 * 1024;

    /** Pipeline floor to put back afterwards. */
    private int _savedMin;

    /** Pipeline ceiling to put back afterwards. */
    private int _savedMax;

    /** Chunk size to put back afterwards. */
    private int _savedPartsize;

    /** Per-request cap to put back afterwards. */
    private int _savedMaxPartsize;

    /**
     * A fixture group for the block buffer pool; it holds no state of its own.
     *
     * @since 0.9.72+
     */
    public PeerStateBlockCacheTest() {}

    /**
     * Remembers the pipeline settings every case changes, so the suite is left with
     * the chunk size it expects.
     */
    @Before
    public void savePipelineParams() {
        _savedMin = PeerState.MIN_PIPELINE;
        _savedMax = PeerState.MAX_PIPELINE;
        _savedPartsize = PeerState.PARTSIZE;
        _savedMaxPartsize = PeerState.MAX_PARTSIZE;
    }

    /**
     * Puts the pipeline settings, and with them the pool the block path uses, back
     * as the suite had them.
     */
    @After
    public void restorePipelineParams() {
        PeerState.setPipelineParams(_savedMin, _savedMax, _savedPartsize, _savedMaxPartsize);
    }

    /**
     * The pool the block path acquires from is the size the chunk size says it is,
     * and the buffer it hands out is really that long.
     */
    @Test
    public void thePoolMatchesTheChunkSizeInForce() {
        PeerState.setPipelineParams(_savedMin, _savedMax, CHUNK_A, MAX_PART);
        ByteCache cache = PeerState.blockCache(PeerState.PARTSIZE);
        assertEquals(CHUNK_A, cache.getEntrySize());
        ByteArray ba = cache.acquire();
        assertEquals(CHUNK_A, ba.getData().length);
    }

    /**
     * Moving the chunk size moves the pool the block path uses, and the size a
     * buffer was acquired at still resolves to a pool that takes it back: a block in
     * flight across the change is pooled, not dropped as the wrong size.
     */
    @Test
    public void aBufferAcquiredBeforeAChunkSizeChangeStillHasItsPool() {
        PeerState.setPipelineParams(_savedMin, _savedMax, CHUNK_A, MAX_PART);
        ByteCache before = PeerState.blockCache(PeerState.PARTSIZE);
        ByteArray ba = before.acquire();

        PeerState.setPipelineParams(_savedMin, _savedMax, CHUNK_B, MAX_PART);

        assertEquals("the pool followed the chunk size only for the size, not the caller",
                     CHUNK_B, PeerState.blockCache(PeerState.PARTSIZE).getEntrySize());
        ByteCache stillOurs = PeerState.blockCache(CHUNK_A);
        assertEquals(CHUNK_A, stillOurs.getEntrySize());
        stillOurs.release(ba, false);
        assertSame("the buffer from before the change was not pooled", ba, stillOurs.acquire());
    }

    /**
     * The pool for the chunk size in force is the same object every time it is
     * asked for - the point of caching it is that the per-block path never goes
     * looking for it again.
     */
    @Test
    public void thePoolForTheChunkSizeInForceIsReused() {
        PeerState.setPipelineParams(_savedMin, _savedMax, CHUNK_A, MAX_PART);
        ByteCache first = PeerState.blockCache(PeerState.PARTSIZE);
        ByteCache second = PeerState.blockCache(PeerState.PARTSIZE);
        assertSame(first, second);
    }
}
