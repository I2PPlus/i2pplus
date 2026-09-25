package net.i2p.router.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Claim semantics for LeaseRequestState: the single resolution claim that
 * keeps onFailed and the timeout stats from firing twice when several paths
 * race, and the bounded retry claim for expired-published LeaseSets.
 */
public class LeaseRequestStateTest {

    /** The resolution claim is granted exactly once per request. */
    @Test
    public void resolutionClaimIsSingleUse() {
        LeaseRequestState state = new LeaseRequestState(null, null, 0, 0, null);
        assertTrue(state.claimCheckResolution());
        assertFalse(state.claimCheckResolution());
        assertFalse(state.claimCheckResolution());
    }

    /** Expired-publish retries stop at the bound so a skewed clock cannot loop. */
    @Test
    public void transientPublishRetryAllowsTheBoundThenStops() {
        LeaseRequestState state = new LeaseRequestState(null, null, 0, 0, null);
        assertEquals(3, LeaseRequestState.MAX_TRANSIENT_PUBLISH_RETRIES);
        assertTrue(state.claimTransientPublishRetry());
        assertTrue(state.claimTransientPublishRetry());
        assertTrue(state.claimTransientPublishRetry());
        assertFalse(state.claimTransientPublishRetry());
        assertFalse(state.claimTransientPublishRetry());
    }

    /** The two claims are independent pools; consuming one does not consume the other. */
    @Test
    public void claimsAreIndependent() {
        LeaseRequestState state = new LeaseRequestState(null, null, 0, 0, null);
        assertTrue(state.claimTransientPublishRetry());
        assertTrue(state.claimCheckResolution());
        // retry pool still has room
        assertTrue(state.claimTransientPublishRetry());
        // but the resolution pool is spent
        assertFalse(state.claimCheckResolution());
    }
}
