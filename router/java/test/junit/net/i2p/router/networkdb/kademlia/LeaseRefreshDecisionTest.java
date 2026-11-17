package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A floodfill's main netDb does not search for the lease sets it is about to
 * lose: those searches mark the other floodfills as recently queried for a
 * minute, and the next store then has nobody to flood to. A client
 * sub-database keeps refreshing, since it holds somebody else's entry and needs
 * continuity of service.
 *
 * @since 0.9.72+
 */
public class LeaseRefreshDecisionTest {

    /** Holds no state; each case calls the decision directly. */
    public LeaseRefreshDecisionTest() {}

    /** A floodfill's main netDb leaves its own expiring entries alone. */
    @Test
    public void floodfillMainDbDoesNotRefresh() {
        assertFalse(ExpireLeasesJob.refreshesExpiringLeaseSets(false, true));
    }

    /** A client sub-database refreshes even on a floodfill. */
    @Test
    public void clientDbAlwaysRefreshes() {
        assertTrue(ExpireLeasesJob.refreshesExpiringLeaseSets(true, true));
        assertTrue(ExpireLeasesJob.refreshesExpiringLeaseSets(true, false));
    }

    /** A router that is not a floodfill keeps refreshing its entries. */
    @Test
    public void nonFloodfillRefreshes() {
        assertTrue(ExpireLeasesJob.refreshesExpiringLeaseSets(false, false));
    }
}
