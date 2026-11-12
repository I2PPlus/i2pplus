package net.i2p.router.networkdb.reseed;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the per-URL fetch budget a reseed host is given. EepGet has no total
 * timeout of its own, so one silent host used to hold up the whole reseed
 * attempt; the budget has to be tight enough to move on, and loose enough for a
 * 1 MB download over a slow tunnel.
 *
 * @since 0.9.72+
 */
public class ReseedFetchBudgetTest {

    /** EepGet's own connect timeout, in ms - the budget has to be tighter. */
    private static final int EEPGET_CONNECT_TIMEOUT = 90 * 1000;

    @Test
    public void testHeaderBudgetIsTighterThanTheEepGetDefault() {
        assertTrue("a reseed must not wait out the default connect timeout",
                   Reseeder.RESEED_HEADER_TIMEOUT < EEPGET_CONNECT_TIMEOUT);
        assertTrue("a header budget under 15s will fail on a slow tunnel",
                   Reseeder.RESEED_HEADER_TIMEOUT >= 15 * 1000);
    }

    @Test
    public void testBudgetsAreOrdered() {
        assertTrue(Reseeder.RESEED_INACTIVITY_TIMEOUT > 0);
        assertTrue("an inactivity gap longer than the total budget can never fire",
                   Reseeder.RESEED_INACTIVITY_TIMEOUT < Reseeder.RESEED_TOTAL_TIMEOUT);
        assertTrue("the total budget has to cover the header wait",
                   Reseeder.RESEED_HEADER_TIMEOUT < Reseeder.RESEED_TOTAL_TIMEOUT);
    }

    /**
     * A reseed tries a list of hosts in sequence, so the per-host budget is what
     * decides whether a bad day of hosts still finishes in time for the next
     * reseed. Ten stalled hosts must still leave hours of headroom.
     */
    @Test
    public void testTenStalledHostsStillLeaveHeadroom() {
        long worstCase = 10L * Reseeder.RESEED_TOTAL_TIMEOUT;
        assertTrue("ten stalled hosts must fit in an hour", worstCase < 60 * 60 * 1000L);
    }

    /**
     * The HTTPS path is capped with the same ceiling as the HTTP path, which is
     * the size the SU3 signature is checked against.
     */
    @Test
    public void testSu3CeilingIsOneMegabyte() {
        assertEquals(1024 * 1024L, Reseeder.MAX_SU3_RESPONSE_SIZE);
    }
}
