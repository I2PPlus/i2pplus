package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The bars that decide whether a peer belongs in the fast or high-capacity tier,
 * as opposed to which peer gets picked from a tier that keeps its members.
 *
 * <p>Two failures are pinned here. The first is a tier that is full in the counter
 * and empty in use: the live router held 999 fast peers against a 479-peer
 * reservoir, every selection pass refused all ~580 candidates at the RTT gate, and
 * nothing was ever expelled, so the client pools reported "not enough leases" from
 * a tier that reported itself healthy. The fix is that the RTT ceiling gates
 * membership as well as selection, and an eviction refills its slot.
 *
 * <p>The second is a loss threshold that ignored how deep the tier was. A 900-peer
 * tier can afford to expel a lossy member because there is somebody behind it to
 * promote; a 250-peer tier cannot, and expelling there is a net shrink. So the loss
 * bar ramps with tier size, and it is a hard reason rather than a soft one, because
 * the ramp already accounts for depth.
 *
 * @since 0.9.72+
 */
public class TierMembershipBarsTest {

    private static final float CONFIGURED = 0.20f;

    // ---------------- how much loss a tier of a given depth tolerates ----------------

    /**
     * At or below the loose end of the ramp the tolerated ratio is twice the
     * configured threshold: 0.40 with the 0.20 default. A tier that thin has
     * nobody to promote into a vacated slot.
     */
    @Test
    public void testLoosestBarBelowTheRampFloor() {
        assertEquals(0.40f, ProfileOrganizer.lossyThresholdFor(0, CONFIGURED), 0.001f);
        assertEquals(0.40f, ProfileOrganizer.lossyThresholdFor(250, CONFIGURED), 0.001f);
        assertEquals(0.40f, ProfileOrganizer.lossyThresholdFor(ProfileOrganizer.LOSS_RAMP_LOOSE_PEERS,
                                                               CONFIGURED), 0.001f);
    }

    /**
     * The ramp is linear across 300 to 600 members, so the bar tightens
     * continuously as the reservoir behind the tier deepens.
     */
    @Test
    public void testBarRampsDownWithTierDepth() {
        float at350 = ProfileOrganizer.lossyThresholdFor(350, CONFIGURED);
        float at450 = ProfileOrganizer.lossyThresholdFor(450, CONFIGURED);
        float at550 = ProfileOrganizer.lossyThresholdFor(550, CONFIGURED);
        assertEquals(0.30f, at450, 0.001f);
        assertTrue("tightening with depth: 350=" + at350 + " 450=" + at450, at350 > at450);
        assertTrue("tightening with depth: 450=" + at450 + " 550=" + at550, at450 > at550);
    }

    /**
     * From 600 members up the configured threshold applies unchanged and holds for
     * any deeper tier - 999 fast peers tolerate exactly what was configured.
     */
    @Test
    public void testConfiguredBarFromTheTightEndUp() {
        assertEquals(CONFIGURED, ProfileOrganizer.lossyThresholdFor(ProfileOrganizer.LOSS_RAMP_TIGHT_PEERS,
                                                                    CONFIGURED), 0.001f);
        assertEquals(CONFIGURED, ProfileOrganizer.lossyThresholdFor(999, CONFIGURED), 0.001f);
        assertEquals(CONFIGURED, ProfileOrganizer.lossyThresholdFor(10000, CONFIGURED), 0.001f);
    }

    /**
     * A Tuner override moves the whole ramp, it does not replace it: a threshold
     * loosened to 0.40 tolerates 0.80 while the tier is thin and 0.40 once it is
     * deep. An operator changing the value expects the same proportionality, not a
     * ramp that snaps back to the default shape.
     */
    @Test
    public void testRampScalesWithTheConfiguredThreshold() {
        assertEquals(0.80f, ProfileOrganizer.lossyThresholdFor(100, 0.40f), 0.001f);
        assertEquals(0.60f, ProfileOrganizer.lossyThresholdFor(450, 0.40f), 0.001f);
        assertEquals(0.40f, ProfileOrganizer.lossyThresholdFor(900, 0.40f), 0.001f);
    }

    /**
     * The tolerated ratio never exceeds a loss ratio of 1.0, so a threshold tuned
     * above 0.5 still produces a usable bar instead of a gate nothing can reach.
     */
    @Test
    public void testBarNeverExceedsCertainLoss() {
        assertEquals(1.0f, ProfileOrganizer.lossyThresholdFor(10, 0.80f), 0.001f);
    }

    /**
     * A non-positive threshold disables the bar rather than turning it into a
     * hair-trigger that expels every peer with any loss at all.
     */
    @Test
    public void testNonPositiveThresholdDisablesTheBar() {
        assertEquals(0.0f, ProfileOrganizer.lossyThresholdFor(999, 0.0f), 0.001f);
        assertEquals(-1.0f, ProfileOrganizer.lossyThresholdFor(999, -1.0f), 0.001f);
    }

    // ---------------- the reservoir must stay deeper than the tier it seeds ----------------

    /**
     * The fast tier never fills to or past the reservoir's actual depth. That is
     * the invariant whose absence produced 999 fast peers against 479 high-capacity
     * ones: the first demotion had nobody to promote, so the tier stayed short
     * until the next rebuild.
     */
    @Test
    public void testFastTierStaysBelowTheReservoir() {
        assertEquals(478, ProfileOrganizer.fastFillTargetFor(1000, 479));
        assertEquals(999, ProfileOrganizer.fastFillTargetFor(1000, 1000));
        // A reservoir deeper than the tier's own maximum leaves that maximum alone:
        // the rule bounds growth past the supply, it does not inflate the tier.
        assertEquals(1000, ProfileOrganizer.fastFillTargetFor(1000, 1500));
        assertEquals(1000, ProfileOrganizer.fastFillTargetFor(1000, 1001));
    }

    /**
     * An empty reservoir means an empty fast tier, not a tier of its configured
     * maximum with nothing behind it.
     */
    @Test
    public void testEmptyReservoirLeavesNoRoom() {
        assertEquals(0, ProfileOrganizer.fastFillTargetFor(1000, 0));
        assertEquals(0, ProfileOrganizer.fastFillTargetFor(1000, 1));
    }

    /**
     * The reservoir aims at least one deeper than the tier it seeds, even past the
     * minimum it would otherwise fill to, so a refill always has a candidate.
     */
    @Test
    public void testReservoirStaysDeeperThanTheTier() {
        assertEquals(1000, ProfileOrganizer.highCapFillTargetFor(1000, 999));
        assertEquals(1000, ProfileOrganizer.highCapFillTargetFor(1000, 400));
        assertEquals(244, ProfileOrganizer.highCapFillTargetFor(243, 243));
    }

    // ---------------- the bars are hard reasons, not soft ones ----------------

    /**
     * Latency and loss evict even from a tier below its soft-eviction floor. The
     * loss bar already scales with the tier's depth and the RTT ceiling already
     * tracks the pool, so a peer past either is a peer the tier has no use for at
     * any size - and a tier of 999 members no pool can select is worse off than a
     * short tier it can select from.
     */
    @Test
    public void testBarsEvictBelowTheSoftFloor() {
        assertEquals(ProfileOrganizer.PurgeReason.RTT,
                     ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                     false, false, false, true, false));
        assertEquals(ProfileOrganizer.PurgeReason.LOSS,
                     ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                     false, false, false, false, true));
    }

    /**
     * The categorical reasons still outrank both bars: a banned peer is reported as
     * banned even when it is also slow, because that is the actionable fact an
     * operator greps for.
     */
    @Test
    public void testCategoricalReasonsOutrankTheBars() {
        assertEquals(ProfileOrganizer.PurgeReason.BANNED,
                     ProfileOrganizer.purgeReasonFor(true, true, true, false,
                                                     true, true, true, true, true));
        assertEquals(ProfileOrganizer.PurgeReason.UNREACHABLE,
                     ProfileOrganizer.purgeReasonFor(false, true, true, false,
                                                     true, true, true, true, true));
        assertEquals(ProfileOrganizer.PurgeReason.NOT_CAPABLE,
                     ProfileOrganizer.purgeReasonFor(false, false, false, false,
                                                     true, true, true, true, true));
    }

    /**
     * Latency outranks loss. Both are membership bars, and the latency ceiling is
     * the number selection is already refusing on, so it is the one that explains
     * the starvation warning.
     */
    @Test
    public void testLatencyOutranksLoss() {
        assertEquals(ProfileOrganizer.PurgeReason.RTT,
                     ProfileOrganizer.purgeReasonFor(false, false, true, true,
                                                     false, false, false, true, true));
    }

    /**
     * The seven-argument form is unchanged: with neither bar in play it answers
     * exactly as it did before the bars existed, so the eviction rules pinned by
     * the earlier tests still hold.
     */
    @Test
    public void testSevenArgumentFormUnchanged() {
        assertEquals(ProfileOrganizer.PurgeReason.FAILURES,
                     ProfileOrganizer.purgeReasonFor(false, false, true, true,
                                                     true, false, false));
        assertNull(ProfileOrganizer.purgeReasonFor(false, false, true, false,
                                                   true, true, true));
    }
}
