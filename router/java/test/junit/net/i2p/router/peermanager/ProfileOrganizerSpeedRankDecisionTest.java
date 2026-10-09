package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Pins the speed-threshold rank: the boundary profile chosen by the bounded
 * top-K selection must be the same profile the removed full sort chose, at every
 * qualifying count.
 *
 * <p>The rank is a fraction of the <em>qualifying</em> count, so a set of 4000
 * profiles with 20 qualifiers reads rank 6, not rank 50 — the count that drives
 * it has nothing to do with how many profiles are under consideration. The
 * equivalence is asserted against the pre-optimisation code reproduced verbatim
 * in {@link #originalBoundary}.
 *
 * @see ProfileOrganizerSelectionCostTest
 * @see ProfileOrganizerCapacityThresholdCountTest
 * @since 0.9.71+
 */
public class ProfileOrganizerSpeedRankDecisionTest {

    private static final long NOW = 2_000_000_000L;
    /** Capacity of every qualifying profile; decoys sit below it. */
    private static final float CAPACITY = 10.0f;
    private static final double CAPACITY_THRESHOLD = 10.0;

    // ---- speedThresholdCutoff ----

    /**
     * The 30% fraction and the 50 cap, at the counts that straddle the cap. The
     * cap binds from 167 qualifiers, since 167 * 0.3 is the first count whose
     * 30% reaches 50.
     */
    @Test
    public void speedCutoffHoldsTheThirtyPercentAndFiftyBoundary() {
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(0));
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(1));
        assertEquals(49, ProfileOrganizer.speedThresholdCutoff(166));
        assertEquals(50, ProfileOrganizer.speedThresholdCutoff(167));
        assertEquals(50, ProfileOrganizer.speedThresholdCutoff(168));
        assertEquals(50, ProfileOrganizer.speedThresholdCutoff(1000));
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(-3));
    }

    // ---- countQualifying ----

    /** Only profiles passing both gates are counted. */
    @Test
    public void countQualifyingCountsOnlyQualifyingProfiles() {
        List<PeerProfile> all = new ArrayList<>();
        all.add(profile(CAPACITY, true, 10.0f));
        all.add(profile(CAPACITY, true, 20.0f));
        all.add(profile(9.9f, true, 30.0f));   // below the capacity threshold
        all.add(profile(CAPACITY, false, 40.0f)); // inactive
        assertEquals(2, ProfileOrganizer.countQualifying(all, NOW, CAPACITY_THRESHOLD));
        assertEquals(0, ProfileOrganizer.countQualifying(Collections.<PeerProfile>emptyList(),
                                                         NOW, CAPACITY_THRESHOLD));
    }

    // ---- selectSpeedBoundary ----

    /**
     * A lone qualifier is the boundary no matter how many faster profiles are
     * present, because a non-qualifying profile is not in the ranked set at all.
     */
    @Test
    public void singleQualifyingPeerIsTheBoundary() {
        List<PeerProfile> all = new ArrayList<>();
        PeerProfile only = profile(CAPACITY, true, 1.0f);
        for (int i = 0; i < 500; i++) {
            all.add(profile(1.0f, true, 1000.0f + i));
        }
        all.add(only);
        assertEquals(1, ProfileOrganizer.countQualifying(all, NOW, CAPACITY_THRESHOLD));
        assertSame(only, ProfileOrganizer.selectSpeedBoundary(all, NOW, CAPACITY_THRESHOLD));
    }

    /** Nothing qualifies, so there is no boundary and the threshold stays unset. */
    @Test
    public void noQualifyingPeerYieldsNoBoundary() {
        List<PeerProfile> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            all.add(profile(1.0f, true, 100.0f + i));
        }
        assertNull(ProfileOrganizer.selectSpeedBoundary(all, NOW, CAPACITY_THRESHOLD));
        assertNull(ProfileOrganizer.selectSpeedBoundary(Collections.<PeerProfile>emptyList(),
                                                       NOW, CAPACITY_THRESHOLD));
    }

    /**
     * The regression itself: a full profile set with few qualifiers. The profile
     * count puts the rank at the 50 cap and the selection then finds no 51st
     * qualifier and returns nothing, which left the previous threshold in place.
     * The qualifying count (20) puts it at rank 6.
     */
    @Test
    public void largeProfileSetWithFewQualifiersStillHasABoundary() {
        List<PeerProfile> all = new ArrayList<>();
        float speed = 1.0f;
        for (int i = 0; i < 20; i++) {
            all.add(profile(CAPACITY, true, speed += 1.0f));  // the 20 qualifiers, speeds 2..21
        }
        for (int i = 0; i < 3980; i++) {
            // Decoys faster than every qualifier: a set-size rank would pick one.
            all.add(profile(1.0f, i % 2 == 0, 10_000.0f + i));
        }
        assertEquals(20, ProfileOrganizer.countQualifying(all, NOW, CAPACITY_THRESHOLD));
        PeerProfile boundary = ProfileOrganizer.selectSpeedBoundary(all, NOW, CAPACITY_THRESHOLD);
        assertNotNull("few qualifiers must still yield a boundary profile", boundary);
        assertEquals(CAPACITY, boundary.getCapacityValue(), 0.0f);
        assertSame(originalBoundary(all, CAPACITY_THRESHOLD), boundary);
        assertEquals("rank 6 of 20 qualifiers is the 7th fastest",
                     nthFastestQualifying(all, CAPACITY_THRESHOLD, 7).getSpeedValue(),
                     boundary.getSpeedValue(), 0.0f);
        assertEquals(15.0f, boundary.getSpeedValue(), 0.0f);
    }

    /** The bounded selection reproduces the full sort at the pinned counts. */
    @Test
    public void boundaryMatchesFullSortAtPinnedQualifyingCounts() {
        for (int qualifying : new int[] {0, 1, 2, 33, 51, 300}) {
            List<PeerProfile> all = mixedSet(qualifying, 40);
            PeerProfile expected = originalBoundary(all, CAPACITY_THRESHOLD);
            PeerProfile actual = ProfileOrganizer.selectSpeedBoundary(all, NOW, CAPACITY_THRESHOLD);
            assertEquals("qualifying=" + qualifying + " qualifying count",
                         qualifying, ProfileOrganizer.countQualifying(all, NOW, CAPACITY_THRESHOLD));
            if (expected == null) {
                assertNull("qualifying=" + qualifying, actual);
            } else {
                assertSame("qualifying=" + qualifying, expected, actual);
            }
        }
    }

    /**
     * Sweep: random profile counts, random qualifying fractions, distinct speeds
     * so the boundary peer is unique and the comparison is exact rather than on
     * the speed value alone.
     */
    @Test
    public void boundaryAgreesWithFullSortOnRandomizedSets() {
        Random rnd = new Random(20261006L);
        for (int round = 0; round < 300; round++) {
            int profiles = rnd.nextInt(600);
            int qualifying = profiles == 0 ? 0 : rnd.nextInt(profiles + 1);
            List<PeerProfile> all = mixedSet(qualifying, profiles - qualifying, rnd);
            assertSame("round=" + round + " profiles=" + profiles + " qualifying=" + qualifying,
                       originalBoundary(all, CAPACITY_THRESHOLD),
                       ProfileOrganizer.selectSpeedBoundary(all, NOW, CAPACITY_THRESHOLD));
        }
    }

    // ---- helpers ----

    /**
     * The pre-optimisation implementation, verbatim: filter, sort the qualifiers
     * by speed descending, read rank min(30% of the qualifying count, 50).
     */
    private static PeerProfile originalBoundary(List<PeerProfile> reordered, double capacityThreshold) {
        List<PeerProfile> candidates = new ArrayList<>();
        for (PeerProfile profile : reordered) {
            if (profile.getCapacityValue() >= capacityThreshold && profile.getIsActive(NOW)) {
                candidates.add(profile);
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((p1, p2) -> Double.compare(p2.getSpeedValue(), p1.getSpeedValue()));
        int cutoff = Math.min((int) (candidates.size() * 0.3), 50);
        return candidates.get(cutoff);
    }

    /** The k-th fastest qualifying profile by brute force, 1-based. */
    private static PeerProfile nthFastestQualifying(List<PeerProfile> all, double capacityThreshold, int k) {
        List<PeerProfile> candidates = new ArrayList<>();
        for (PeerProfile profile : all) {
            if (profile.getCapacityValue() >= capacityThreshold && profile.getIsActive(NOW)) {
                candidates.add(profile);
            }
        }
        candidates.sort((p1, p2) -> Double.compare(p2.getSpeedValue(), p1.getSpeedValue()));
        return candidates.get(k - 1);
    }

    /** {@code qualifyingCount} qualifiers followed by {@code decoys} faster non-qualifiers. */
    private static List<PeerProfile> mixedSet(int qualifyingCount, int decoys) {
        return mixedSet(qualifyingCount, decoys, new Random(qualifyingCount * 31L + decoys));
    }

    /**
     * Speeds are unique across the whole set, so the sorted boundary is a single
     * well-defined peer; half of the decoys are active and half inactive, and all
     * are faster than every qualifier.
     */
    private static List<PeerProfile> mixedSet(int qualifyingCount, int decoys, Random rnd) {
        List<PeerProfile> all = new ArrayList<>(qualifyingCount + decoys);
        List<Float> speeds = new ArrayList<>(qualifyingCount + decoys);
        for (int i = 0; i < qualifyingCount + decoys; i++) {
            speeds.add((float) i);
        }
        Collections.shuffle(speeds, rnd);
        for (int i = 0; i < qualifyingCount; i++) {
            all.add(profile(CAPACITY, true, speeds.get(i)));
        }
        for (int i = qualifyingCount; i < qualifyingCount + decoys; i++) {
            boolean active = i % 2 == 0;
            all.add(profile(1.0f, active, speeds.get(i)));
        }
        return all;
    }

    private static PeerProfile profile(float capacity, boolean active, float speed) {
        PeerProfile profile = mock(PeerProfile.class);
        when(profile.getCapacityValue()).thenReturn(capacity);
        when(profile.getIsActive(NOW)).thenReturn(active);
        when(profile.getSpeedValue()).thenReturn(speed);
        return profile;
    }
}
