package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The tier admission and eviction rules that separate the two tiers.
 *
 * <p>High capacity is the reservoir the fast tier is seeded from, so its
 * <em>admission</em> deliberately ignores tunnel acceptance; acceptance decides
 * fast-tier membership instead. Eviction runs the other way and applies to both,
 * so a peer that declines too many build requests leaves whichever tier lists it.
 * These tests pin that asymmetry, because the failure mode when it collapses is
 * silent in both directions: admission that is too strict empties the reservoir,
 * and eviction that is too lax fills the fast tier with peers that waste builds.
 *
 * <p>The size guard is scaled to the known population, so a fixed count that
 * silently disables eviction on a small network is excluded.
 *
 * @since 0.9.71+
 */
public class TierAdmissionTest {

    // ---------------- soft-purge threshold scales with the population ----------------

    /**
     * The threshold tracks the population, so eviction cannot be switched off by
     * a constant that outgrew the network.
     */
    @Test
    public void testThresholdIsOneTenthOfKnownProfiles() {
        assertEquals(50, ProfileOrganizer.softPurgeThreshold(500));
        assertEquals(80, ProfileOrganizer.softPurgeThreshold(800));
    }

    /**
     * Capped, so a large network does not raise the bar past what any tier
     * reaches and leave eviction permanently inactive.
     */
    @Test
    public void testThresholdIsCappedAtOneHundred() {
        assertEquals(100, ProfileOrganizer.softPurgeThreshold(1000));
        assertEquals(100, ProfileOrganizer.softPurgeThreshold(50000));
        assertEquals(100, ProfileOrganizer.softPurgeThreshold(Integer.MAX_VALUE));
    }

    /**
     * Below the cap the fraction governs, so a small router is not asked to
     * muster a hundred peers it cannot have.
     */
    @Test
    public void testSmallNetworkUsesTheFractionNotTheCap() {
        assertEquals(10, ProfileOrganizer.softPurgeThreshold(100));
        assertEquals(20, ProfileOrganizer.softPurgeThreshold(200));
    }

    /**
     * An unknown population must not enable eviction: with nothing known there is
     * no evidence a tier is well stocked, and evicting on that basis is the
     * collapse this guard exists to prevent.
     */
    @Test
    public void testUnknownPopulationDisablesSoftEviction() {
        assertEquals(Integer.MAX_VALUE, ProfileOrganizer.softPurgeThreshold(0));
        assertEquals(Integer.MAX_VALUE, ProfileOrganizer.softPurgeThreshold(-5));
    }

    /** The current live shape: a 5,061-profile router with small tiers. */
    @Test
    public void testCurrentRouterSizesQualify() {
        int threshold = ProfileOrganizer.softPurgeThreshold(5061);
        assertEquals(100, threshold);
        assertTrue("fast tier of 441 should qualify", 441 >= threshold);
        assertTrue("high-cap of 693 should qualify", 693 >= threshold);
    }

    // ---------------- a starving tier is still protected ----------------

    /**
     * The guard's whole purpose: a tier below the threshold keeps its soft
     * failures rather than being emptied further.
     */
    @Test
    public void testStarvedTierStaysBelowThreshold() {
        int threshold = ProfileOrganizer.softPurgeThreshold(5061);
        assertFalse("a tier of 40 peers must not be turned over",
                    40 >= threshold);
    }

    // ---------------- admission and eviction are deliberately asymmetric ----------------

    /**
     * Documents the asymmetry in one place so a future change to either side is
     * a deliberate edit rather than an accident.
     *
     * <p>High-capacity admission passes {@code requireAcceptance = false}; fast
     * admission passes {@code true}. Eviction applies acceptance to both. If
     * admission for high capacity were tightened to match eviction, the tier
     * would hold only good accepters and there would be nothing left to seed the
     * fast tier from when a peer demotes.
     */
    @Test
    public void testAdmissionAndEvictionAsymmetryIsIntentional() {
        assertTrue("high-capacity admission must not require acceptance, or the " +
                   "reservoir it provides is filtered by the same criterion that " +
                   "empties the fast tier",
                   !ProfileOrganizer.HIGH_CAP_ADMISSION_REQUIRES_ACCEPTANCE);
        assertTrue("fast-tier admission must require acceptance",
                   ProfileOrganizer.FAST_ADMISSION_REQUIRES_ACCEPTANCE);
        assertTrue("eviction must apply acceptance to both tiers",
                   ProfileOrganizer.EVICTION_REQUIRES_ACCEPTANCE_BOTH_TIERS);
    }

    // ---------------- eviction reason precedence ----------------

    /**
     * A poor accepter is evicted when the tier can afford the turnover.
     *
     * <p>This is the path the live counters could not confirm: an all-zero
     * acceptance count is indistinguishable from a broken predicate, so the
     * predicate is pinned directly rather than inferred from the console.
     */
    @Test
    public void testLowAcceptanceEvictsWhenSoftPurgeIsAllowed() {
        assertEquals(ProfileOrganizer.PurgeReason.ACCEPTANCE,
                     ProfileOrganizer.purgeReasonFor(false, false, true, true,
                                                      false, false, true));
    }

    /**
     * The same peer is kept when the tier is below the threshold, which is the
     * whole purpose of the guard - a starved tier must not be turned over.
     */
    @Test
    public void testLowAcceptanceIsKeptWhenSoftPurgeIsBlocked() {
        assertNull("a starved tier must keep a merely poor accepter",
                   ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                   false, false, true));
    }

    /**
     * The categorical reasons apply at any tier size, including a starved one.
     *
     * <p>These are not judgements about performance; a banned or unreachable
     * peer is never a valid member whatever the tier size, so withholding its
     * eviction only leaves it selectable for another cycle.
     */
    @Test
    public void testCategoricalReasonsApplyEvenWhenSoftPurgeIsBlocked() {
        assertEquals(ProfileOrganizer.PurgeReason.BANNED,
                     ProfileOrganizer.purgeReasonFor(true, false, true, false,
                                                      true, true, true));
        assertEquals(ProfileOrganizer.PurgeReason.UNREACHABLE,
                     ProfileOrganizer.purgeReasonFor(false, true, true, false,
                                                      true, true, true));
        assertEquals(ProfileOrganizer.PurgeReason.NOT_CAPABLE,
                     ProfileOrganizer.purgeReasonFor(false, false, false, false,
                                                      true, true, true));
    }

    /**
     * Reason precedence is a reporting decision, not six independent checks: the
     * most actionable fact wins so the counter names something an operator can
     * act on rather than whichever test happened to be evaluated last.
     */
    @Test
    public void testReasonsAreReportedInPrecedenceOrder() {
        assertEquals("banned outranks everything",
                     ProfileOrganizer.PurgeReason.BANNED,
                     ProfileOrganizer.purgeReasonFor(true, true, false, true, true, true, true));
        assertEquals("unreachable outranks not-capable",
                     ProfileOrganizer.PurgeReason.UNREACHABLE,
                     ProfileOrganizer.purgeReasonFor(false, true, false, true, true, true, true));
        assertEquals("not-capable outranks the soft reasons",
                     ProfileOrganizer.PurgeReason.NOT_CAPABLE,
                     ProfileOrganizer.purgeReasonFor(false, false, false, true, true, true, true));
        assertEquals("failures outrank acceptance, being the stronger signal",
                     ProfileOrganizer.PurgeReason.FAILURES,
                     ProfileOrganizer.purgeReasonFor(false, false, true, true, true, true, true));
    }

    /**
     * A healthy peer is left alone, which is what keeps the purge from shrinking
     * a healthy tier for no reason.
     */
    @Test
    public void testHealthyPeerIsKept() {
        assertNull(ProfileOrganizer.purgeReasonFor(false, false, true, true,
                                                    false, false, false));
        assertNull("and is kept even when the tier is below the threshold",
                   ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                   false, false, false));
    }

    /**
     * Loss probation counts as a soft reason alongside recent failures; both
     * feed the same outcome so the two are not silently unequal.
     */
    @Test
    public void testLossProbationIsASoftReason() {
        assertEquals(ProfileOrganizer.PurgeReason.FAILURES,
                     ProfileOrganizer.purgeReasonFor(false, false, true, true,
                                                      false, true, false));
        assertNull(ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                    false, true, false));
    }
}
