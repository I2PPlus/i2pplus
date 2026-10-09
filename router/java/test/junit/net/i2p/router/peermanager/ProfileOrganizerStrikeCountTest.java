package net.i2p.router.peermanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the strike accumulation that gates tier demotion for a peer that did
 * not answer a build request.
 *
 * <p>Previously this policy was exercised only against a mock of itself, which
 * asserted that a local counter incremented.  {@code nextStrikeCount} is the
 * real seam, so these tests drive it directly: the behaviour that matters is
 * that two consecutive non-replies do <em>not</em> reach the threshold, which
 * is why eviction had to be moved to the immediate path in
 * {@code BuildExecutor}.
 */
public class ProfileOrganizerStrikeCountTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long DECAY = 10L * 60 * 1000L;

    /** A first failure starts the count at one. */
    @Test
    public void firstFailureIsOneStrike() {
        assertEquals(1, ProfileOrganizer.nextStrikeCount(0, 0, T0));
    }

    /** Consecutive failures inside the decay window accumulate. */
    @Test
    public void consecutiveFailuresAccumulate() {
        assertEquals(2, ProfileOrganizer.nextStrikeCount(1, T0, T0 + 1000));
        assertEquals(3, ProfileOrganizer.nextStrikeCount(2, T0, T0 + 2000));
    }

    /**
     * The behaviour that justified the immediate eviction path: two silent
     * builds must leave the count below the demotion threshold.
     */
    @Test
    public void twoFailuresStayBelowTheThreshold() {
        int s = ProfileOrganizer.nextStrikeCount(0, 0, T0);
        s = ProfileOrganizer.nextStrikeCount(s, T0, T0 + 1000);
        assertTrue("two silent builds must not trip the strike threshold",
                   s < ProfileOrganizer.DEMOTE_STRIKE_THRESHOLD);
    }

    /** The third consecutive failure reaches the threshold. */
    @Test
    public void thirdFailureReachesTheThreshold() {
        int s = ProfileOrganizer.nextStrikeCount(0, 0, T0);
        s = ProfileOrganizer.nextStrikeCount(s, T0, T0 + 1000);
        s = ProfileOrganizer.nextStrikeCount(s, T0 + 1000, T0 + 2000);
        assertEquals(ProfileOrganizer.DEMOTE_STRIKE_THRESHOLD, s);
    }

    /** A failure outside the decay window restarts the count. */
    @Test
    public void staleStrikesExpire() {
        assertEquals("a strike older than the decay window is forgotten",
                     1, ProfileOrganizer.nextStrikeCount(2, T0, T0 + DECAY));
    }

    /** Just inside the decay window the count still accumulates. */
    @Test
    public void strikesInsideTheWindowAccumulate() {
        assertEquals(3, ProfileOrganizer.nextStrikeCount(2, T0, T0 + DECAY - 1));
    }

    /** A clock that has not advanced past the last strike keeps the count. */
    @Test
    public void sameInstantStillCounts() {
        assertEquals(2, ProfileOrganizer.nextStrikeCount(1, T0, T0));
    }

    /**
     * The immediate eviction path is what actually removes the peer, so the
     * threshold must be a backstop rather than the primary mechanism — a
     * single non-reply has to be enough on its own.
     */
    @Test
    public void oneFailureIsBelowThresholdSoImmediatePathIsRequired() {
        int s = ProfileOrganizer.nextStrikeCount(0, 0, T0);
        assertTrue("one silent build cannot demote via the strike path",
                   s < ProfileOrganizer.DEMOTE_STRIKE_THRESHOLD);
    }
}
