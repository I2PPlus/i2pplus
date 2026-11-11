package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for <code>PeerConnectionOut.setPrefetchParams</code>, the clamp applied to the
 * per-peer piece-prefetch depth and the ceiling of the shared prefetch pool. The two
 * bounds are static and global, so the fixture captures the shipped values at class
 * load and restores them after every case; the pool is shared with every other test in
 * the module's JVM.
 *
 * @since 0.9.72+
 */
public class PrefetchParamsTest {

    /** Prefetch depth as shipped, captured before any case can change it. */
    private static final int INIT_INFLIGHT = PeerConnectionOut.MAX_PREFETCH_INFLIGHT;

    /** Pool ceiling as shipped, captured before any case can change it. */
    private static final int INIT_THREADS = PeerConnectionOut.MAX_PREFETCH_THREADS;

    /**
     * Fixture group for the prefetch clamps; it holds no state.
     */
    public PrefetchParamsTest() {}

    /**
     * Restore the shipped bounds before each case so the assertions describe the
     * defaults and not whatever the previous case left behind.
     */
    @Before
    public void setUp() {
        PeerConnectionOut.setPrefetchParams(INIT_INFLIGHT, INIT_THREADS);
    }

    /**
     * Restore the shipped bounds after each case.
     */
    @After
    public void tearDown() {
        PeerConnectionOut.setPrefetchParams(INIT_INFLIGHT, INIT_THREADS);
    }

    /**
     * The shipped defaults are the raised pair: six blocks in flight per uploading peer
     * and a pool that can actually run six at once. Below four the uploading side stalls
     * on cold reads at tunnel latency.
     */
    @Test
    public void testShippedDefaultsAreRaised() {
        assertTrue("inflight default " + INIT_INFLIGHT, INIT_INFLIGHT >= 6);
        assertTrue("threads default " + INIT_THREADS, INIT_THREADS >= 6);
        assertEquals(INIT_THREADS, PeerConnectionOut.prefetchPoolSize());
    }

    /**
     * Values above the caps are pulled back to them: the depth cap bounds pinned
     * buffers per peer, the pool cap bounds concurrent disk reads router-wide.
     */
    @Test
    public void testClampsAboveCaps() {
        PeerConnectionOut.setPrefetchParams(1000, 1000);
        assertEquals(32, PeerConnectionOut.MAX_PREFETCH_INFLIGHT);
        assertEquals(16, PeerConnectionOut.MAX_PREFETCH_THREADS);
        assertEquals(16, PeerConnectionOut.prefetchPoolSize());
    }

    /**
     * Zero and negative values still leave one prefetch in flight and one pool thread;
     * a zero depth would disable the deferred load entirely and drop every piece onto
     * the sender thread.
     */
    @Test
    public void testClampsBelowMinimum() {
        PeerConnectionOut.setPrefetchParams(0, -5);
        assertEquals(1, PeerConnectionOut.MAX_PREFETCH_INFLIGHT);
        assertEquals(1, PeerConnectionOut.MAX_PREFETCH_THREADS);
        assertEquals(1, PeerConnectionOut.prefetchPoolSize());
    }

    /**
     * In-range values are applied verbatim, and the pool follows the thread argument
     * rather than the depth argument - the two bounds are independent.
     */
    @Test
    public void testInRangeValuesApplied() {
        PeerConnectionOut.setPrefetchParams(4, 3);
        assertEquals(4, PeerConnectionOut.MAX_PREFETCH_INFLIGHT);
        assertEquals(3, PeerConnectionOut.MAX_PREFETCH_THREADS);
        assertEquals(3, PeerConnectionOut.prefetchPoolSize());
    }

    /**
     * A depth above the pool ceiling is accepted rather than silently reduced: the
     * excess loads fall back to the inline path instead of being dropped, so the
     * depth is a bound on outstanding requests, not on running threads.
     */
    @Test
    public void testDepthAbovePoolCeilingIsKept() {
        PeerConnectionOut.setPrefetchParams(12, 2);
        assertEquals(12, PeerConnectionOut.MAX_PREFETCH_INFLIGHT);
        assertEquals(2, PeerConnectionOut.prefetchPoolSize());
    }

    /**
     * Raising the ceiling on a pool that has already started takes effect immediately;
     * lowering it stops new bursts from spawning threads without killing running loads,
     * because the core size is zero and idle threads time out.
     */
    @Test
    public void testPoolCeilingIsLive() {
        PeerConnectionOut.setPrefetchParams(6, 6);
        assertEquals(6, PeerConnectionOut.prefetchPoolSize());
        PeerConnectionOut.setPrefetchParams(6, 1);
        assertEquals(1, PeerConnectionOut.prefetchPoolSize());
        PeerConnectionOut.setPrefetchParams(6, 6);
        assertEquals(6, PeerConnectionOut.prefetchPoolSize());
    }
}
