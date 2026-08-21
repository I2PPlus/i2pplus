package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pending next-hop lookup stale-window calculation used by
 * BuildHandler.drainPendingLookups(): the window must fit inside the
 * originator's build request timeout, with a fallback when the arguments
 * are degenerate.
 *
 * @since 0.9.71+
 */
public class BuildHandlerPendingLookupTest {

    @Test
    public void testNormalDefaults() {
        // 15s request timeout, 3s lookup timeout -> 12s stale window
        assertEquals(12000, BuildHandler.pendingLookupMaxAge(15000, 3000));
    }

    @Test
    public void testAdaptiveRequestTimeout() {
        // adaptive timeout range is 10-18s; window tracks it
        assertEquals(7000, BuildHandler.pendingLookupMaxAge(10000, 3000));
        assertEquals(15000, BuildHandler.pendingLookupMaxAge(18000, 3000));
    }

    @Test
    public void testEqualTimeoutsFallsBack() {
        // no margin left; fall back to twice the lookup timeout
        assertEquals(6000, BuildHandler.pendingLookupMaxAge(3000, 3000));
    }

    @Test
    public void testInvertedTimeoutsFallsBack() {
        assertEquals(6000, BuildHandler.pendingLookupMaxAge(2000, 3000));
    }

    @Test
    public void testZeroAndNegativeInputsStayPositive() {
        assertEquals(1, BuildHandler.pendingLookupMaxAge(0, 0));
        assertEquals(1, BuildHandler.pendingLookupMaxAge(-5000, -2000));
        assertEquals(4000, BuildHandler.pendingLookupMaxAge(0, 2000));
    }
}
