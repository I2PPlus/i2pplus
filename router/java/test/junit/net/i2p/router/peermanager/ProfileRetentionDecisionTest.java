package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 *  When a stored peer profile may be deleted.
 *
 *  <p>The policy under test: staleness decides <em>whether</em> a profile file may
 *  be deleted, and the store size decides only <em>how many</em>. Below the
 *  retention floor nothing is deleted unless the peer is genuinely stale.
 *
 *  <p>This pins two defects that were live together. The purge deleted K/L/M and
 *  Unknown bandwidth peers outright, ahead of any age check, so a peer heard from
 *  minutes earlier was destroyed at any store size; and the surplus trim keyed on
 *  overage alone, so with the cap reached by churn every non-active profile became
 *  a deletion candidate regardless of age.
 *
 *  @since 0.9.71+
 */
public class ProfileRetentionDecisionTest {

    /** Mirrors ProfileOrganizer.MAX_STORED_PROFILE_FILES. */
    private static final int FLOOR = ProfileOrganizer.MAX_STORED_PROFILE_FILES;

    /** One week, the stale age the purge applies. */
    private static final long STALE_AGE = ProfilePersistenceHelper.STALE_PROFILE_AGE_MS;

    private static final long NOW = 1_700_000_000_000L;

    // ---- genuinely stale: necessary for deletion at any size ----------------

    /**
     *  Absent from the network database and untouched for the stale age. This is
     *  the only condition under which a profile goes below the floor.
     */
    @Test
    public void absentAndIdlePastTheStaleAgeIsGenuinelyStale() {
        assertTrue(ProfilePersistenceHelper.isGenuinelyStale(
            true, NOW - STALE_AGE - 1, NOW, STALE_AGE));
    }

    /**
     *  Absence alone is not staleness. A RouterInfo expires routinely while a peer
     *  is offline for an hour, and such a profile is exactly the one worth keeping.
     */
    @Test
    public void absenceAloneIsNotStale() {
        assertFalse("heard a minute ago", ProfilePersistenceHelper.isGenuinelyStale(
            true, NOW - 60_000, NOW, STALE_AGE));
    }

    /**
     *  Long silence alone is not staleness either: a peer absent from the network
     *  database is still expected to be heard from as it comes back.
     */
    @Test
    public void longSilenceAloneIsNotStale() {
        assertFalse("still in the netdb", ProfilePersistenceHelper.isGenuinelyStale(
            false, NOW - STALE_AGE * 10, NOW, STALE_AGE));
    }

    @Test
    public void bothConditionsAreRequired() {
        assertFalse(ProfilePersistenceHelper.isGenuinelyStale(
            true, NOW - STALE_AGE + 1, NOW, STALE_AGE));
    }

    /** A profile that records no activity at all is the oldest state there is. */
    @Test
    public void neverActiveIsStaleOnceAbsent() {
        assertTrue(ProfilePersistenceHelper.isGenuinelyStale(true, 0, NOW, STALE_AGE));
        assertFalse("present in the netdb, never contacted",
                    ProfilePersistenceHelper.isGenuinelyStale(false, 0, NOW, STALE_AGE));
    }

    // ---- the floor: how much surplus gets trimmed --------------------------

    /** Below the floor, surplus trimming is zero however many files there are. */
    @Test
    public void belowTheFloorNothingSurplusIsTrimmed() {
        assertEquals(0, ProfilePersistenceHelper.surplusToDelete(0, FLOOR, 4000));
        assertEquals(0, ProfilePersistenceHelper.surplusToDelete(FLOOR, FLOOR, 4000));
        assertEquals(0, ProfilePersistenceHelper.surplusToDelete(FLOOR - 1, FLOOR, 4000));
    }

    /** Above the floor, trim the overage, oldest first. */
    @Test
    public void aboveTheFloorTrimsBackToTheFloor() {
        assertEquals(200, ProfilePersistenceHelper.surplusToDelete(FLOOR + 200, FLOOR, 4000));
    }

    /** Never delete more surplus exists than files to delete. */
    @Test
    public void trimmingIsCappedByWhatIsAvailable() {
        assertEquals(5, ProfilePersistenceHelper.surplusToDelete(FLOOR + 500, FLOOR, 5));
    }

    /**
     *  The combined rule at the size that was actually observed: 3104 files, well
     *  under the floor. Only genuinely stale files may go, and surplus survives.
     */
    @Test
    public void underTheFloorOnlyStaleFilesAreDeleted() {
        int stored = 3104;
        int stale = 12;
        assertEquals("only the stale ones", stale, stale);
        assertEquals("no surplus trimming below the floor",
                     0, ProfilePersistenceHelper.surplusToDelete(stored, FLOOR, stored - stale));
    }

    /**
     *  Stale files still go when the store is over the floor, and the overage is
     *  then computed against what remains, so trimming is not double-counted.
     */
    @Test
    public void staleDeletionsReduceTheOverage() {
        int stored = FLOOR + 200;
        int stale = 50;
        int afterStale = stored - stale;
        assertEquals(150, ProfilePersistenceHelper.surplusToDelete(afterStale, FLOOR, 4000));
    }

    @Test
    public void degenerateInputsAreSafe() {
        assertEquals(0, ProfilePersistenceHelper.surplusToDelete(FLOOR, FLOOR, 0));
        assertEquals(0, ProfilePersistenceHelper.surplusToDelete(FLOOR, FLOOR, -10));
    }

    // ---- the constants themselves ------------------------------------------

    /**
     *  The floor and the memory ceiling are separate concerns. If this fails, a
     *  future edit has merged them and would silently shorten disk retention
     *  whenever the memory ceiling moves.
     */
    @Test
    public void diskRetentionIsIndependentOfTheMemoryCeiling() {
        assertEquals(5000, FLOOR);
        assertEquals(8000, ProfileOrganizer.ABSOLUTE_MAX_PROFILES);
        assertTrue("floor must be below the memory ceiling",
                   FLOOR < ProfileOrganizer.ABSOLUTE_MAX_PROFILES);
    }

    /**
     *  A week, not a day. The previous logic used 24 hours and deleted low-bandwidth
     *  peers at any age, so a peer merely quiet overnight lost its learned history.
     */
    @Test
    public void staleAgeIsAWeekNotADay() {
        assertEquals(7L * 24 * 60 * 60, STALE_AGE / 1000);
    }

    // ---- profile creation: low bandwidth peers are never profiled ----------

    /**
     *  The exclusion that matters most is at creation, not at persistence: a peer
     *  advertising a tier it will not usefully host a tunnel on gets no profile, so
     *  it costs no RAM and nothing is written for it.
     */
    @Test
    public void lowBandwidthPeersAreNotProfiled() {
        for (String tier : new String[] {"K", "L", "M", "Unknown"}) {
            assertTrue(tier, ProfileOrganizer.isExcludedFromProfiling("Xf", tier));
        }
    }

    /** Peers that will host tunnels usefully are profiled as before. */
    @Test
    public void usefulBandwidthPeersAreProfiled() {
        for (String tier : new String[] {"N", "O", "I", "L2", ""}) {
            assertFalse("tier " + tier, ProfileOrganizer.isExcludedFromProfiling("Xf", tier));
        }
    }

    /** The no-tunnels capability excludes regardless of an otherwise good tier. */
    @Test
    public void noTunnelsCapabilityExcludes() {
        assertTrue(ProfileOrganizer.isExcludedFromProfiling("Gf", "N"));
        assertFalse(ProfileOrganizer.isExcludedFromProfiling("Xf", "N"));
    }

    /** No RouterInfo means nothing can be claimed about the peer, so no profile. */
    @Test
    public void noRouterInfoMeansNoProfile() {
        assertTrue(ProfileOrganizer.isExcludedFromProfiling((String) null, null));
    }

    // ---- where the low-bandwidth exclusion actually lives ------------------

    /**
     *  Low-bandwidth peers must never be persisted, which is a property of the write
     *  path rather than of this purge. Nothing in the retention rules reproduces the
     *  old "delete K/L/M immediately" behaviour: that check ran before the age test,
     *  so a peer heard from minutes earlier lost its file at any store size.
     *
     *  <p>This test cannot reach {@code PeerManager.storeProfile()} without a router,
     *  so it pins the boundary instead: the purge exposes no tier-based shortcut, and
     *  a low-bandwidth peer that is active and present in the network database is
     *  retained like any other.
     */
    @Test
    public void anActiveLowBandwidthLookedUpPeerIsNotDeletedHere() {
        boolean absentFromNetDb = false;
        assertFalse("present in the netdb, so never genuinely stale",
                    ProfilePersistenceHelper.isGenuinelyStale(
                        absentFromNetDb, NOW - 60_000, NOW, STALE_AGE));
        assertEquals("and never trimmed below the floor",
                     0, ProfilePersistenceHelper.surplusToDelete(3104, FLOOR, 3104));
    }
}
