package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

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
}