package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Unit tests for the pure decision predicates extracted from
 * ProfileOrganizer selection hot paths.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerDecisionTest {

    private static final long NOW = 1_000_000_000L;
    private static final long MINUTE = 60 * 1000L;

    private PeerProfile profile() {
        return mock(PeerProfile.class);
    }

    // ---- isReliableBandwidthPeer ----

    @Test
    public void testReliableLowAcceptanceFails() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.29);
        assertFalse(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
        assertFalse(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, true));
    }

    @Test
    public void testReliableRecentTestPasses() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        when(p.getLastTestedSuccessfully()).thenReturn(NOW - 5 * MINUTE);
        assertTrue(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableTestAtWindowBoundaryFails() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        when(p.getLastTestedSuccessfully()).thenReturn(NOW - 10 * MINUTE);
        assertFalse(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableRecentHeardFromPasses() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        when(p.getLastHeardFrom()).thenReturn(NOW - 20 * MINUTE);
        assertTrue(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableRecentSendPasses() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        when(p.getLastSendSuccessful()).thenReturn(NOW - 25 * MINUTE);
        assertTrue(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableActivityAtWindowBoundaryFails() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        when(p.getLastHeardFrom()).thenReturn(NOW - 30 * MINUTE);
        when(p.getLastSendSuccessful()).thenReturn(NOW - 31 * MINUTE);
        assertFalse(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableNoActivityNotEstablishedFails() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        assertFalse(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, false));
    }

    @Test
    public void testReliableEstablishedPassesWithoutActivity() {
        PeerProfile p = profile();
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.5);
        assertTrue(ProfileOrganizer.isReliableBandwidthPeer(p, NOW, true));
    }

    // ---- hasRecentProofOfLife ----

    @Test
    public void testNoProfileNoLife() {
        assertFalse(ProfileOrganizer.hasRecentProofOfLife(null, NOW));
    }

    @Test
    public void testRecentSendIsLife() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 30 * MINUTE);
        assertTrue(ProfileOrganizer.hasRecentProofOfLife(p, NOW));
    }

    @Test
    public void testRecentHeardFromIsLife() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 30 * MINUTE);
        assertTrue(ProfileOrganizer.hasRecentProofOfLife(p, NOW));
    }

    @Test
    public void testRecentHeardAboutIsLife() {
        PeerProfile p = profile();
        when(p.getLastHeardAbout()).thenReturn(NOW - 30 * MINUTE);
        assertTrue(ProfileOrganizer.hasRecentProofOfLife(p, NOW));
    }

    @Test
    public void testAllStaleNoLife() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 61 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 61 * MINUTE);
        when(p.getLastHeardAbout()).thenReturn(NOW - 61 * MINUTE);
        assertFalse(ProfileOrganizer.hasRecentProofOfLife(p, NOW));
    }

    @Test
    public void testNeverContactedNoLife() {
        assertFalse(ProfileOrganizer.hasRecentProofOfLife(profile(), NOW));
    }
}