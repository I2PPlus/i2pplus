package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Math tests for the streaming throughput ring.
 *
 * <p>The stream lifetime byte stats record each closed connection's total
 * bytes weighted by its lifetime, so the rate is avgValue/avgDuration - the
 * old ring plotted the average lifetime byte count as if it were bytes/sec,
 * inflating long-lived connections by their own lifetime.
 *
 * @since 0.9.71+
 */
public class StreamHelperThroughputTest {

    private static final double EPS = 0.0001;

    @Test
    public void testRateFromValueAndDuration() {
        // 5 MB transferred over 10 s = 500 KB/s
        assertEquals(500_000, StreamHelper.closedStreamRateBps(5_000_000, 10_000), EPS);
    }

    @Test
    public void testLongLivedConnectionNotInflated() {
        // 100 MB over 1000 s is 100 KB/s, not 100 MB
        assertEquals(100_000, StreamHelper.closedStreamRateBps(100_000_000, 1_000_000), EPS);
    }

    @Test
    public void testMissingDataIsZero() {
        assertEquals(0, StreamHelper.closedStreamRateBps(0, 10_000), EPS);
        assertEquals(0, StreamHelper.closedStreamRateBps(5_000_000, 0), EPS);
        assertEquals(0, StreamHelper.closedStreamRateBps(-1, 10_000), EPS);
        assertEquals(0, StreamHelper.closedStreamRateBps(5_000_000, Double.NaN), EPS);
        assertEquals(0, StreamHelper.closedStreamRateBps(5_000_000, Double.POSITIVE_INFINITY), EPS);
    }
}
