package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The RouterInfo predicates behind the ban toggles on /configban: a version
 * above every released version, and a capability string that claims to be
 * reachable and unreachable at once. Both single-source a rule the NTCP2
 * handshake, the floodfill store path and the data store all rely on, so the
 * boundary values are pinned here.
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
}
