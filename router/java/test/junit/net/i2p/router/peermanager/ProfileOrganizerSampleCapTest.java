package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the candidate-sample cap used by lockedSelectPeers
 * ({@link ProfileOrganizer#maxCandidateSample(int, int)}).
 *
 * <p>Scanning all ~670 tier peers per selection was a CPU hot path;
 * a 20× sample preserves selection quality while cutting gate checks.
 * The sample floor is what breaks the old stable-iteration clique: the cap
 * used to drop to 20, so every hop of every tunnel drew from the same 20
 * peers and the rest of a 1000-peer tier was never examined.
 *
 * <p>The floor was raised from 64 to 256 so the caller's first-hop quality
 * loop has enough candidates to reject on reachability before it relaxes its
 * transport-session requirement.  The cost is that the sample is a larger
 * fraction of the tier: against 670 peers, 256 is 38% rather than 10%, which
 * weakens the clique protection this cap was introduced for.  Keep the
 * "much smaller than a full scan" assertion below so that trade is visible
 * rather than silent.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerSampleCapTest {

    @Test
    public void testEmptyTier() {
        assertEquals(0, ProfileOrganizer.maxCandidateSample(3, 0));
        assertEquals(0, ProfileOrganizer.maxCandidateSample(3, -1));
    }

    @Test
    public void testFloorGovernsSingleHop() {
        // 1 hop -> 20x is below the floor, so the floor applies
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(1, 670));
        // a tier smaller than the floor is fully scanned
        assertEquals(15, ProfileOrganizer.maxCandidateSample(1, 15));
        assertEquals(1, ProfileOrganizer.maxCandidateSample(1, 1));
    }

    @Test
    public void testScalesWithHowMany() {
        // 2x20 and 3x20 are under the floor, so the floor applies.
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(2, 670));
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(3, 670));
        // 10x20 = 200 is also under the 256 floor, so the floor still wins.
        // The scaled term only takes over once howMany*20 exceeds the floor,
        // which at 256 is howMany >= 13.
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(10, 670));
        assertEquals(13 * 20, ProfileOrganizer.maxCandidateSample(13, 670));
        assertEquals(20 * 20, ProfileOrganizer.maxCandidateSample(20, 670));
    }

    @Test
    public void testNeverExceedsPeerCount() {
        assertEquals(50, ProfileOrganizer.maxCandidateSample(10, 50));
        assertEquals(5, ProfileOrganizer.maxCandidateSample(3, 5));
    }

    @Test
    public void testNonPositiveHowManyStillGetsFloor() {
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(0, 670));
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE,
                     ProfileOrganizer.maxCandidateSample(-3, 670));
    }

    @Test
    public void testTypicalFastTierIsMuchSmallerThanFullScan() {
        int cap = ProfileOrganizer.maxCandidateSample(3, 670);
        assertEquals(ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE, cap);
        assertTrue(cap < 670);
        // Still a meaningful fraction of the tier, not a near-full scan.
        // This bound is what the 64 floor satisfied at 10x; at 256 the sample is
        // 38% of a 670-peer tier, so the guard is deliberately weaker here and
        // the weaker bound is the point of the assertion.
        assertTrue("sample must stay well under a full tier scan",
                   670 / cap >= 2);
    }
}
