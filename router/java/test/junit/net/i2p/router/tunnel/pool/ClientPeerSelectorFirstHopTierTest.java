package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the first-hop quality ladder.
 *
 * The ladder relaxes its reachability requirement as attempts accumulate, and
 * the relaxation is why a gateway can be selected with no transport session.
 * That is only explicable after the fact — an unreachable gateway looks
 * identical to a peer that went silent — so the accepted tier is now logged.
 * These tests pin the ladder that decides it.
 */
public class ClientPeerSelectorFirstHopTierTest {

    private static final int CONNECTING_PREF_ATTEMPTS = 5;

    /** Inside startup grace the ladder never advances, however many attempts. */
    @Test
    public void startupHoldsTheTierFlat() {
        for (int attempts = 1; attempts <= 16; attempts++) {
            assertEquals("startup must not relax the tier at attempt " + attempts,
                         1, ClientPeerSelector.firstHopQualityTier(attempts, true, 1));
        }
    }

    /**
     * The reachable-gate window: up to CONNECTING_PREF_ATTEMPTS the ladder
     * stays at or below tier 1, which is where {@code isEstablished} and
     * {@code isConnecting} are enforced.
     */
    @Test
    public void reachableGateHoldsForTheFirstAttempts() {
        int tier = 0;
        for (int attempts = 1; attempts <= CONNECTING_PREF_ATTEMPTS; attempts++) {
            tier = ClientPeerSelector.firstHopQualityTier(attempts, false, tier);
            assertTrue("attempt " + attempts + " reached tier " + tier +
                       ", which drops the transport-session requirement",
                       tier <= 1);
        }
    }

    /**
     * The defect: after the connecting-preference window the ladder reaches
     * tier 2, where nothing requires the gateway to have a transport session.
     * A peer selected there cannot receive a build request.
     */
    @Test
    public void ladderReachesUncheckedTier() {
        int tier = 0;
        for (int attempts = 1; attempts <= CONNECTING_PREF_ATTEMPTS; attempts++) {
            tier = ClientPeerSelector.firstHopQualityTier(attempts, false, tier);
        }
        assertEquals(1, tier);
        tier = ClientPeerSelector.firstHopQualityTier(CONNECTING_PREF_ATTEMPTS + 1, false, tier);
        assertEquals("one attempt past the window the gate is gone", 2, tier);
    }

    /** The unchecked tier is where the remaining attempts of the 16 land. */
    @Test
    public void mostOfTheBudgetSpentUnchecked() {
        int tier = 0;
        int unchecked = 0;
        for (int attempts = 1; attempts <= 16; attempts++) {
            tier = ClientPeerSelector.firstHopQualityTier(attempts, false, tier);
            if (tier >= 2) {unchecked++;}
        }
        assertEquals("11 of 16 attempts carry no transport requirement", 11, unchecked);
    }

    /** The current tier is the floor, so a relaxed ladder never tightens back. */
    @Test
    public void ladderNeverTightens() {
        int tier = ClientPeerSelector.firstHopQualityTier(16, false, 0);
        assertEquals(2, tier);
        assertEquals("a later attempt must not restore the gate", 2,
                     ClientPeerSelector.firstHopQualityTier(1, false, tier));
    }

    /** Attempts beyond both thresholds stay at tier 2 rather than escalating. */
    @Test
    public void ladderIsBoundedAtTwo() {
        int tier = 0;
        for (int attempts = 1; attempts <= 40; attempts++) {
            tier = ClientPeerSelector.firstHopQualityTier(attempts, false, tier);
            assertTrue("tier escaped the ladder at attempt " + attempts, tier <= 2);
        }
        assertEquals(2, tier);
    }
}
