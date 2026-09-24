package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the earliest-keyed LeaseSet refresh defer decision
 * ({@link TunnelPool#shouldDeferPublishedRefresh(long, long, long)}).
 * Keying on earliest (not latest) is what makes in-use re-mint fire before
 * the first lease drains capacity, while still deferring when every lease
 * outlasts the throttle window.
 *
 * @since 0.9.71+
 */
public class LeaseSetRefreshDeferTest {

    private static final long THROTTLE = 2L * 60 * 1000;

    @Test
    public void earliestBeyondThrottleDefers() {
        long now = 1_000_000L;
        assertTrue(TunnelPool.shouldDeferPublishedRefresh(now + THROTTLE, now, THROTTLE));
        assertTrue(TunnelPool.shouldDeferPublishedRefresh(now + THROTTLE + 1, now, THROTTLE));
        assertTrue(TunnelPool.shouldDeferPublishedRefresh(now + 10 * 60 * 1000L, now, THROTTLE));
    }

    @Test
    public void earliestInsideThrottleForces() {
        long now = 1_000_000L;
        assertFalse(TunnelPool.shouldDeferPublishedRefresh(now + THROTTLE - 1, now, THROTTLE));
        assertFalse(TunnelPool.shouldDeferPublishedRefresh(now, now, THROTTLE));
        assertFalse(TunnelPool.shouldDeferPublishedRefresh(now - 1, now, THROTTLE));
    }

    /**
     * Latest still healthy but earliest drained: must NOT defer.
     * This is the bug that latest-keying introduced.
     */
    @Test
    public void latestHealthyDoesNotMaskDrainedEarliest() {
        long now = 1_000_000L;
        long latest = now + 9 * 60 * 1000L;
        long earliest = now + 30 * 1000L;
        // decision must use earliest, not latest
        assertFalse(TunnelPool.shouldDeferPublishedRefresh(earliest, now, THROTTLE));
        // if a caller accidentally passed latest it would defer — pinned here
        // as the contrast case for the earliest path above
        assertTrue(TunnelPool.shouldDeferPublishedRefresh(latest, now, THROTTLE));
    }
}
