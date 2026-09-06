package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the effective connect-timeout window and the scaled SYN give-up budget
 * in {@link Connection#computeEffectiveConnectTimeout(long, int, long)} and
 * {@link Connection#computeSynResendBudget(int, int, long)}.
 *
 * <p>These two helpers must agree on the connect window. The regression this
 * guards against: with RTT evidence the inter-SYN interval collapses toward
 * {@link Connection#SYN_RTO_MIN} (750ms), so a fixed {@code maxSynResends}
 * budget of 12 sends gives {@code 12 * 750ms = 9s} — <em>shorter</em> than the
 * 10000ms floor of the effective connect window. That let the retransmit timer
 * tear an outbound connection down error-free while
 * {@link Connection#waitForConnect(int)} was still waiting, so a live-but-slow
 * peer (e.g. an up IRC server) surfaced as the generic {@code "Connection failed"}
 * instead of connecting or reporting an accurate error. The budget must therefore
 * be scaled up so {@code budget * interval >= window}.
 *
 * @since 0.9.71+
 */
public class ConnectionSynBudgetTest {

    // --------------------------------------------------------------------
    // computeEffectiveConnectTimeout
    // --------------------------------------------------------------------

    /** Default path: 100% multiplier leaves the base window unchanged. */
    @Test
    public void testEffectiveTimeoutDefaultMultiplier() {
        assertEquals(30150, Connection.computeEffectiveConnectTimeout(30150, 100, 75000));
        assertEquals(30000, Connection.computeEffectiveConnectTimeout(30000, 100, 75000));
    }

    /** Fast-network multiplier scales down, floored at CONNECT_TIMEOUT_FLOOR_MS. */
    @Test
    public void testEffectiveTimeoutFloorApplied() {
        // 30150 * 30 / 100 = 9045, below the 10000 floor
        assertEquals(Connection.CONNECT_TIMEOUT_FLOOR_MS,
                     Connection.computeEffectiveConnectTimeout(30150, 30, 75000));
        // a small base with a 100% multiplier is also floored
        assertEquals(Connection.CONNECT_TIMEOUT_FLOOR_MS,
                     Connection.computeEffectiveConnectTimeout(5000, 100, 75000));
    }

    /** Slow-network multiplier scales up but is capped at the absolute maximum. */
    @Test
    public void testEffectiveTimeoutCapApplied() {
        // 30150 * 200 / 100 = 60300 (below the 75000 cap)
        assertEquals(60300, Connection.computeEffectiveConnectTimeout(30150, 200, 75000));
        // a huge base is capped, not wrapped
        assertEquals(75000, Connection.computeEffectiveConnectTimeout(100000, 200, 75000));
        assertEquals(75000, Connection.computeEffectiveConnectTimeout(50000, 175, 75000));
    }

    /** No configured timeout means no window at all. */
    @Test
    public void testEffectiveTimeoutNoTimeout() {
        assertEquals(0, Connection.computeEffectiveConnectTimeout(0, 100, 75000));
        assertEquals(0, Connection.computeEffectiveConnectTimeout(0, 200, 75000));
        assertEquals(0, Connection.computeEffectiveConnectTimeout(-5, 100, 75000));
    }

    /** Large bases must not overflow the long arithmetic. */
    @Test
    public void testEffectiveTimeoutNoOverflow() {
        long huge = Long.MAX_VALUE / 2;
        assertEquals(75000, Connection.computeEffectiveConnectTimeout(huge, 200, 75000));
    }

    // --------------------------------------------------------------------
    // computeSynResendBudget
    // --------------------------------------------------------------------

    /** The classic dead-path budget: 12 * 5s = 60s already fills the window, unchanged. */
    @Test
    public void testBudgetConfiguredWinsWhenSufficient() {
        // 30150ms window / 5000ms interval = 7 sends needed < 12 configured
        assertEquals(12, Connection.computeSynResendBudget(12, 5000, 30150));
        // exact fit: 12 sends * 5000ms = 60000ms >= 60000ms window
        assertEquals(12, Connection.computeSynResendBudget(12, 5000, 60000));
    }

    /** RTT-evidence interval at the 750ms floor MUST scale past the configured count. */
    @Test
    public void testBudgetScalesToCoverWindow() {
        // The regression: 12 * 750ms = 9s < 10s floor window.
        assertEquals(14, Connection.computeSynResendBudget(12, 750, 10000));
        // 30150ms window / 750ms interval = 41 sends (ceil)
        assertEquals(41, Connection.computeSynResendBudget(12, 750, 30150));
        // Tuner-scaled window at 200% multiplier: 60300 / 750 = 81 sends (ceil)
        assertEquals(81, Connection.computeSynResendBudget(12, 750, 60300));
    }

    /** Minimum configured count from the tuner clamp (3) still scales. */
    @Test
    public void testBudgetScalesFromFloorClamp() {
        assertEquals(41, Connection.computeSynResendBudget(3, 750, 30150));
    }

    /** Give-up boundary: budget must cover the window, one-ms over is enough to trigger the next send count. */
    @Test
    public void testBudgetBoundary() {
        // ceil to always cover: 60001/5000 -> 13
        assertEquals(13, Connection.computeSynResendBudget(12, 5000, 60001));
        // 10000/1000 = 10 exactly, below configured -> configured wins
        assertEquals(12, Connection.computeSynResendBudget(12, 1000, 10000));
        // 10001/1000 -> 11, below configured
        assertEquals(12, Connection.computeSynResendBudget(12, 1000, 10001));
    }

    /** Degenerate inputs pass the configured count through unchanged. */
    @Test
    public void testBudgetDegeneratesToConfigured() {
        assertEquals(12, Connection.computeSynResendBudget(12, 0, 30150));
        assertEquals(12, Connection.computeSynResendBudget(12, 750, 0));
        assertEquals(12, Connection.computeSynResendBudget(12, -750, 30150));
        assertEquals(0, Connection.computeSynResendBudget(0, 750, 30150));
        assertEquals(-1, Connection.computeSynResendBudget(-1, 750, 30150));
    }

    /** Invariant sweep: for any sane interval/window, budget * interval covers the window. */
    @Test
    public void testBudgetInvariantCoversWindow() {
        for (int interval : new int[] {750, 1000, 1500, 3000, 5000}) {
            for (long window : new long[] {10000, 30150, 60300, 75000}) {
                int budget = Connection.computeSynResendBudget(12, interval, window);
                assertTrue("budget " + budget + " * interval " + interval + " < window " + window,
                           (long) budget * interval >= window);
            }
        }
    }

    /** Full IRC-path simulation: effective window enters the budget and covers it. */
    @Test
    public void testIrcPathWindowFullyCovered() {
        // IRC forces connectDelay=150, default connectTimeout=30s -> base 30150ms.
        long window = Connection.computeEffectiveConnectTimeout(30150, 100, 75000);
        assertEquals(30150, window);
        // With fast RTT evidence the interval drops to the 750ms floor...
        assertEquals(Connection.SYN_RTO_MIN, Connection.computeSynRetransmitInterval(200, 5000));
        int budget = Connection.computeSynResendBudget(12, Connection.SYN_RTO_MIN, window);
        // ...and the budget must cover the entire window.
        assertTrue((long) budget * Connection.SYN_RTO_MIN >= window);
        // The disconnect-timeout gate (waitForConnect floor) must also be covered.
        assertTrue((long) budget * Connection.SYN_RTO_MIN >= Connection.CONNECT_TIMEOUT_FLOOR_MS);
    }
}