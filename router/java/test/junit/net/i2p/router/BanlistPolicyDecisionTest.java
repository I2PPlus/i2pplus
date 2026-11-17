package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The RouterInfo predicates behind the ban toggles on /configban: a version
 * above every released version, a capability string that claims to be reachable
 * and unreachable at once, and the low-tier-unreachable (LU) condition. All
 * three single-source a rule the NTCP2 handshake, the floodfill store path, the
 * data store and the request throttle all rely on, so the boundary values are
 * pinned here.
 *
 * @since 0.9.72+
 */
public class BanlistPolicyDecisionTest {

    // ---- isInvalidVersion ---------------------------------------------------

    @Test
    public void versionAtTheCeilingIsInvalid() {
        assertTrue(Banlist.isInvalidVersion(Banlist.MAX_VALID_VERSION));
    }

    @Test
    public void versionAboveTheCeilingIsInvalid() {
        assertTrue(Banlist.isInvalidVersion("3.0"));
        assertTrue(Banlist.isInvalidVersion("99.99.99"));
    }

    @Test
    public void highestPlausibleReleaseIsValid() {
        // One below the ceiling is still a real version, so a future release
        // close to the ceiling is not banned by this rule.
        assertFalse(Banlist.isInvalidVersion("2.4.99"));
        assertFalse(Banlist.isInvalidVersion("0.9.70"));
        assertFalse(Banlist.isInvalidVersion("1.0"));
    }

    @Test
    public void nullVersionIsNotInvalid() {
        // A missing version is the no-version rule's business, not this one.
        assertFalse(Banlist.isInvalidVersion(null));
    }

    // ---- publishesContradictoryCaps ----------------------------------------

    @Test
    public void bothReachabilityFlagsIsContradictory() {
        assertTrue(Banlist.publishesContradictoryCaps("RU"));
        assertTrue(Banlist.publishesContradictoryCaps("UR"));
        assertTrue(Banlist.publishesContradictoryCaps("PDUCXGR"));
    }

    @Test
    public void oneFlagIsNotContradictory() {
        assertFalse(Banlist.publishesContradictoryCaps("R"));
        assertFalse(Banlist.publishesContradictoryCaps("U"));
        assertFalse(Banlist.publishesContradictoryCaps("PDR"));
        assertFalse(Banlist.publishesContradictoryCaps("PDUX"));
    }

    @Test
    public void missingCapabilitiesAreNotContradictory() {
        assertFalse(Banlist.publishesContradictoryCaps(null));
        assertFalse(Banlist.publishesContradictoryCaps(""));
    }

    /** The two flags really are the letters the predicates look for. */
    @Test
    public void capabilityLettersMatchTheRouterConstants() {
        assertEquals('R', Router.CAPABILITY_REACHABLE);
        assertEquals('U', Router.CAPABILITY_UNREACHABLE);
    }

    // ---- isLowTierUnreachable ----------------------------------------------

    /**
     * BW12 ('K') and BW32 ('L') are the two tiers this rule calls low, with the
     * U flag a peer publishes when it never answered a handshake.
     */
    @Test
    public void bothLowTiersWithTheUnreachableFlagMatch() {
        assertTrue(Banlist.isLowTierUnreachable("DKU"));
        assertTrue(Banlist.isLowTierUnreachable("DLU"));
    }

    /**
     * A peer that publishes no reachability flag at all counts as unreachable:
     * the transports read a missing R as the same finding as the U flag, and
     * every ban site has always read it that way.
     */
    @Test
    public void noReachabilityFlagCountsAsUnreachable() {
        assertTrue(Banlist.isLowTierUnreachable("DK"));
        assertTrue(Banlist.isLowTierUnreachable("DL"));
    }

    /** A low tier peer that says it is reachable is not LU. */
    @Test
    public void aReachableLowTierPeerIsNotLu() {
        assertFalse(Banlist.isLowTierUnreachable("DKR"));
        assertFalse(Banlist.isLowTierUnreachable("DLR"));
    }

    /**
     * The tiers above BW32 are not low, so a peer that is merely slow and
     * firewalled is not banned by this rule. ParticipatingThrottler's low-share
     * test is deliberately wider than this one, and stays wider.
     */
    @Test
    public void higherTiersAreNotLowTier() {
        assertFalse(Banlist.isLowTierUnreachable("DMU"));   // BW64
        assertFalse(Banlist.isLowTierUnreachable("DNU"));   // BW128
        assertFalse(Banlist.isLowTierUnreachable("DOU"));   // BW256
        assertFalse(Banlist.isLowTierUnreachable("DPU"));   // BW512
        assertFalse(Banlist.isLowTierUnreachable("DXU"));   // unlimited
        assertFalse(Banlist.isLowTierUnreachable("DU"));    // no tier at all
    }

    /**
     * Publishing both reachability flags is contradictory, which is the other
     * predicate's business; here the U flag is present, so this rule reads the
     * peer as unreachable.
     */
    @Test
    public void theUnreachableFlagWinsOverAContradictoryPair() {
        assertTrue(Banlist.isLowTierUnreachable("DKUR"));
    }

    /** A peer that takes no transit is still judged on its tier, not on G. */
    @Test
    public void theNoTunnelsFlagDoesNotMakeAPeerLowTier() {
        assertFalse(Banlist.isLowTierUnreachable("DGU"));
        assertTrue(Banlist.isLowTierUnreachable("DGKU"));
    }

    /** No capability string at all cannot match a rule about capabilities. */
    @Test
    public void missingCapabilitiesAreNotLu() {
        assertFalse(Banlist.isLowTierUnreachable(null));
        assertFalse(Banlist.isLowTierUnreachable(""));
    }

    /**
     * The tier letters are the ones the Router constants define. 'L' is the
     * BW32 capability, and it is also the "L" tier string
     * RouterInfo.getBandwidthTier() returns for the same peer, which is how the
     * data store used to reach the same peer by a different spelling.
     */
    @Test
    public void tierLettersMatchTheRouterConstants() {
        assertEquals('K', Router.CAPABILITY_BW12);
        assertEquals('L', Router.CAPABILITY_BW32);
        assertEquals('M', Router.CAPABILITY_BW64);
    }
}
