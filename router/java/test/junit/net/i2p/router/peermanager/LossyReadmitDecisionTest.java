package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Boundary tests for {@link ProfileOrganizer#lossyReadmitAllowed} — the pure
 * decision behind re-admitting loss-demoted peers, including the widened
 * fast-track while the tiers are short.
 *
 * @since 0.9.71+
 */
public class LossyReadmitDecisionTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long MINUTE = 60 * 1000L;
    private static final long WINDOW = 10 * MINUTE;
    private static final long MIN_AGE = 10 * MINUTE;
    private static final float THRESHOLD = 0.20f;
    private static final float FAST = 0.05f;

    private static boolean allow(float ratio, long ratioAgeMs, long demotedAgeMs, boolean tiersShort) {
        long lossySince = demotedAgeMs <= 0 ? 0 : NOW - demotedAgeMs;
        return ProfileOrganizer.lossyReadmitAllowed(ratio, NOW - ratioAgeMs, lossySince, NOW,
                                                    WINDOW, MIN_AGE, THRESHOLD, FAST, tiersShort);
    }

    @Test
    public void testNeverDemotedAlwaysAllowed() {
        assertTrue(allow(0.9f, 60 * MINUTE, 0, false));
    }

    @Test
    public void testSpotlessFreshReportSkipsMinAge() {
        // 3% < 5% fast-track, tiers full, demoted 1 min ago
        assertTrue(allow(0.03f, MINUTE, MINUTE, false));
    }

    @Test
    public void testCleanButNotSpotlessHeldToMinAgeWhenTiersFull() {
        // 10% is below threshold but above the 5% fast-track
        assertFalse(allow(0.10f, MINUTE, MINUTE, false));
    }

    @Test
    public void testCleanButNotSpotlessAdmittedWhenTiersShort() {
        assertTrue(allow(0.10f, MINUTE, MINUTE, true));
    }

    @Test
    public void testThresholdRatioIsNotCleanEvenWhenTiersShort() {
        // freshClean requires ratio strictly below the demotion threshold
        assertFalse(allow(THRESHOLD, MINUTE, MINUTE, true));
        assertFalse(allow(0.25f, MINUTE, MINUTE, true));
    }

    @Test
    public void testJustUnderThresholdAdmittedWhenTiersShort() {
        assertTrue(allow(0.199f, MINUTE, MINUTE, true));
    }

    @Test
    public void testFreshCleanAdmittedAfterMinAgeWhenTiersFull() {
        assertTrue(allow(0.10f, MINUTE, 11 * MINUTE, false));
    }

    @Test
    public void testMinAgeBoundaryIsInclusive() {
        // exactly min age has passed -> not "less than" -> admitted
        assertTrue(allow(0.10f, MINUTE, MIN_AGE, false));
    }

    @Test
    public void testStaleReportIsNeverEvidence() {
        // report older than the freshness window, even past min age
        assertFalse(allow(0.01f, 11 * MINUTE, 60 * MINUTE, false));
        assertFalse(allow(0.01f, 11 * MINUTE, 60 * MINUTE, true));
    }

    @Test
    public void testStaleWindowBoundaryIsExclusive() {
        // exactly the window age is not "less than" the window -> stale
        assertFalse(allow(0.01f, WINDOW, MIN_AGE, false));
    }

    // ---- a peer the transport has never reported on ----

    /**
     * "No report" is not "no clean evidence". The loss report fields are not
     * carried across a restart by the transport, so a peer that comes back from a
     * persisted probation with nothing on file was never observed to be lossy -
     * it was only ever remembered as one. Demanding a report from a peer outside
     * the tiers is a closed loop: outside the tiers it carries no packets, so it
     * generates no report, so the probation never clears.
     */
    @Test
    public void testNeverReportedIsReadmitted() {
        assertTrue("a demotion with no report behind it is not evidence of loss",
                   ProfileOrganizer.lossyReadmitAllowed(0f, 0L, NOW - 60 * MINUTE, NOW,
                                                        WINDOW, MIN_AGE, THRESHOLD, FAST, false));
        assertTrue("and the same with the tiers full",
                   ProfileOrganizer.lossyReadmitAllowed(0f, 0L, NOW - 24 * 60 * MINUTE, NOW,
                                                        WINDOW, MIN_AGE, THRESHOLD, FAST, true));
    }

    /**
     * A report of zero loss made at a real time is a report, and it is clean
     * evidence: the zero-ratio case is distinguished by the timestamp, not by the
     * ratio.
     */
    @Test
    public void testReportedZeroAtAFreshTimestampIsCleanEvidence() {
        assertTrue(allow(0f, MINUTE, MINUTE, false));
    }

    // ---- the clock bound on probation ----

    /**
     * Probation is bounded by time. The loss score that caused the demotion
     * decays to effectively zero in four hours, and clearing the probation needs
     * a transport report that a peer outside the tiers will not produce, so
     * without a bound the demotion is permanent.
     */
    @Test
    public void testProbationExpiresOnTheClock() {
        long bound = 60 * 60 * 1000L;
        assertFalse(ProfileOrganizer.lossProbationExpired(0, NOW));
        assertFalse("just demoted", ProfileOrganizer.lossProbationExpired(NOW - MINUTE, NOW));
        assertFalse("one minute short", ProfileOrganizer.lossProbationExpired(NOW - bound + 1, NOW));
        assertTrue("at the bound", ProfileOrganizer.lossProbationExpired(NOW - bound, NOW));
        assertTrue("a week of silence", ProfileOrganizer.lossProbationExpired(NOW - 7 * 24 * 60 * MINUTE, NOW));
    }
}
