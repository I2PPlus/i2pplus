package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the hard retransmit budget decision for resend give-up.
 *
 * <p>{@link Connection#hardResendBudgetExceeded(int, int, int)} decides whether
 * a packet has been sent enough times to give up. Resends tagged as
 * soft-failure-triggered (NO_TUNNELS / EXPIRED / LOCAL) are subtracted from the
 * total because such a message never reached the tunnel fabric and is therefore
 * not evidence of on-wire loss. Without the exemption, a tunnel handover that
 * outlives {@code maxResends} router-side soft failures aborts a healthy long
 * stream (the 30-storm kill observed on long-lived transfers).
 *
 * @since 0.9.72
 */
public class ConnectionHardResendBudgetTest {

    // ---- hardResendBudgetExceeded ----

    /** Exactly the budget of hard sends is not exceeded (strict inequality). */
    @Test
    public void testAtBudgetBoundaryNotExceeded() {
        assertFalse(Connection.hardResendBudgetExceeded(30, 0, 30));
        assertFalse(Connection.hardResendBudgetExceeded(1, 0, 1));
        assertFalse(Connection.hardResendBudgetExceeded(0, 0, 30));
    }

    /** One past the budget is exceeded. */
    @Test
    public void testBeyondBudgetExceeded() {
        assertTrue(Connection.hardResendBudgetExceeded(31, 0, 30));
        assertTrue(Connection.hardResendBudgetExceeded(6, 0, 5));
    }

    /** Soft-failure resends do not consume the hard budget. */
    @Test
    public void testSoftResendsOffsetBudget() {
        // a pure soft storm never gives up regardless of total sends
        assertFalse(Connection.hardResendBudgetExceeded(100, 70, 30));
        assertFalse(Connection.hardResendBudgetExceeded(60, 31, 30));
        // 60 sends, 30 of them soft -> 30 hard sends, exactly at budget
        assertFalse(Connection.hardResendBudgetExceeded(60, 30, 30));
    }

    /** Hard sends past the budget still give up even with many soft resends. */
    @Test
    public void testHardSendsBeyondBudgetExceeded() {
        // 31 hard sends among 61 total (30 soft) exceeds the budget
        assertTrue(Connection.hardResendBudgetExceeded(61, 30, 30));
    }

    /** Soft accounting clamped at zero: soft > total cannot undercount below 0. */
    @Test
    public void testSoftOvercountClampedToZero() {
        assertFalse(Connection.hardResendBudgetExceeded(5, 6, 30));
        assertFalse(Connection.hardResendBudgetExceeded(5, 500, 30));
    }

    /** Degenerate budgets: any positive hard send exceeds a zero/negative budget. */
    @Test
    public void testDegenerateBudget() {
        assertTrue(Connection.hardResendBudgetExceeded(1, 0, 0));
        assertTrue(Connection.hardResendBudgetExceeded(1, 0, -1));
        assertFalse(Connection.hardResendBudgetExceeded(0, 0, 0));
        assertFalse(Connection.hardResendBudgetExceeded(0, 1, 0));
    }

    /** First send is not soft: hard count equals total when nothing soft occurred. */
    @Test
    public void testNoSoftSendsHardEqualsTotal() {
        for (int n = 0; n <= 3; n++) {
            assertEquals(n > 30, Connection.hardResendBudgetExceeded(n, 0, 30));
        }
    }
}