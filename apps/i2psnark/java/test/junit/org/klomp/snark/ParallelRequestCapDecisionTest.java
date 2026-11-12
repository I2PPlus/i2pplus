package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how many peers may pull one piece at a time. The cap used to be a constant eight,
 * which is a no-op for a torrent whose pieces are one block long and a hard stop for one
 * whose pieces are thirty-two blocks long - the second case left twenty four blocks' worth
 * of willing peers unused on every end-game piece.
 *
 * @since 0.9.72+
 */
public class ParallelRequestCapDecisionTest {

    /** The default request block size, {@link PeerState#PARTSIZE} as shipped. */
    private static final int PART = 16 * 1024;

    /**
     * Test instance; every test calls a static decision method, so the instance carries
     * no state.
     */
    public ParallelRequestCapDecisionTest() {
    }

    /**
     * A piece that is a single block cannot be shared out, so the floor is what applies -
     * the old constant behaviour for a small-piece torrent.
     */
    @Test
    public void testASingleBlockPieceKeepsTheFloor() {
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS,
                     PeerCoordinator.parallelRequestCap(PART, PART));
    }

    /**
     * Eight blocks is exactly the floor, the point where scaling and the constant agree.
     */
    @Test
    public void testAPieceOfExactlyTheFloorNumberOfBlocksIsTheFloor() {
        assertEquals(8, PeerCoordinator.parallelRequestCap(8 * PART, PART));
    }

    /**
     * Ten blocks of work and ten peers, between the floor and the ceiling - this is the
     * case the constant silently dropped two peers on.
     */
    @Test
    public void testTheCapScalesWithTheBlockCount() {
        assertEquals(10, PeerCoordinator.parallelRequestCap(10 * PART, PART));
        // a part block still counts as a block to hand out
        assertEquals(11, PeerCoordinator.parallelRequestCap(10 * PART + 1, PART));
    }

    /**
     * A 512 KiB piece is 32 blocks at the default part size, so the ceiling applies and
     * the remaining 16 blocks' worth of demand is still served by those sixteen peers.
     */
    @Test
    public void testALargePieceTakesTheCeiling() {
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS_CAP,
                     PeerCoordinator.parallelRequestCap(512 * 1024, PART));
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS_CAP,
                     PeerCoordinator.parallelRequestCap(4 * 1024 * 1024, PART));
    }

    /**
     * With 128 KiB blocks configured, a 128 KiB piece is one block again, so the floor
     * holds no matter how big the pieces are - the scaling follows blocks, not bytes.
     */
    @Test
    public void testTheCapFollowsBlocksNotBytes() {
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS,
                     PeerCoordinator.parallelRequestCap(128 * 1024, 128 * 1024));
    }

    /**
     * A piece length we cannot read (no metainfo yet) or a part size that makes no sense
     * falls back to the floor instead of dividing by zero or inventing a huge cap.
     */
    @Test
    public void testUnusableLengthsFallBackToTheFloor() {
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS,
                     PeerCoordinator.parallelRequestCap(0, PART));
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS,
                     PeerCoordinator.parallelRequestCap(-1, PART));
        assertEquals(PeerCoordinator.MAX_PARALLEL_REQUESTS,
                     PeerCoordinator.parallelRequestCap(512 * 1024, 0));
    }

    /**
     * The floor stays the floor and the ceiling stays the ceiling, so the bookkeeping the
     * cap exists to bound cannot grow past sixteen peers per piece.
     */
    @Test
    public void testTheBoundsThemselves() {
        assertEquals(8, PeerCoordinator.MAX_PARALLEL_REQUESTS);
        assertEquals(16, PeerCoordinator.MAX_PARALLEL_REQUESTS_CAP);
        assertTrue(PeerCoordinator.MAX_PARALLEL_REQUESTS
                   < PeerCoordinator.MAX_PARALLEL_REQUESTS_CAP);
    }
}
