package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Unit tests for the profile-cap predicates extracted from
 * ProfileOrganizer.enforceProfileCap().
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerCapTest {

    private static final long NOW = 2_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;

    private static Hash hash(int seed) {
        byte[] b = new byte[32];
        b[31] = (byte) seed;
        return Hash.create(b);
    }

    private PeerProfile profile(long lastSend, long lastHeardFrom) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getPeer()).thenReturn(hash(1));
        when(p.getLastSendSuccessful()).thenReturn(lastSend);
        when(p.getLastHeardFrom()).thenReturn(lastHeardFrom);
        return p;
    }

    // ---- clampMaxProfiles ----

    @Test
    public void testClampBelowFloor() {
        assertEquals(100, ProfileOrganizer.clampMaxProfiles(50));
    }

    @Test
    public void testClampAboveCeiling() {
        assertEquals(ProfileOrganizer.ABSOLUTE_MAX_PROFILES, ProfileOrganizer.clampMaxProfiles(99999));
    }

    @Test
    public void testClampWithinRange() {
        assertEquals(1000, ProfileOrganizer.clampMaxProfiles(1000));
    }

    @Test
    public void testClampBoundariesUnchanged() {
        assertEquals(100, ProfileOrganizer.clampMaxProfiles(100));
        assertEquals(ProfileOrganizer.ABSOLUTE_MAX_PROFILES,
                     ProfileOrganizer.clampMaxProfiles(ProfileOrganizer.ABSOLUTE_MAX_PROFILES));
    }

    // ---- isEvictable ----

    @Test
    public void testProtectedByFastTier() {
        Hash h = hash(1);
        PeerProfile p = profile(NOW - 10 * HOUR, NOW - 10 * HOUR);
        Map<Hash, PeerProfile> fast = new HashMap<>();
        fast.put(h, p);
        assertFalse(ProfileOrganizer.isEvictable(p, fast, new HashMap<>(), NOW - 48 * HOUR, 800, 1200));
    }

    @Test
    public void testFastTierOverLimitNotProtected() {
        Hash h = hash(1);
        PeerProfile p = profile(NOW - 100 * HOUR, NOW - 100 * HOUR);
        Map<Hash, PeerProfile> fast = new HashMap<>();
        fast.put(h, p);
        assertTrue(ProfileOrganizer.isEvictable(p, fast, new HashMap<>(), NOW - 48 * HOUR, 0, 1200));
    }

    @Test
    public void testProtectedByHighCapTier() {
        Hash h = hash(1);
        PeerProfile p = profile(NOW - 10 * HOUR, NOW - 10 * HOUR);
        Map<Hash, PeerProfile> hc = new HashMap<>();
        hc.put(h, p);
        assertFalse(ProfileOrganizer.isEvictable(p, new HashMap<>(), hc, NOW - 48 * HOUR, 800, 1200));
    }

    @Test
    public void testActiveSendKept() {
        PeerProfile p = profile(NOW - HOUR, NOW - 10 * HOUR);
        assertFalse(ProfileOrganizer.isEvictable(p, new HashMap<>(), new HashMap<>(), NOW - 48 * HOUR, 800, 1200));
    }

    @Test
    public void testActiveHeardFromKept() {
        PeerProfile p = profile(NOW - 10 * HOUR, NOW - HOUR);
        assertFalse(ProfileOrganizer.isEvictable(p, new HashMap<>(), new HashMap<>(), NOW - 48 * HOUR, 800, 1200));
    }

    @Test
    public void testBoundaryActivityKept() {
        PeerProfile p = profile(NOW - 48 * HOUR, NOW - 49 * HOUR);
        assertFalse(ProfileOrganizer.isEvictable(p, new HashMap<>(), new HashMap<>(), NOW - 48 * HOUR, 800, 1200));
    }

    @Test
    public void testInactiveUnprotectedEvictable() {
        PeerProfile p = profile(NOW - 49 * HOUR, NOW - 49 * HOUR);
        assertTrue(ProfileOrganizer.isEvictable(p, new HashMap<>(), new HashMap<>(), NOW - 48 * HOUR, 800, 1200));
    }
}