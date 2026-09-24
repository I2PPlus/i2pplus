package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the bandwidth-derived slow-start threshold anchor in
 * {@link Connection#bandwidthSsthreshAnchor(float, int, int, int)}.
 *
 * <p>The anchor estimates the packets the pipe can hold as measured bandwidth
 * times an effective RTT. The effective RTT is floored at
 * {@link Connection#SS_THRESH_BW_ANCHOR_MIN_RTT} (1s): a raw sub-second
 * min-RTT would otherwise shrink the anchor toward the degenerate 16-packet
 * fast-retransmit floor after the first loss event. Once pinned at 16 packets
 * the connection slows to ~10KB/s and the low ACK rate lowers the measured
 * bandwidth, confirming the low anchor — a self-reinforcing throughput lock.
 *
 * <p>A pure, stateless decision: testable without a running router.
 *
 * @since 0.9.71+
 */
public class ConnectionBandwidthSsthreshAnchorTest {

    /** The anchor never falls below the caller's floor, even with no bandwidth evidence. */
    @Test
    public void testAnchorNeverBelowFloor() {
        assertEquals(16, Connection.bandwidthSsthreshAnchor(0f, 500, 2, 16));
        assertEquals(2, Connection.bandwidthSsthreshAnchor(0f, 500, 1, 2));
        assertEquals(16, Connection.bandwidthSsthreshAnchor(0.001f, 100, 2, 16));
    }

    /** A sub-second min-RTT is floored to the 1s anchor RTT. */
    @Test
    public void testSubSecondMinRttIsFloored() {
        // identical anchors at 500ms and exactly 1000ms raw min-RTT
        assertEquals(Connection.bandwidthSsthreshAnchor(1f, 500, 2, 16),
                     Connection.bandwidthSsthreshAnchor(1f, 1000, 2, 16));
        assertEquals(2000, Connection.bandwidthSsthreshAnchor(1f, 400, 2, 16));
    }

    /** A healthy pipe's anchor is well above the 16-packet degenerate floor. */
    @Test
    public void testHealthyPathAnchorIsLarge() {
        // 1 packet/ms (~1MB/s @ 1KB packets) on a fast floor: anchor 2000
        assertEquals(2000, Connection.bandwidthSsthreshAnchor(1f, 400, 2, 16));
    }

    /** A young window with a low early bandwidth reading still recovers high. */
    @Test
    public void testLowEarlyBandwidthEscapesFloor() {
        // 0.1 packets/ms (100KB/s) on a sub-second min-RTT -> 200, not 16
        assertEquals(200, Connection.bandwidthSsthreshAnchor(0.1f, 400, 2, 16));
        assertEquals(100, Connection.bandwidthSsthreshAnchor(0.1f, 400, 1, 16));
    }

    /** Even an already-degraded pipe rises above the 16-packet lock. */
    @Test
    public void testDegradedPipeRisesAboveLock() {
        // ~10KB/s reading (0.01 packets/ms) -> 20, above the 16-packet floor
        assertEquals(20, Connection.bandwidthSsthreshAnchor(0.01f, 400, 2, 16));
    }

    /** Above the anchor floor the anchor scales with the actual min-RTT. */
    @Test
    public void testActualMinRttAboveFloorScales() {
        assertEquals(3000, Connection.bandwidthSsthreshAnchor(1f, 1500, 2, 16));
        assertEquals(5000, Connection.bandwidthSsthreshAnchor(1f, 2500, 2, 16));
    }

    /** The growth factor is honored at the top (the maxSS clamp lives at the callers). */
    @Test
    public void testFactorIsApplied() {
        assertEquals(1000, Connection.bandwidthSsthreshAnchor(1f, 500, 1, 16));
        assertEquals(2000, Connection.bandwidthSsthreshAnchor(1f, 500, 2, 16));
        assertEquals(4000, Connection.bandwidthSsthreshAnchor(1f, 500, 4, 16));
    }

    /** Rounding: fractional bandwidth estimates round to the nearest packet. */
    @Test
    public void testFractionalBandwidthRounds() {
        // 0.5 packets/ms * 1000ms * 1 == 500
        assertEquals(500, Connection.bandwidthSsthreshAnchor(0.5f, 500, 1, 16));
        // 0.449 * 1000 * 1 == 449 (round, not truncate)
        assertEquals(449, Connection.bandwidthSsthreshAnchor(0.449f, 500, 1, 16));
        // 0.451 * 1000 * 1 == 451
        assertEquals(451, Connection.bandwidthSsthreshAnchor(0.451f, 500, 1, 16));
    }

    /** Degenerate inputs behave safely: an unknown/zero min-RTT floors to the
     *  healthy 1s anchor (never a degenerate small anchor), and a zero factor
     *  still respects the floor. */
    @Test
    public void testNegativeOrZeroInputsStillFloor() {
        assertEquals(16, Connection.bandwidthSsthreshAnchor(-1f, 500, 2, 16));
        // min-RTT unset: floored to the 1s anchor -> healthy 2000, not 16
        assertEquals(2000, Connection.bandwidthSsthreshAnchor(1f, 0, 2, 16));
        assertEquals(2000, Connection.bandwidthSsthreshAnchor(1f, -5, 2, 16));
        // zero factor yields 0 before the floor -> floor wins
        assertEquals(16, Connection.bandwidthSsthreshAnchor(1f, 500, 0, 16));
    }
}
