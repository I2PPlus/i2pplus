package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the recovery-liveness backstop and retransmit pacing decisions.
 *
 * <p>The stuck-packet zombie is "an unacked packet that is cancelled (on reset
 * or close) but never removed from the window map". No give-up branch can count
 * it (getNumSends() is frozen), so the RTO timer fires into a no-op forever.
 * {@link Connection#stuckLifetimeExceeded(int, int, long, long)} is the pure
 * predicate behind the hard liveness backstop that force-closes such a
 * connection. {@link Connection#shouldPaceRetx(int, int)} pins the "burst then
 * pace; recovery-critical packets never pace" rule.
 *
 * @since 0.9.72
 */
public class ConnectionRecoveryDecisionTest {

    // ---- stuckLifetimeExceeded ----

    /** A packet whose lifetime matches the worst-case budget is not yet stuck. */
    @Test
    public void testAtBudgetBoundaryNotStuck() {
        assertEquals(false, Connection.stuckLifetimeExceeded(6, 30000, 100_000, 100_000 - 6L * 30000));
        // exactly budget -> not stuck (strict inequality)
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, 280_000, 100_000));
    }

    /** A packet in flight beyond the worst-case budget is stuck. */
    @Test
    public void testBeyondBudgetIsStuck() {
        assertTrue(Connection.stuckLifetimeExceeded(6, 30000, 280_001, 100_000));
        assertTrue(Connection.stuckLifetimeExceeded(6, 30000, 300_000, 100_000));
    }

    /** A packet with any progress within the budget is not stuck. */
    @Test
    public void testWithinBudgetNotStuck() {
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, 179_999, 100_000));
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, 100_000, 100_000));
    }

    /** Never-sent or unknown packets are never declared stuck. */
    @Test
    public void testUnknownTimesNotStuck() {
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, 500_000, 0));
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, 500_000, -1));
    }

    /** Degenerate configuration never triggers (protects against divide-by-zero-style configs). */
    @Test
    public void testDegenerateConfigNeverStuck() {
        assertFalse(Connection.stuckLifetimeExceeded(0, 30000, 500_000, 100_000));
        assertFalse(Connection.stuckLifetimeExceeded(-1, 30000, 500_000, 100_000));
        assertFalse(Connection.stuckLifetimeExceeded(6, 0, 500_000, 100_000));
        assertFalse(Connection.stuckLifetimeExceeded(6, -1, 500_000, 100_000));
    }

    /** The comparison is exact-inequality so a live path on the boundary survives. */
    @Test
    public void testLivePathOnBoundarySurvives() {
        long lastSend = 100_000;
        long budget = 6L * 30000;
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, lastSend + budget, lastSend));
        assertTrue(Connection.stuckLifetimeExceeded(6, 30000, lastSend + budget + 1, lastSend));
    }

    // ---- shouldPaceRetx ----

    /** The first IMMEDIATE_RETX_BURST packets always send directly. */
    @Test
    public void testFirstBurstGoesDirect() {
        for (int n = 0; n <= 3; n++) {
            for (int b = 0; b < Connection.IMMEDIATE_RETX_BURST; b++) {
                assertFalse("burstCount " + b + " nResends " + n + " should be direct",
                            Connection.shouldPaceRetx(b, n));
            }
        }
    }

    /** Beyond the burst, ordinary packets are paced. */
    @Test
    public void testBeyondBurstPaces() {
        assertTrue(Connection.shouldPaceRetx(Connection.IMMEDIATE_RETX_BURST, 1));
        assertTrue(Connection.shouldPaceRetx(10, 2));
        assertTrue(Connection.shouldPaceRetx(Connection.IMMEDIATE_RETX_BURST, 3));
    }

    /** Recovery-critical packets (MAX_PACED_RETX+ transmissions) never pace. */
    @Test
    public void testRecoveryCriticalNeverPaces() {
        for (int b = Connection.IMMEDIATE_RETX_BURST; b < 50; b += 5) {
            assertFalse("burstCount " + b + " nResends >= MAX must be direct",
                        Connection.shouldPaceRetx(b, Connection.MAX_PACED_RETX));
            assertFalse(Connection.shouldPaceRetx(b, Connection.MAX_PACED_RETX + 5));
        }
    }

    /** Boundary: exactly MAX_PACED_RETX transmissions switches to direct. */
    @Test
    public void testMaxPacedBoundary() {
        assertTrue(Connection.shouldPaceRetx(10, Connection.MAX_PACED_RETX - 1));
        assertFalse(Connection.shouldPaceRetx(10, Connection.MAX_PACED_RETX));
    }

    /** Constants are coherent: burst number and recovery threshold are positive. */
    @Test
    public void testConstantsCoherent() {
        assertTrue(Connection.IMMEDIATE_RETX_BURST > 0);
        assertTrue(Connection.MAX_PACED_RETX > 0);
        assertEquals(Connection.IMMEDIATE_RETX_BURST, 4);
        assertEquals(Connection.MAX_PACED_RETX, 4);
    }
}
