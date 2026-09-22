package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the candidate-sample cap used by locked_selectPeers
 * ({@link ProfileOrganizer#maxCandidateSample(int, int)}).
 *
 * <p>Scanning all ~670 tier peers per selection was a CPU hot path;
 * a 20× sample preserves selection quality while cutting gate checks.
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
    public void testFloorOfTwenty() {
        // howMany=1 → 20; peer tier smaller than floor is fully scanned
        assertEquals(20, ProfileOrganizer.maxCandidateSample(1, 670));
        assertEquals(15, ProfileOrganizer.maxCandidateSample(1, 15));
        assertEquals(1, ProfileOrganizer.maxCandidateSample(1, 1));
    }

    @Test
    public void testScalesWithHowMany() {
        assertEquals(40, ProfileOrganizer.maxCandidateSample(2, 670));
        assertEquals(60, ProfileOrganizer.maxCandidateSample(3, 670));
        assertEquals(200, ProfileOrganizer.maxCandidateSample(10, 670));
    }

    @Test
    public void testNeverExceedsPeerCount() {
        assertEquals(50, ProfileOrganizer.maxCandidateSample(10, 50));
        assertEquals(5, ProfileOrganizer.maxCandidateSample(3, 5));
    }

    @Test
    public void testNonPositiveHowManyStillGetsFloor() {
        assertEquals(20, ProfileOrganizer.maxCandidateSample(0, 670));
        assertEquals(20, ProfileOrganizer.maxCandidateSample(-3, 670));
    }

    @Test
    public void testTypicalFastTierIsMuchSmallerThanFullScan() {
        int cap = ProfileOrganizer.maxCandidateSample(3, 670);
        assertEquals(60, cap);
        assertTrue(cap < 670);
        // At least 10× fewer gate checks than a full 670-peer scan
        assertTrue(670 / cap >= 10);
    }
}
