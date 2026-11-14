package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

/**
 * Pins how big a block we ask a peer for. Snark has always asked for PARTSIZE while serving up
 * to MAX_PARTSIZE, so the BEP 6 large-block allowance was receive-only: a peer that could answer
 * with 128 KiB in one message got eight requests and eight replies for the same bytes. The size
 * is now taken from the peer's own rate, but only for a peer carrying the BEP 6 FAST option bit,
 * and the window is counted in the blocks actually asked for - the coupling that matters, since a
 * bigger block with the window still counted in 16 KiB chunks would put eight times the bytes in
 * flight on the wire.
 *
 * @since 0.9.72+
 */
public class RequestSizeTest {

    /** Chunk size we ask by default. */
    private static final int PART = PeerState.PARTSIZE;

    /** Largest block a BEP 6 peer may be asked for. */
    private static final int MAXPART = PeerState.MAX_PARTSIZE;

    /** Sub-block granularity requests are handed out in. */
    private static final int SUB = PartialPiece.SUB_PARTSIZE;

    /** Window floor in blocks. */
    private static final int MIN = PeerState.MIN_PIPELINE;

    /** Window ceiling in blocks. */
    private static final int MAX = PeerState.MAX_PIPELINE;

    /** Seconds of data kept in flight with a peer. */
    private static final int RTT = PeerState.REQUEST_RTT_SECONDS;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public RequestSizeTest() {}

    /**
     * The block size to ask of a peer at the given rate.
     *
     * @param rate the peer's measured download rate in bytes per second
     * @param fast whether the peer carries the BEP 6 FAST option bit
     * @return the block size in bytes
     */
    private static int size(long rate, boolean fast) {
        return PeerState.requestSize(rate, fast, PART, MAXPART, RTT);
    }

    /**
     * A peer that does not carry the FAST option bit is never asked for more than the chunk
     * size, however fast it is: BEP 6 only allows the big request of peers that announced it,
     * and a peer that has not may choke rather than answer.
     */
    @Test
    public void testNonFastPeerIsNeverAskedBig() {
        assertEquals(PART, size(0, false));
        assertEquals(PART, size(1024 * 1024, false));
        assertEquals(PART, size(100L * 1024 * 1024, false));
    }

    /**
     * A fast peer with no rate sample yet is asked for the chunk size, so the first blocks on a
     * fresh connection are the ordinary small ones and the size only grows once the checker has
     * measured the peer.
     */
    @Test
    public void testUnmeasuredPeerGetsTheChunkSize() {
        assertEquals(PART, size(0, true));
        assertEquals(PART, size(-1, true));
    }

    /**
     * A fast peer that cannot move a big block inside the window is still asked for the chunk
     * size: enlarging the block past that only delays piece completion.
     */
    @Test
    public void testSlowFastPeerKeepsTheChunkSize() {
        // the block only grows once rate * RTT exceeds MIN_PIPELINE chunks
        long threshold = (long) PART * MIN / RTT;
        assertEquals(PART, size(threshold - 1, true));
        assertEquals(PART, size(threshold, true));
        assertTrue(size(threshold + 1, true) >= PART);
    }

    /**
     * Past the threshold the block grows with the peer's rate, stays a whole number of sub-blocks
     * so the merge loop cannot overshoot it, and never passes the configured cap.
     */
    @Test
    public void testSizeRampsAlignsAndStaysCapped() {
        long threshold = (long) PART * MIN / RTT;
        int prev = PART;
        for (long rate = threshold; rate <= 8L * 1024 * 1024; rate += 1024) {
            int sz = size(rate, true);
            assertTrue(rate + " -> " + sz, sz >= PART);
            assertTrue(rate + " -> " + sz, sz <= MAXPART);
            assertTrue(rate + " -> " + sz, sz % SUB == 0);
            assertTrue(Long.toString(rate), sz >= prev);
            prev = sz;
        }
        assertEquals(MAXPART, size(8L * 1024 * 1024, true));
    }

    /**
     * The coupling the change has to keep: counted in the blocks actually asked for, the window
     * never drops below its floor, so a bigger block buys fewer messages rather than fewer bytes
     * in flight.
     */
    @Test
    public void testWindowStillCoversTheRoundTrip() {
        for (long rate = 0; rate <= 8L * 1024 * 1024; rate += 1024) {
            int sz = size(rate, true);
            int blocks = PeerState.requestWindow(rate, RTT, MIN, MAX, sz);
            assertTrue(rate + " -> " + blocks, blocks >= MIN);
            // bytes in flight stay at about one time window's worth, never the eight-fold a
            // window still counted in 16 KiB chunks would put on the wire
            long inflight = (long) blocks * sz;
            assertTrue(rate + " -> " + inflight, inflight <= rate * RTT + MAXPART);
        }
    }

    /**
     * The win is the message count: the same bytes in flight arrive in fewer, larger blocks, so
     * the peer answers a quarter as many requests.
     */
    @Test
    public void testBiggerBlocksCutTheMessageCount() {
        long rate = 100 * 1024;
        int sz = size(rate, true);
        assertTrue(Integer.toString(sz), sz > PART);
        int oldBlocks = PeerState.requestWindow(rate, RTT, MIN, MAX, PART);
        int newBlocks = PeerState.requestWindow(rate, RTT, MIN, MAX, sz);
        assertTrue(oldBlocks + " vs " + newBlocks, newBlocks < oldBlocks);
        // the same bytes stay in flight either way, within one block of each other
        long oldBytes = (long) oldBlocks * PART;
        long newBytes = (long) newBlocks * sz;
        assertTrue(oldBytes + " vs " + newBytes, Math.abs(oldBytes - newBytes) < sz);
    }

    /**
     * A memory-resident partial piece of the given length.
     *
     * @param len piece length in bytes
     * @return the partial piece, fully in memory
     */
    private static PartialPiece piece(int len) {
        return new PartialPiece(new Piece(1), len, new File("/tmp"));
    }

    /**
     * The merge cap is what bounds a request: with the default chunk size a run of empty
     * sub-blocks yields one chunk, and with a BEP 6 cap it yields that many chunks in one
     * request, which is the eight round trips saved.
     */
    @Test
    public void testMergeCapBoundsTheRequest() {
        int len = 8 * PART;
        Request one = piece(len).getRequest(0);
        assertEquals(PART, one.len);

        Request big = piece(len).getRequest(0, MAXPART);
        assertEquals(MAXPART, big.len);
        assertEquals(0, big.off);

        // and a cap below one sub-block still hands out a whole sub-block, never a partial one
        Request small = piece(len).getRequest(0, SUB / 2);
        assertEquals(SUB, small.len);
    }

    /**
     * A capped request still stops at the end of the piece, so the tail block is never padded out
     * past the piece length.
     */
    @Test
    public void testCappedRequestStopsAtThePieceEnd() {
        int len = 3 * SUB + 992;
        Request r = piece(len).getRequest(0, MAXPART);
        assertEquals(len, r.len);
    }

    /**
     * Requesting with a cap claims the sub-blocks it covers, so a second call on the same piece
     * starts after them rather than handing the same bytes to another peer.
     */
    @Test
    public void testCappedRequestClaimsWhatItCovers() {
        int len = 16 * SUB;
        PartialPiece pp = piece(len);
        Request first = pp.getRequest(0, 4 * SUB);
        assertEquals(4 * SUB, first.len);
        Request second = pp.getRequest(first.off + first.len, 4 * SUB);
        assertEquals(4 * SUB, second.off);
        assertEquals(4 * SUB, second.len);
    }
}
