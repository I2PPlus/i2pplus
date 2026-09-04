package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Unit tests for the adaptive absolute-RTT ceiling used to keep clearly-slow
 * peers out of fast-tier selection.
 *
 * <p>The fast tier is a relative ranking (top N% by speed), so on a degraded
 * network its members can still sit at high absolute latency. The ceiling is a
 * pure decision ({@link ProfileOrganizer#computeFastRttCeiling(double)}) applied
 * at selection time via {@link ProfileOrganizer#aboveRttCeiling(PeerProfile, long)}.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerRttCeilingTest {

    // ---- computeFastRttCeiling ----

    @Test
    public void testHealthyBoundaryFloorsAtMin() {
        // A healthy fast-tier boundary (e.g. 300ms) times the multiplier does not
        // clear the 1500ms floor, so the ceiling is the floor — no tighter than the
        // existing demoteIfHighRTT bar.
        assertEquals(1500L, ProfileOrganizer.computeFastRttCeiling(300.0d));
        assertEquals(1500L, ProfileOrganizer.computeFastRttCeiling(500.0d));
    }

    @Test
    public void testDegradedBoundaryScalesButClampsAtCap() {
        // Deeper boundary (e.g. 839ms typical on the degraded pool) pushes the
        // ceiling up to catch clearly-slow outliers, but never past the cap.
        assertEquals(2517L, ProfileOrganizer.computeFastRttCeiling(839.0d));
        // A pathological 1.5s boundary would scale to 4.5s but clamps at 3s.
        assertEquals(3000L, ProfileOrganizer.computeFastRttCeiling(1500.0d));
    }

    @Test
    public void testZeroBoundaryReturnsFloor() {
        // No measured boundary yet — fall back to the floor so nothing is over-trimmed.
        assertEquals(1500L, ProfileOrganizer.computeFastRttCeiling(0.0d));
        assertEquals(1500L, ProfileOrganizer.computeFastRttCeiling(-1.0d));
    }

    @Test
    public void testBoundaryJustAboveFloorScalesLinearly() {
        // 600ms * 3 = 1800ms, between floor and cap.
        assertEquals(1800L, ProfileOrganizer.computeFastRttCeiling(600.0d));
    }

    // ---- aboveRttCeiling ----

    private PeerProfile profile(float rtt) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelTestTimeAverage()).thenReturn(rtt);
        return p;
    }

    @Test
    public void testClearAboveCeilingExcludes() {
        // A peer with a tunnel-test RTT well above any possible ceiling is excluded.
        PeerProfile p = profile(3200f);
        assertTrue(ProfileOrganizer.aboveRttCeiling(p, 1500L));
    }

    @Test
    public void testAtOrBelowCeilingIncluded() {
        PeerProfile pAtEdge = profile(1500f);
        assertTrue(ProfileOrganizer.aboveRttCeiling(pAtEdge, 1500L)); // exactly at ceiling trips (>0 && >= ceiling)
        PeerProfile pBelow = profile(1200f);
        assertFalse(ProfileOrganizer.aboveRttCeiling(pBelow, 1500L));
    }

    @Test
    public void testZeroOrUnknownRttNeverExcluded() {
        PeerProfile pZero = profile(0f);
        assertFalse(ProfileOrganizer.aboveRttCeiling(pZero, 1500L));
    }
}
