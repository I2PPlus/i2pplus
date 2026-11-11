package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for <code>PeerCoordinator.setRate</code> and <code>getRate</code>, the
 * RATE_DEPTH-deep ring behind the upload/download rates shown in the UI and reported
 * through <code>Snark.getDownloadRate()</code>. The ring holds per-CHECK_PERIOD byte
 * totals, most recent first, terminated by a negative sentinel; <code>getRate</code>
 * weights the newest sample three times the oldest.
 *
 * <p>The insert used to sit inside the shift loop, which overwrote slot 0 on every
 * iteration and then shifted that same value down, so the ring held
 * {newest, newest, previous} and the weighted average collapsed onto the newest
 * sample. {@link #testRingHoldsThreeDistinctSamples()} pins that regression.
 *
 * @since 0.9.72+
 */
public class RateHistoryTest {

    /**
     * Fixture group for the rate ring; it holds no state.
     */
    public RateHistoryTest() {}

    /**
     * A fresh ring holds no complete sample, so the rate is zero rather than a
     * division over the sentinel.
     */
    @Test
    public void testEmptyRingIsZero() {
        long[] ring = {-1, -1, -1};
        assertEquals(0, PeerCoordinator.getRate(ring));
    }

    /**
     * One sample of 1000 bytes in a 5 s period is 200 B/s.
     */
    @Test
    public void testSingleSampleRate() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(1000, ring);
        assertArrayEquals(new long[] {1000, -1, -1}, ring);
        assertEquals(200, PeerCoordinator.getRate(ring));
    }

    /**
     * Two samples shift the first down instead of duplicating the second: the ring is
     * {newest, previous, sentinel}. Under the misplaced-insert bug this held
     * {2000, 2000, -1} and the oldest slot was never filled.
     */
    @Test
    public void testTwoSamplesShiftDown() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(1000, ring);
        PeerCoordinator.setRate(2000, ring);
        assertArrayEquals(new long[] {2000, 1000, -1}, ring);
        // (3*2000 + 2*1000) / (5 * 5s)
        assertEquals(320, PeerCoordinator.getRate(ring));
    }

    /**
     * Three distinct pushes leave three distinct samples, newest first, oldest last.
     * This is the regression pin for the insert-inside-the-shift-loop bug: the buggy
     * version left {3000, 3000, 2000}.
     */
    @Test
    public void testRingHoldsThreeDistinctSamples() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(1000, ring);
        PeerCoordinator.setRate(2000, ring);
        PeerCoordinator.setRate(3000, ring);
        assertArrayEquals(new long[] {3000, 2000, 1000}, ring);
    }

    /**
     * A full ring is weighted 3:2:1 newest to oldest, over RATE_DEPTH * CHECK_PERIOD.
     */
    @Test
    public void testWeightedAverageOverFullRing() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(1000, ring);
        PeerCoordinator.setRate(2000, ring);
        PeerCoordinator.setRate(3000, ring);
        // (3*3000 + 2*2000 + 1*1000) / (6 * 5s)
        assertEquals(466, PeerCoordinator.getRate(ring));
    }

    /**
     * A fourth push drops the oldest sample; the ring is fixed size and never grows.
     */
    @Test
    public void testOldestSampleFallsOff() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(1000, ring);
        PeerCoordinator.setRate(2000, ring);
        PeerCoordinator.setRate(3000, ring);
        PeerCoordinator.setRate(4000, ring);
        assertArrayEquals(new long[] {4000, 3000, 2000}, ring);
        assertEquals(666, PeerCoordinator.getRate(ring));
    }

    /**
     * A zero-byte period is a real reading (the peer went quiet), not the "no sample
     * yet" sentinel, so it stays in the weighted average and drags the rate down
     * instead of ending the walk.
     */
    @Test
    public void testZeroSampleCounts() {
        long[] ring = {-1, -1, -1};
        PeerCoordinator.setRate(0, ring);
        assertArrayEquals(new long[] {0, -1, -1}, ring);
        assertEquals(0, PeerCoordinator.getRate(ring));
        PeerCoordinator.setRate(1000, ring);
        assertArrayEquals(new long[] {1000, 0, -1}, ring);
        // (3*1000 + 2*0) / (5 * 5s)
        assertEquals(120, PeerCoordinator.getRate(ring));
    }

    /**
     * The ring length is RATE_DEPTH; a caller-supplied array of that size is filled
     * completely and no element outside it is touched.
     */
    @Test
    public void testRingLengthIsRateDepth() {
        assertEquals(3, PeerCoordinator.RATE_DEPTH);
        long[] ring = new long[PeerCoordinator.RATE_DEPTH];
        java.util.Arrays.fill(ring, -1);
        for (int i = 1; i <= PeerCoordinator.RATE_DEPTH; i++) {
            PeerCoordinator.setRate(i * 1000, ring);
        }
        assertArrayEquals(new long[] {3000, 2000, 1000}, ring);
    }
}
