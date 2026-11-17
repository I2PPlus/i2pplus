package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A new RouterInfo that declares itself fast is admitted past the fill-level
 * drop probability only up to a ceiling. The bandwidth tier and the version
 * that qualify it are written by the entry's own signer, so minted identities
 * all claim them, and an unbounded exemption stores them on every router that
 * sees one with no admission check at all.
 *
 * @since 0.9.72+
 */
public class StoreAdmissionTest {

    /** Holds no state; each case calls the decision directly. */
    public StoreAdmissionTest() {}

    /**
     * Below the soft limit a fast entry is admitted, and it stays admitted up to
     * the ceiling, which is what the exemption exists for: keeping reachable
     * fast routers in the netDb while it fills.
     */
    @Test
    public void fastEntriesAreAdmittedUpToTheCeiling() {
        assertTrue(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(true, false, 0));
        assertTrue(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(true, false,
                                                                                HandleFloodfillDatabaseStoreMessageJob.LIMIT_ROUTERS));
        assertTrue(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(true, false,
                                                                                2 * HandleFloodfillDatabaseStoreMessageJob.LIMIT_ROUTERS));
    }

    /**
     * One entry past the ceiling the exemption stops, and the entry faces the
     * same drop probability as every other new one - the bound that keeps a
     * stream of minted identities from filling the heap.
     */
    @Test
    public void fastEntriesFaceTheThrottlePastTheCeiling() {
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(
                      true, false, 2 * HandleFloodfillDatabaseStoreMessageJob.LIMIT_ROUTERS + 1));
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(
                      true, false, Integer.MAX_VALUE));
    }

    /**
     * The exemption needs both claims: a slow tier, or a version below the
     * minimum, gets no exemption at any fill level.
     */
    @Test
    public void onlyAFastCurrentEntryIsExempt() {
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(false, false, 0));
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(true, true, 0));
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.bypassesStoreThrottle(false, true, 0));
    }
}
