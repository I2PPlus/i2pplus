package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * Unit tests for the build-success-gated decision functions in
 * TunnelPeerSelector: relaxedExcludeCaps, allowFirewalledUnderAttack,
 * and the buildSuccess-carrying overloads of getActivityWindow /
 * isStalePeer / getBuildSuccess.
 *
 * Pure statics are exercised without a router context; the overload
 * smoke tests use a fresh isolated RouterContext when available.
 */
public class TunnelPeerSelectorBuildSuccessTest {

    private static final double ATTACK = TunnelPeerSelector.ATTACK_THRESHOLD;
    private static final long STARTUP_MS = TunnelPeerSelector.STARTUP_WARNING_SUPPRESS_MS;
    private static final long HOUR_MS = 60 * 60 * 1000L;

    // ---------- relaxedExcludeCaps ----------

    @Test
    public void testRelaxedExcludeCaps_NullAndEmpty() {
        assertNull(TunnelPeerSelector.relaxedExcludeCaps(null, 0.2, HOUR_MS));
        assertNull(TunnelPeerSelector.relaxedExcludeCaps(null, 0.85, HOUR_MS));
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("", 0.2, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_HealthyKeepsCaps() {
        assertEquals("MNODP", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.85, HOUR_MS));
        assertEquals("ABCDEF", TunnelPeerSelector.relaxedExcludeCaps("ABCDEF", 0.5, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_AttackStripsMNODP() {
        assertEquals("", TunnelPeerSelector.relaxedExcludeCaps("MNODP", 0.2, HOUR_MS));
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.2, HOUR_MS));
        assertEquals("EFG", TunnelPeerSelector.relaxedExcludeCaps("MEFGN", 0.2, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_NoDataRelaxes() {
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.0, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_ThresholdBoundary() {
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", ATTACK - 0.01, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", ATTACK, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.44, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.45, HOUR_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.99, HOUR_MS));
    }

    @Test
    public void testRelaxedExcludeCaps_StartupRelaxes() {
        long fourMin = STARTUP_MS - 60 * 1000L;
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, fourMin));
        assertEquals("ABC", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, 1));
    }

    @Test
    public void testRelaxedExcludeCaps_StartupBoundary() {
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, STARTUP_MS));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, 0));
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", 0.85, STARTUP_MS + 1));
    }

    @Test
    public void testRelaxedExcludeCaps_NaNIsConservative() {
        assertEquals("ABCD", TunnelPeerSelector.relaxedExcludeCaps("ABCD", Double.NaN, HOUR_MS));
    }

    // ---------- allowFirewalledUnderAttack ----------

    @Test
    public void testAllowFirewalledUnderAttack_NoUnreachableCap() {
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack(null, 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("ABC", 0.85));
    }

    @Test
    public void testAllowFirewalledUnderAttack_UCapOnly() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("U", 0.2));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("U", 0.85));
    }

    @Test
    public void testAllowFirewalledUnderAttack_RelaxedUnderAttack() {
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UN", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UO", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UP", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UX", 0.2));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.0));
        assertTrue(TunnelPeerSelector.allowFirewalledUnderAttack("UM", ATTACK - 0.01));
    }

    @Test
    public void testAllowFirewalledUnderAttack_NotRelaxedWhenHealthy() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", 0.85));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UP", 0.5));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", ATTACK));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UM", Double.NaN));
    }

    @Test
    public void testAllowFirewalledUnderAttack_OtherCapsNotRelaxing() {
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UQ", 0.2));
        assertFalse(TunnelPeerSelector.allowFirewalledUnderAttack("UABC", 0.2));
    }

    // ---------- context smoke tests ----------

    private static RouterContext getContext() {
        RouterContext ctx = null;
        try {
            ctx = RouterTestHelper.newContext();
        } catch (Throwable t) {
            // no live router available
        }
        Assume.assumeTrue("No RouterContext available", ctx != null);
        return ctx;
    }

    @Test
    public void testGetBuildSuccessNeverThrows() {
        RouterContext ctx = getContext();
        double bs = TunnelPeerSelector.getBuildSuccess(ctx);
        assertTrue(bs >= 0.0 && bs <= 1.0);
    }

    @Test
    public void testGetActivityWindowInRange() {
        RouterContext ctx = getContext();
        long w = TunnelPeerSelector.getActivityWindow(ctx, 0.85);
        assertTrue("window " + w + " outside [4h, 12h]", w >= 4 * HOUR_MS && w <= 12 * HOUR_MS);
    }

    @Test
    public void testGetActivityWindowSameForDegraded() {
        RouterContext ctx = getContext();
        long healthy = TunnelPeerSelector.getActivityWindow(ctx, 0.85);
        long degraded = TunnelPeerSelector.getActivityWindow(ctx, 0.2);
        long noData = TunnelPeerSelector.getActivityWindow(ctx, 0.0);
        assertEquals("degraded floor must not exceed base window", healthy, degraded);
        assertEquals(healthy, noData);
    }
}