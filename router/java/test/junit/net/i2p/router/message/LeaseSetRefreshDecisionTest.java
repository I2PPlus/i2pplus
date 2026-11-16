package net.i2p.router.message;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;

/**
 * Tests the decisions behind refreshing an expiring LeaseSet: when the local copy
 * is old enough to be worth re-searching, and when a refresh is already marked so
 * a further send must not start another search.
 *
 * <p>A live router logged 422 sends made with an expired LeaseSet across nine log
 * rotations, every one of them 7-29s past the lease expiry, and the previous
 * trigger (expired more than 90s ago) fired for none of them - so nothing was
 * ever refreshed and each send went out on a dead lease. The trigger is now one
 * search-time ahead of expiry, bounded by a per-destination marker, because the
 * NetDb facade deliberately does not deduplicate client-initiated searches.
 *
 * @since 0.9.72+
 */
public class LeaseSetRefreshDecisionTest {

    /** A typical lookup timeout: the Tuner search cap plus the job's grace. */
    private static final long LEAD = 12 * 1000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; both decisions are pure, so there is nothing to set up.
     *
     * @since 0.9.72+
     */
    public LeaseSetRefreshDecisionTest() {}

    // ---- leaseSetRefreshNeeded ----

    /** A lease that expires inside the search time is refreshed now. */
    @Test
    public void testExpiresInsideLead() {
        long now = 1_000_000L;
        assertTrue(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now + LEAD, now, LEAD));
        assertTrue(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now + 1000L, now, LEAD));
    }

    /** A lease with more than the search time left is left alone. */
    @Test
    public void testFreshLeaseNotRefreshed() {
        long now = 1_000_000L;
        assertFalse(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now + LEAD + 1, now, LEAD));
        assertFalse(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now + 10 * 60 * 1000L, now, LEAD));
    }

    /** An already-expired lease is refreshed - the measured 7-29s-past case. */
    @Test
    public void testExpiredLeaseRefreshed() {
        long now = 1_000_000L;
        assertTrue(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now, now, LEAD));
        assertTrue(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now - 29 * 1000L, now, LEAD));
    }

    /**
     * The measured live distribution: every stale send was 7-29s past expiry, so
     * all of them must take the refresh path, while a lease refreshed a minute
     * early must not.
     */
    @Test
    public void testMeasuredStaleWindowAllRefresh() {
        long now = 1_000_000L;
        for (int age = 7; age <= 29; age++)
            assertTrue("age=" + age,
                       OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now - age * 1000L, now, LEAD));
    }

    /** A zero lead only refreshes what is already expired. */
    @Test
    public void testZeroLeadRefreshesOnlyExpired() {
        long now = 1_000_000L;
        assertTrue(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now, now, 0L));
        assertFalse(OutboundClientMessageOneShotJob.leaseSetRefreshNeeded(now + 1, now, 0L));
    }

    // ---- leaseSetRefreshInFlight ----

    /** No marker means the next send may start the search. */
    @Test
    public void testNoMarkerAllowsRefresh() {
        assertFalse(OutboundCache.leaseSetRefreshInFlight(null, 1_000_000L));
    }

    /** A marker in force suppresses a second search for the same destination. */
    @Test
    public void testMarkerInForceSuppresses() {
        assertTrue(OutboundCache.leaseSetRefreshInFlight(Long.valueOf(1_012_000L), 1_000_000L));
    }

    /** The marker lapses with the search window, so a failed refresh is retried. */
    @Test
    public void testMarkerExpiresWithTheWindow() {
        assertFalse(OutboundCache.leaseSetRefreshInFlight(Long.valueOf(1_000_000L), 1_000_000L));
        assertFalse(OutboundCache.leaseSetRefreshInFlight(Long.valueOf(999_000L), 1_000_000L));
    }

    // ---- fetchableLeaseSet ----

    /** A v1 LeaseSet always has a public copy to search for. */
    @Test
    public void testV1AlwaysFetchable() {
        assertTrue(OutboundClientMessageOneShotJob.fetchableLeaseSet(new LeaseSet()));
    }

    /**
     * A published v2 LeaseSet is fetchable; one that is unpublished and not
     * blinded-when-published has no public copy in the network, so searching for
     * it only pays for the round trip.
     */
    @Test
    public void testV2FetchableOnlyWithAPublicCopy() {
        LeaseSet2 ls2 = new LeaseSet2();
        assertTrue(OutboundClientMessageOneShotJob.fetchableLeaseSet(ls2));
        ls2.setUnpublished();
        assertFalse(OutboundClientMessageOneShotJob.fetchableLeaseSet(ls2));
        ls2.setBlindedWhenPublished();
        assertTrue(OutboundClientMessageOneShotJob.fetchableLeaseSet(ls2));
    }
}
