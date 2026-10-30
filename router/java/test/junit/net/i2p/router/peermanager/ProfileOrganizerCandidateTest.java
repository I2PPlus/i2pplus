package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.router.tunnel.pool.TunnelPeerSelector;

/**
 * Unit tests for the candidate-eligibility predicates extracted from
 * ProfileOrganizer reorganize fallback paths.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerCandidateTest {

    private static final long NOW = 2_000_000_000L;
    private static final long MINUTE = 60 * 1000L;
    private static final long HOUR = 60 * MINUTE;

    private PeerProfile profile() {
        return mock(PeerProfile.class);
    }

    // ---- isExpiredProfile ----

    @Test
    public void testActiveProfileWithinWindowNotExpired() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 30 * MINUTE);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertFalse(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testActiveProfileExpired() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 3 * HOUR);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertTrue(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testPassiveHeardFromWithinWindowNotExpired() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 30 * MINUTE);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertFalse(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testPassiveHeardAboutWithinWindowNotExpired() {
        PeerProfile p = profile();
        when(p.getLastHeardAbout()).thenReturn(NOW - 30 * MINUTE);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertFalse(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testPassiveExpired() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 2 * HOUR);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertTrue(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testNeverContactedGossipPeerIsExpired() {
        PeerProfile p = profile();
        when(p.hasTunnelHistory()).thenReturn(true);
        // zero timestamps -> cutoff 0 < now - window for any positive window
        assertTrue(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testGossipExpired() {
        PeerProfile p = profile();
        when(p.hasTunnelHistory()).thenReturn(true);
        assertTrue(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 5 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testActiveAtWindowBoundaryNotExpired() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 2 * HOUR);
        when(p.hasTunnelHistory()).thenReturn(true);
        assertFalse(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testActiveWithoutTunnelHistoryUsesUntrackedWindow() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 30 * MINUTE);
        when(p.hasTunnelHistory()).thenReturn(false);
        // within the 2h active window but beyond the 10min untracked window
        assertTrue(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 10 * MINUTE));
    }

    @Test
    public void testPassiveWithoutTunnelHistoryKeepsPassiveWindow() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 30 * MINUTE);
        when(p.hasTunnelHistory()).thenReturn(false);
        // untracked window is wider than passive here — min() must not shrink it
        assertFalse(ProfileOrganizer.isExpiredProfile(p, NOW, 2 * HOUR, HOUR, 30 * MINUTE, 90 * MINUTE));
    }

    // ---- hasRecentTierActivity ----

    @Test
    public void testRecentHeardFromPasses() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 30 * MINUTE);
        assertTrue(ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR));
    }

    @Test
    public void testRecentSendPasses() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 30 * MINUTE);
        assertTrue(ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR));
    }

    @Test
    public void testStaleFails() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 2 * HOUR);
        when(p.getLastSendSuccessful()).thenReturn(NOW - 2 * HOUR);
        assertFalse(ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR));
    }

    @Test
    public void testExactlyAtCutoffPasses() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - HOUR);
        assertTrue(ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR));
    }

    @Test
    public void testNeverContactedFails() {
        assertFalse(ProfileOrganizer.hasRecentTierActivity(profile(), NOW - HOUR));
    }

    // ---- hasRecentTierActivity with a live session ----

    /**
     * A session the transport is holding right now is the liveness the timestamp
     * is standing in for. A peer we have not sent anything to in four hours, with
     * a live session, is alive; a fill pass that refuses it is refusing it for
     * having been chosen last cycle, which is not a fact about the peer.
     */
    @Test
    public void testLiveSessionCountsAsRecentActivity() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 4 * HOUR);
        when(p.getLastSendSuccessful()).thenReturn(NOW - 4 * HOUR);
        assertFalse("stale on the timestamps alone",
                    ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR, false));
        assertTrue("reachable now",
                   ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR, true));
    }

    /**
     * A live session with no contact timestamps at all. This is the shape of a
     * peer the router has a working transport session with and has never been
     * selected through - exactly the candidate a starved tier fill wants.
     */
    @Test
    public void testLiveSessionWithNoTimestampsCounts() {
        assertTrue(ProfileOrganizer.hasRecentTierActivity(profile(), NOW - HOUR, true));
    }

    /** The two-arg form keeps its strict behaviour: selection is unchanged. */
    @Test
    public void testTwoArgFormIsUnchanged() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 4 * HOUR);
        assertFalse(ProfileOrganizer.hasRecentTierActivity(p, NOW - HOUR));
    }

    // ---- tierFillActivityWindow ----

    /**
     * A tier at its minimum keeps the strict selection window: the widening is a
     * starvation response, not a general loosening.
     */
    @Test
    public void testFillWindowIsTheBaseWindowAtTheMinimum() {
        assertEquals(HOUR, ProfileOrganizer.tierFillActivityWindow(HOUR, 1000, 1000));
        assertEquals(HOUR, ProfileOrganizer.tierFillActivityWindow(HOUR, 1200, 1000));
    }

    /**
     * The measured shape: a 227-peer fast tier against a 1,000-peer minimum, at
     * the 1-hour base window. The fill was offered only peers heard from in the
     * last hour - a set that is itself a function of the tier being full - so it
     * reported itself done at 227 and the tier never recovered.
     */
    @Test
    public void testFillWindowWidensOnTheMeasuredStarvation() {
        long w = ProfileOrganizer.tierFillActivityWindow(HOUR, 227, 1000);
        assertTrue("23% of target must look back days, not an hour, was " + w,
                   w >= 5 * HOUR);
        assertTrue("and stays bounded", w <= TunnelPeerSelector.MAX_WINDOW_MS);
    }

    /** The widening is monotonic in the deficit: a deeper shortage looks further back. */
    @Test
    public void testFillWindowIsMonotonicInDeficit() {
        long prev = HOUR;
        int[] sizes = {900, 700, 500, 300, 100, 0};
        for (int size : sizes) {
            long w = ProfileOrganizer.tierFillActivityWindow(HOUR, size, 1000);
            assertTrue("window " + w + " for size " + size + " is below the window "
                       + prev + " for a fuller tier", w >= prev);
            prev = w;
        }
    }

    /**
     * The widening is proportionate on a small router whose base window is
     * already long: a 4-hour base does not become a month.
     */
    @Test
    public void testFillWindowRespectsTheCeiling() {
        long w = ProfileOrganizer.tierFillActivityWindow(4 * HOUR, 0, 1000);
        assertTrue("never past the ceiling", w <= TunnelPeerSelector.MAX_WINDOW_MS);
        assertTrue("never below the base", w >= 4 * HOUR);
    }

    /** No target means no widening: the base window, so an unknown minimum is safe. */
    @Test
    public void testFillWindowWithNoMinimum() {
        assertEquals(HOUR, ProfileOrganizer.tierFillActivityWindow(HOUR, 10, 0));
        assertEquals(HOUR, ProfileOrganizer.tierFillActivityWindow(HOUR, 10, -1));
    }
}