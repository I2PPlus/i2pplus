package net.i2p.client.streaming.impl;

import java.util.Properties;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the explicit stall give-up budget, {@code i2p.streaming.stallGiveupMs}.
 *
 * <p>Why it was split out of {@code maxResends x maxRTO}: that product made one
 * loss-tolerance knob silently double as the connection lifetime. {@code maxResends}
 * is the per-<em>packet</em> budget; with the 8s RTO cap it produced a 4-minute
 * hold on a stream making no forward progress. While the client tunnel pool is
 * short of healthy tunnels every second of that hold is a second of capacity a live
 * stream cannot get, and the reconnects it provokes starve the pool further.
 *
 * @since 0.9.71+
 */
public class ConnectionStallGiveupBudgetTest {

    private static final String KEY = "i2p.streaming.stallGiveupMs";

    @Test
    public void defaultIsNinetySeconds() {
        ConnectionOptions opts = new ConnectionOptions();
        assertEquals(90_000L, opts.getStallGiveupMs());
    }

    @Test
    public void defaultMatchesTheAdvertisedConstant() {
        assertEquals(ConnectionOptions.DEFAULT_STALL_GIVEUP_MS,
                     new ConnectionOptions().getStallGiveupMs());
    }

    @Test
    public void theDefaultIsWellUnderTheOldDerivedBudget() {
        // The behaviour being replaced: 30 sends * 8s RTO cap.
        long old = 30L * ConnectionOptions.getMaxRTOStatic();
        assertTrue("the new budget must actually shorten the hold",
                   ConnectionOptions.DEFAULT_STALL_GIVEUP_MS < old);
    }

    @Test
    public void explicitValueIsHonoured() {
        Properties p = new Properties();
        p.setProperty(KEY, "45000");
        ConnectionOptions opts = new ConnectionOptions();
        opts.setStallGiveupMs(45000L);
        assertEquals(45000L, opts.getStallGiveupMs());
    }

    @Test
    public void setterClampsNegativesToZeroMeaningDisabled() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.setStallGiveupMs(-1L);
        assertEquals(0L, opts.getStallGiveupMs());
        opts.setStallGiveupMs(Long.MIN_VALUE);
        assertEquals(0L, opts.getStallGiveupMs());
    }

    @Test
    public void aZeroBudgetDisablesTheBackstopEntirely() {
        long createdOn = 100_000L;
        assertFalse("zero means disabled, not 'always stuck'",
                    Connection.stuckLifetimeExceeded(0L, 9_999_999L, createdOn));
    }

    @Test
    public void aNegativeBudgetDisablesTheBackstop() {
        assertFalse(Connection.stuckLifetimeExceeded(-1L, 9_999_999L, 100_000L));
    }

    /////////////// the predicate itself, against the new default

    @Test
    public void notStuckBeforeTheBudget() {
        assertFalse(Connection.stuckLifetimeExceeded(90_000L, 100_000L + 89_999L, 100_000L));
    }

    @Test
    public void stuckAfterTheBudget() {
        assertTrue(Connection.stuckLifetimeExceeded(90_000L, 100_000L + 90_001L, 100_000L));
    }

    @Test
    public void exactlyAtTheBoundaryIsNotStuck() {
        assertFalse("strict inequality: a live path on the boundary survives",
                    Connection.stuckLifetimeExceeded(90_000L, 190_000L, 100_000L));
    }

    @Test
    public void unknownCreationTimeIsNeverStuck() {
        assertFalse(Connection.stuckLifetimeExceeded(90_000L, 500_000L, 0L));
        assertFalse(Connection.stuckLifetimeExceeded(90_000L, 500_000L, -1L));
    }

    /**
     * Repeated retransmission must not extend the deadline: an old packet being
     * actively resent is still dead once its creation age passes the budget.
     */
    @Test
    public void resendsDoNotExtendTheDeadline() {
        long createdOn = 100_000L;
        assertTrue(Connection.stuckLifetimeExceeded(90_000L, 600_000L, createdOn));
        assertFalse(Connection.stuckLifetimeExceeded(90_000L, 600_000L, 599_000L));
    }

    @Test
    public void toStringMentionsTheBudget() {
        assertTrue("the budget should be visible in the option dump",
                   new ConnectionOptions().toString().contains("stallGiveupMs="));
    }
}