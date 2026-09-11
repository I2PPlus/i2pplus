package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the recovery-liveness backstop and retransmit pacing decisions.
 *
 * <p>{@link Connection#stuckLifetimeExceeded(int, int, long, long)} is the pure
 * predicate behind the hard liveness backstop that force-closes a dead
 * connection. The deadline is anchored at the oldest unacked packet's CREATION
 * (not its last transmission), so an established connection in resume mode —
 * which keeps retransmitting a budget-exhausted head-of-line packet every RTO,
 * refreshing its last send time — still gets a fixed wall-clock deadline
 * instead of retrying a dead path forever. {@link Connection#shouldPaceRetx(int,
 * int)} pins the "burst then pace; recovery-critical packets never pace" rule.
 *
 * @since 0.9.71+
 */
public class ConnectionRecoveryDecisionTest {

    // ---- stuckLifetimeExceeded ----

    /** A packet whose lifetime matches the worst-case budget is not yet stuck. */
    @Test
    public void testAtBudgetBoundaryNotStuck() {
        long createdOn = 100_000;
        // exactly budget of lifetime -> not stuck (strict inequality)
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, createdOn + 6L * 30000, createdOn));
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
        long createdOn = 100_000;
        long budget = 6L * 30000;
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, createdOn + budget, createdOn));
        assertTrue(Connection.stuckLifetimeExceeded(6, 30000, createdOn + budget + 1, createdOn));
    }

    /** Creation anchoring: frequent retransmissions (fresh lastSend) cannot
     *  extend the deadline — an old packet being actively resent is still dead
     *  once its CREATION age exceeds the budget. */
    @Test
    public void testRepeatedResendsDoNotExtendDeadline() {
        long createdOn = 100_000;
        long now = 600_000;
        // Actively resent a moment ago, but created beyond the budget -> stuck.
        assertTrue(Connection.stuckLifetimeExceeded(6, 30000, now, createdOn));
        // Created recently -> not stuck even if last transmission was a while ago.
        assertFalse(Connection.stuckLifetimeExceeded(6, 30000, now, now - 1_000));
    }

    // ---- remoteSilentTooLong ----

    /** A remote that reached the exact inactivity boundary is not yet silent. */
    @Test
    public void testAtInactivityBoundaryNotSilent() {
        long lastReceivedOn = 100_000;
        // exactly inactivity timeout ago -> not silent (strict inequality)
        assertFalse(Connection.remoteSilentTooLong(lastReceivedOn, 60_000, 160_000));
        assertFalse(Connection.remoteSilentTooLong(lastReceivedOn, 120_000, 220_000));
    }

    /** A remote silent beyond the full inactivity window is too long. */
    @Test
    public void testBeyondInactivityIsSilent() {
        assertTrue(Connection.remoteSilentTooLong(100_000, 120_000, 220_001));
        assertTrue(Connection.remoteSilentTooLong(100_000, 120_000, 240_000));
    }

    /** A remote that sent anything within the window is not silent. */
    @Test
    public void testWithinWindowNotSilent() {
        assertFalse(Connection.remoteSilentTooLong(100_000, 120_000, 220_000));
        assertFalse(Connection.remoteSilentTooLong(100_000, 120_000, 120_000));
        assertFalse(Connection.remoteSilentTooLong(100_000, 120_000, 100_000));
    }

    /** Connect-phase connections (nothing ever received) are never declared
     *  silent — they are bounded separately by the SYN give-up budget. */
    @Test
    public void testNeverReceivedNotSilent() {
        assertFalse(Connection.remoteSilentTooLong(-1, 120_000, 500_000));
        assertFalse(Connection.remoteSilentTooLong(0, 120_000, 500_000));
    }

    /** Degenerate configuration never triggers. */
    @Test
    public void testDegenerateConfigNeverSilent() {
        assertFalse(Connection.remoteSilentTooLong(100_000, 0, 500_000));
        assertFalse(Connection.remoteSilentTooLong(100_000, -1, 500_000));
    }

    /** The comparison is exact-inequality so a connection on the boundary survives. */
    @Test
    public void testLiveConnectionOnBoundarySurvives() {
        long lastReceivedOn = 100_000;
        long window = 120_000;
        assertFalse(Connection.remoteSilentTooLong(lastReceivedOn, (int) window, lastReceivedOn + window));
        assertTrue(Connection.remoteSilentTooLong(lastReceivedOn, (int) window, lastReceivedOn + window + 1));
    }

    /** Receive anchoring: retransmitting (a fresh last SEND) does not extend the
     *  deadline — the connection is still dead once the remote has been silent
     *  for the full window. */
    @Test
    public void testResendsToSilentRemoteDoNotExtendDeadline() {
        long lastReceivedOn = 100_000;
        long now = 250_000;
        // Sending just now, but remote silent beyond the window -> silent.
        assertTrue(Connection.remoteSilentTooLong(lastReceivedOn, 120_000, now));
        // Remote sent something recently -> not silent even if we sent ages ago.
        assertFalse(Connection.remoteSilentTooLong(lastReceivedOn, 120_000, lastReceivedOn + 1_000));
    }

    // ---- effectiveInactivityTimeout (remote-silence fallback floor) ----

    /** A non-positive configured window falls back to the protocol default. */
    @Test
    public void testZeroConfiguredWindowFallsBack() {
        assertEquals(Connection.REMOTE_SILENT_FALLBACK_MS,
                     Connection.effectiveInactivityTimeout(0, Connection.REMOTE_SILENT_FALLBACK_MS));
        assertEquals(Connection.REMOTE_SILENT_FALLBACK_MS,
                     Connection.effectiveInactivityTimeout(-1, Connection.REMOTE_SILENT_FALLBACK_MS));
    }

    /** A positive configured window is honored as-is (never tightened). */
    @Test
    public void testPositiveWindowHonored() {
        assertEquals(120_000, Connection.effectiveInactivityTimeout(120_000, Connection.REMOTE_SILENT_FALLBACK_MS));
        assertEquals(300_000, Connection.effectiveInactivityTimeout(300_000, Connection.REMOTE_SILENT_FALLBACK_MS));
    }

    /** The remote-silence bound still disarms on a truly unknown last-received
     *  time even with the effective (floored) window. */
    @Test
    public void testBoundRequiresKnownLastReceived() {
        long now = 500_000;
        assertFalse(Connection.remoteSilentTooLong(0, Connection.REMOTE_SILENT_FALLBACK_MS, now));
        assertFalse(Connection.remoteSilentTooLong(-1, Connection.REMOTE_SILENT_FALLBACK_MS, now));
    }

    /** Wiring-level: a zero configured timeout no longer disarms the bound; the
     *  effective window floor is what gates it. */
    @Test
    public void testZeroConfigStillFiresBoundWithFallback() {
        long lastReceivedOn = 100_000;
        long now = lastReceivedOn + Connection.REMOTE_SILENT_FALLBACK_MS + 1;
        int effective = Connection.effectiveInactivityTimeout(0, Connection.REMOTE_SILENT_FALLBACK_MS);
        assertTrue(Connection.remoteSilentTooLong(lastReceivedOn, effective, now));
    }

    // ---- budgetExhaustionClosesConnection (resume, don't close) ----

    /** Established connections resume: budget exhaustion alone does not close. */
    @Test
    public void testEstablishedResumesOverClose() {
        assertFalse(Connection.budgetExhaustionClosesConnection(true, true));
    }

    /** Connect-phase connections (no forward progress) still close. */
    @Test
    public void testConnectPhaseClosesOnBudget() {
        assertTrue(Connection.budgetExhaustionClosesConnection(true, false));
    }

    /** A connection within its budget never closes on this decision. */
    @Test
    public void testWithinBudgetNeverCloses() {
        assertFalse(Connection.budgetExhaustionClosesConnection(false, false));
        assertFalse(Connection.budgetExhaustionClosesConnection(false, true));
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
