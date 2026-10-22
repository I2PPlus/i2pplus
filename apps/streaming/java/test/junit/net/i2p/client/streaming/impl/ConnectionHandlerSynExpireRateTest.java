package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The SYN accept-queue expire rate the adaptive stress clamp is built on. The
 * counters feeding it were volatile ints incremented with ++ by 4-16 accept
 * workers and the SynReaper, so updates were lost and the clamp decided on a
 * number that did not reflect the traffic.
 *
 * @since 0.9.72+
 */
public class ConnectionHandlerSynExpireRateTest {

    /** No traffic in the window means no evidence, reported as -1 so the clamp stays off. */
    @Test
    public void testNoEvidenceIsMinusOne() {
        assertEquals(-1, ConnectionHandler.synExpireRatePct(0, 0));
        assertEquals(-1, ConnectionHandler.synExpireRatePct(0, 5));
        assertEquals(-1, ConnectionHandler.synExpireRatePct(-1, 0));
    }

    /** Traffic with nothing expired is a clean 0. */
    @Test
    public void testNothingExpiredIsZero() {
        assertEquals(0, ConnectionHandler.synExpireRatePct(1, 0));
        assertEquals(0, ConnectionHandler.synExpireRatePct(1000, 0));
        assertEquals(0, ConnectionHandler.synExpireRatePct(1000, -3));
    }

    /** Exact rates. */
    @Test
    public void testExactRates() {
        assertEquals(100, ConnectionHandler.synExpireRatePct(1, 1));
        assertEquals(50, ConnectionHandler.synExpireRatePct(2, 1));
        assertEquals(25, ConnectionHandler.synExpireRatePct(4, 1));
        assertEquals(40, ConnectionHandler.synExpireRatePct(10, 4));
    }

    /** Integer truncation, pinned so a future rounding change is a deliberate one. */
    @Test
    public void testTruncatesTowardZero() {
        assertEquals(33, ConnectionHandler.synExpireRatePct(3, 1));
        assertEquals(66, ConnectionHandler.synExpireRatePct(3, 2));
        assertEquals(1, ConnectionHandler.synExpireRatePct(100, 1));
    }

    /**
     * A SYN enqueued in one window and reaped in the next counts as expired here
     * without having been processed here, so the raw ratio can exceed 1. The
     * documented range is [0,100], so it must be clamped.
     */
    @Test
    public void testRateIsClampedAtOneHundred() {
        assertEquals(100, ConnectionHandler.synExpireRatePct(1, 2));
        assertEquals(100, ConnectionHandler.synExpireRatePct(10, 1000));
        assertEquals(100, ConnectionHandler.synExpireRatePct(1, Integer.MAX_VALUE));
    }

    /** The clamp threshold the metric is compared against must be reachable. */
    @Test
    public void testThresholdIsReachable() {
        int atThreshold = ConnectionHandler.synExpireRatePct(100, ConnectionHandler.SYN_EXPIRE_THRESHOLD_DEFAULT);
        assertEquals(ConnectionHandler.SYN_EXPIRE_THRESHOLD_DEFAULT, atThreshold);
        for (int processed = 1; processed <= 500; processed++) {
            for (int expired = 0; expired <= processed * 2; expired += 7) {
                int rate = ConnectionHandler.synExpireRatePct(processed, expired);
                assertTrue("rate " + rate + " out of range", rate == -1 || (rate >= 0 && rate <= 100));
            }
        }
    }
}
