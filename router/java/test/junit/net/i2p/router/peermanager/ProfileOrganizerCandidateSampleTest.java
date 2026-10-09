package net.i2p.router.peermanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the configurable candidate-sample floor added alongside
 * {@link ProfileOrganizer#maxCandidateSample(int, int, int)}.
 *
 * <p>The cap arithmetic itself is covered by
 * {@link ProfileOrganizerSampleCapTest}; this file covers only what is new
 * here — the shipped default, the range the clamp enforces, and the fact that
 * every hop size this router actually uses is floor-bound rather than scaled.
 * That last one is why the floor is the operative value for gateway
 * selection rather than a detail the {@code howMany * 20} term overrides.
 *
 * @see ProfileOrganizerSampleCapTest
 * @since 0.9.71+
 */
public class ProfileOrganizerCandidateSampleTest {

    private static final int FLOOR = ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE;

    /**
     * Every first-hop and last-hop call passes {@code howMany = 1}, and a
     * 3-hop client tunnel fills 2 middle hops — so in practice the floor
     * governs and the scaled term never engages.
     */
    @Test
    public void typicalClientHopSizesAreAllFloorBound() {
        for (int howMany = 1; howMany <= 3; howMany++) {
            assertEquals("howMany=" + howMany + " should be floor-bound, not scaled",
                         FLOOR, ProfileOrganizer.maxCandidateSample(howMany, 1500, FLOOR));
        }
    }

    /** The shipped default is 256, raised from the previous 64. */
    @Test
    public void shippedFloorIsTwoFiftySix() {
        assertEquals(256, FLOOR);
    }

    /**
     * The scaled term only takes over once {@code howMany * 20} exceeds the
     * floor, which at 256 is {@code howMany >= 13}.  At the 64 floor it was 4,
     * so this documents how much further out the crossover now sits.
     */
    @Test
    public void scaledTermCrossoverMovedToThirteenHops() {
        assertEquals("12 hops is still floor-bound", FLOOR,
                     ProfileOrganizer.maxCandidateSample(12, 1500, FLOOR));
        assertEquals("13 hops is where 13*20 overtakes the floor",
                     13 * 20, ProfileOrganizer.maxCandidateSample(13, 1500, FLOOR));
    }

    /**
     * The floor is deliberately capped well below a full tier scan, so a
     * misconfigured value cannot turn every selection into an O(tier) walk.
     */
    @Test
    public void floorCannotBeConfiguredIntoAFullScan() {
        assertTrue("the clamp must keep a bad value below a full scan",
                   ProfileOrganizer.MAX_CANDIDATE_SAMPLE < 4096);
    }

    /** Both ends of the clamp are enforced, and the default survives a round trip. */
    @Test
    public void configuredFloorIsClampedAtBothEnds() {
        ProfileOrganizer.setMinCandidateSample(1);
        assertTrue("below 16 must clamp up", ProfileOrganizer.getMinCandidateSample(null) >= 16);
        ProfileOrganizer.setMinCandidateSample(Integer.MAX_VALUE);
        assertTrue("above the cap must clamp down",
                   ProfileOrganizer.getMinCandidateSample(null) <= ProfileOrganizer.MAX_CANDIDATE_SAMPLE);
        ProfileOrganizer.setMinCandidateSample(FLOOR);
        assertEquals("the default must be restorable",
                     FLOOR, ProfileOrganizer.getMinCandidateSample(null));
    }
}
