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
 * <p>The size guard is the tier's own minimum. Soft eviction - acceptance,
 * failures, loss probation - is withheld until the tier has the peers it asked
 * for, because a tier that is starving cannot replace what it evicts. The
 * previous bar was a tenth of the known profiles capped at 100, which put it
 * below the starvation line on every real router and so pruned a starving tier
 * at full strength.
 *
 * @since 0.9.71+
 */
public class TierAdmissionTest {

    // ---------------- the soft-eviction floor is the tier's own minimum ----------------

    /**
     * The floor is the tier minimum, unchanged: the number that already means
     * "this tier is healthy" is the number that has to be reached before the
     * tier is judged on how well its members perform.
     */
    @Test
    public void testFloorIsTheTierMinimum() {
        assertEquals(1000, ProfileOrganizer.softPurgeThreshold(1000));
        assertEquals(500, ProfileOrganizer.softPurgeThreshold(500));
        assertEquals(1, ProfileOrganizer.softPurgeThreshold(1));
    }

    /**
     * An unknown minimum must not enable eviction: with no target there is no
     * evidence the tier is well stocked, and evicting on that basis is the
     * collapse this guard exists to prevent.
     */
    @Test
    public void testUnknownMinimumDisablesSoftEviction() {
        assertEquals(Integer.MAX_VALUE, ProfileOrganizer.softPurgeThreshold(0));
        assertEquals(Integer.MAX_VALUE, ProfileOrganizer.softPurgeThreshold(-5));
    }

    /**
     * The measured live shape, and the defect this change fixes. The router ran
     * two days at 227 fast and 243 high-cap against a 1,000-peer minimum for
     * each, and the old floor of 100 let the soft reasons prune both tiers at
     * full strength the whole time.
     */
    @Test
    public void testStarvingTierIsProtectedAtTheMeasuredSizes() {
        int min = 1000;
        int threshold = ProfileOrganizer.softPurgeThreshold(min);
        assertTrue("a 227-peer fast tier is 23% of target and must not be turned over",
                   227 < threshold);
        assertTrue("a 243-peer high-cap tier likewise", 243 < threshold);
    }

    /**
     * Reaching the minimum re-arms the soft reasons in full: the protection is a
     * starvation guard, not a general amnesty for poor performers.
     */
    @Test
    public void testSoftEvictionResumesAtTheMinimum() {
        int threshold = ProfileOrganizer.softPurgeThreshold(1000);
        assertTrue("at target the tier can afford the turnover", 1000 >= threshold);
        assertTrue("and above it", 1001 >= threshold);
    }

    /**
     * The floor stays reachable on a small router, which is what the old
     * population fraction was written to protect. A tier minimum of 100 - what
     * {@code profileOrganizer.minFastPeers} gives a small network - is a bar a
     * 100-peer tier can meet, not a constant it can never reach.
     */
    @Test
    public void testFloorIsReachableOnASmallNetwork() {
        int threshold = ProfileOrganizer.softPurgeThreshold(100);
        assertTrue("a full small tier qualifies", 100 >= threshold);
        assertFalse("a 99-peer small tier does not", 99 >= threshold);
    }

    // ---------------- a starving tier is still protected ----------------

    /**
     * The guard's whole purpose: a tier below its floor keeps its soft failures
     * rather than being emptied further.
     */
    @Test
    public void testStarvedTierStaysBelowThreshold() {
        int threshold = ProfileOrganizer.softPurgeThreshold(1000);
        assertFalse("a tier of 40 peers must not be turned over", 40 >= threshold);
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
