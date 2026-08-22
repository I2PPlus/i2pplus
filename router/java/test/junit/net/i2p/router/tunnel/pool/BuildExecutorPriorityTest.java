package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.Arrays;

import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Unit tests for the shared build-priority decision helpers extracted from
 * BuildExecutor: {@code isZeroHopEmergency()} (single definition of the
 * zero-hop emergency predicate used by both the allocation and dispatch
 * stages), {@code DISPATCH_COMPARATOR} (urgent-first dispatch ordering with
 * stable ties) and {@code effectiveTarget()} (the deduplicated target
 * formula).
 *
 * @since 0.9.71+
 */
public class BuildExecutorPriorityTest {

    // ---------------- isZeroHopEmergency ----------------

    private static TunnelPool emergencyPool(String nickname, boolean zeroHopConfigured,
                                            boolean hasFallback) {
        TunnelPool p = mock(TunnelPool.class);
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getDestinationNickname()).thenReturn(nickname);
        when(s.isZeroHop()).thenReturn(zeroHopConfigured);
        when(p.getSettings()).thenReturn(s);
        when(p.hasZeroHopFallback()).thenReturn(hasFallback);
        return p;
    }

    @Test
    public void testEmergencyWithFallback() {
        assertTrue(BuildExecutor.isZeroHopEmergency(
                emergencyPool(null, false, true)));
    }

    @Test
    public void testNoFallbackNotUrgent() {
        assertFalse(BuildExecutor.isZeroHopEmergency(
                emergencyPool(null, false, false)));
    }

    @Test
    public void testZeroHopPoolNotUrgent() {
        // a pool expressly configured for zero hops: length-1 IS the product
        assertFalse(BuildExecutor.isZeroHopEmergency(
                emergencyPool(null, true, true)));
    }

    @Test
    public void testPingPoolNotUrgent() {
        assertFalse(BuildExecutor.isZeroHopEmergency(
                emergencyPool("Ping[1234]", false, true)));
    }

    @Test
    public void testNullNicknameTreatedAsNonPing() {
        // covered by testEmergencyWithFallback; here the explicit null path
        // through the ping check is exercised again alongside a fallback
        assertTrue(BuildExecutor.isZeroHopEmergency(
                emergencyPool(null, false, true)));
    }

    // ---------------- DISPATCH_COMPARATOR ----------------

    private static Object[] row(int score, boolean urgent) {
        // pool slot unused by the comparator
        return new Object[] {score, null, urgent};
    }

    private static int scoreOf(Object[] row) {
        return (Integer) row[0];
    }

    private static boolean urgentOf(Object[] row) {
        return (Boolean) row[2];
    }

    @Test
    public void testUrgentBeatsHigherScore() {
        // collapsed non-urgent pool (max score) must not outrank an
        // emergency replacement carrying a low deficit score
        Object[][] rows = new Object[][] {row(BuildExecutor.computeUrgencyScore(0, 4), false),
                                          row(100, true)};
        Arrays.sort(rows, BuildExecutor.DISPATCH_COMPARATOR);
        assertTrue(urgentOf(rows[0]));
        assertFalse(urgentOf(rows[1]));
    }

    @Test
    public void testNonUrgentOrderedByScoreDesc() {
        Object[][] rows = new Object[][] {row(10, false), row(500, false), row(300, false)};
        Arrays.sort(rows, BuildExecutor.DISPATCH_COMPARATOR);
        assertEquals(500, scoreOf(rows[0]));
        assertEquals(300, scoreOf(rows[1]));
        assertEquals(10, scoreOf(rows[2]));
    }

    @Test
    public void testUrgentsOrderedByScoreDescAmongThemselves() {
        Object[][] rows = new Object[][] {row(20, true), row(80, true)};
        Arrays.sort(rows, BuildExecutor.DISPATCH_COMPARATOR);
        assertEquals(80, scoreOf(rows[0]));
        assertEquals(20, scoreOf(rows[1]));
    }

    @Test
    public void testFullTieKeepsAllocationOrder() {
        // stable sort: full ties keep input order instead of an arbitrary
        // identity-hash order that could starve one of two equal pools
        Object[] first = new Object[] {7, "a", false};
        Object[] second = new Object[] {7, "b", false};
        Object[][] tied = new Object[][] {first, second};
        Arrays.sort(tied, BuildExecutor.DISPATCH_COMPARATOR);
        assertSame(first, tied[0]);
        assertSame(second, tied[1]);
    }

    // ---------------- effectiveTarget ----------------

    private static RouterContext ctxWithDefaultProps() {
        RouterContext ctx = mock(RouterContext.class);
        // honor the caller-supplied default, as I2PAppContext.getProperty does
        when(ctx.getProperty(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        return ctx;
    }

    private static TunnelPoolSettings settings(int qty) {
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getTotalQuantity()).thenReturn(qty);
        return s;
    }

    @Test
    public void testKeepQuantityVerbatim() {
        RouterContext ctx = ctxWithDefaultProps();
        assertEquals(3, BuildExecutor.effectiveTarget(ctx, settings(3), true));
        // even below the floor: ping/zero-hop pools are never inflated
        assertEquals(1, BuildExecutor.effectiveTarget(ctx, settings(1), true));
    }

    @Test
    public void testFloorAppliesToSmallQuantities() {
        RouterContext ctx = ctxWithDefaultProps();
        // targetMin default 2, buffer default 0 -> max(2, max(2, qty))
        assertEquals(2, BuildExecutor.effectiveTarget(ctx, settings(1), false));
        assertEquals(2, BuildExecutor.effectiveTarget(ctx, settings(2), false));
    }

    @Test
    public void testLargerQuantityUnchanged() {
        RouterContext ctx = ctxWithDefaultProps();
        assertEquals(5, BuildExecutor.effectiveTarget(ctx, settings(5), false));
        assertEquals(8, BuildExecutor.effectiveTarget(ctx, settings(8), false));
    }

    @Test
    public void testBufferRaisesTarget() {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getProperty("i2p.tunnel.build.targetMin", 2)).thenReturn(2);
        when(ctx.getProperty("i2p.tunnel.targetBuffer", 0)).thenReturn(3);
        // max(2, max(2, 3 + 3)) = 6
        assertEquals(6, BuildExecutor.effectiveTarget(ctx, settings(3), false));
    }
}
