package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Unit tests for the profile-cap predicates extracted from
 * {@link net.i2p.router.peermanager.ProfileOrganizer#enforceProfileCap}.
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

    // ---- maxProfilesForHeap ----

    /** A gigabyte of heap: 6% of it at 10 KB a profile, well inside the clamp. */
    @Test
    public void testCapForOneGigabyteHeap() {
        assertEquals(6291, ProfileOrganizer.maxProfilesForHeap(1024L * 1024 * 1024));
    }

    /**
     * A big heap is capped by the absolute ceiling, not by the heap: the ceiling
     * is what stops the store growing past the point where the peer population
     * stops being useful.
     */
    @Test
    public void testCapForFourGigabyteHeapIsClamped() {
        assertEquals(ProfileOrganizer.ABSOLUTE_MAX_PROFILES,
                     ProfileOrganizer.maxProfilesForHeap(4096L * 1024 * 1024));
    }

    /**
     * A small heap gets the 2000-profile floor, not the heap-derived number:
     * below that the tier minimums cannot be filled from the store at all.
     */
    @Test
    public void testCapForSmallHeapUsesTheFloor() {
        assertEquals(2000, ProfileOrganizer.maxProfilesForHeap(256L * 1024 * 1024));
    }

    /** A missing or bogus heap size falls back to the minimum, never to zero. */
    @Test
    public void testCapForDegenerateHeapSize() {
        assertEquals(ProfileOrganizer.MIN_MAX_PROFILES, ProfileOrganizer.maxProfilesForHeap(0));
        assertEquals(ProfileOrganizer.MIN_MAX_PROFILES, ProfileOrganizer.maxProfilesForHeap(-1));
    }

    /**
     * The cap must grow monotonically with the heap. A cap that shrank as the
     * heap grew would starve a router that was given more memory.
     */
    @Test
    public void testCapIsMonotonicInHeapSize() {
        long[] heaps = {64L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L};
        int prev = -1;
        for (long mb : heaps) {
            int cap = ProfileOrganizer.maxProfilesForHeap(mb * 1024 * 1024);
            assertTrue("cap " + cap + " below the cap " + prev + " for a smaller heap",
                       cap >= prev);
            assertTrue("cap " + cap + " above the ceiling", cap <= ProfileOrganizer.ABSOLUTE_MAX_PROFILES);
            prev = cap;
        }
    }

    // ---- evictionDeficit ----

    /** Under the cap, evict nothing, degraded or not. */
    @Test
    public void testEvictionDeficitUnderCap() {
        assertEquals(0, ProfileOrganizer.evictionDeficit(1000, 1000, false));
        assertEquals(0, ProfileOrganizer.evictionDeficit(900, 1000, false));
        assertEquals(0, ProfileOrganizer.evictionDeficit(900, 1000, true));
    }

    /** Over the cap, evict the whole deficit when the network is healthy. */
    @Test
    public void testEvictionDeficitHealthy() {
        assertEquals(500, ProfileOrganizer.evictionDeficit(1500, 1000, false));
    }

    /**
     * Degraded evicts at half rate, and not zero: the wholesale skip this
     * replaced left the store unpruned for as long as the condition held.
     */
    @Test
    public void testEvictionDeficitDegradedIsHalfNotNone() {
        assertEquals(250, ProfileOrganizer.evictionDeficit(1500, 1000, true));
        assertTrue(ProfileOrganizer.evictionDeficit(1001, 1000, true) >= 0);
        assertEquals(0, ProfileOrganizer.evictionDeficit(1001, 1000, true));
        assertEquals(1, ProfileOrganizer.evictionDeficit(1003, 1000, true));
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