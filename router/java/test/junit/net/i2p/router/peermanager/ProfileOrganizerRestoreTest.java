package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Unit tests for the tier-restore and netDb-eviction predicates extracted
 * from ProfileOrganizer reorganize maintenance paths.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerRestoreTest {

    private static final long NOW = 2_000_000_000L;
    private static final long MINUTE = 60 * 1000L;

    private PeerProfile profile() {
        return mock(PeerProfile.class);
    }

    // ---- needsTierRestore ----

    @Test
    public void testAtExactlyHalfNoRestore() {
        assertFalse(ProfileOrganizer.needsTierRestore(50, 100));
    }

    @Test
    public void testBelowHalfRestores() {
        assertTrue(ProfileOrganizer.needsTierRestore(49, 101));
        assertTrue(ProfileOrganizer.needsTierRestore(59, 120));
        assertTrue(ProfileOrganizer.needsTierRestore(0, 200));
    }

    @Test
    public void testAtOrAboveHalfNoRestore() {
        assertFalse(ProfileOrganizer.needsTierRestore(50, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(60, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(100, 100));
    }

    @Test
    public void testSmallOldSizeNeverRestores() {
        assertFalse(ProfileOrganizer.needsTierRestore(0, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(0, 99));
        assertFalse(ProfileOrganizer.needsTierRestore(49, 99));
    }

    // ---- isStaleAbsentPeer ----

    @Test
    public void testRecentActivityNotStale() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 10 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 20 * MINUTE);
        when(p.getLastHeardAbout()).thenReturn(NOW - 30 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testAllActivityStale() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 2 * 60 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 2 * 60 * MINUTE);
        when(p.getLastHeardAbout()).thenReturn(NOW - 2 * 60 * MINUTE);
        assertTrue(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testMaxActivityDetermines() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 90 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 10 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testExactlyAtThresholdNotStale() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 60 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testNeverContactedIsStale() {
        assertTrue(ProfileOrganizer.isStaleAbsentPeer(profile(), NOW, 60 * MINUTE));
    }
}