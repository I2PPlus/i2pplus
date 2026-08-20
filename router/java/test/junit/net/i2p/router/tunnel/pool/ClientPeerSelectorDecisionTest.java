package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import org.junit.Test;

/**
 * Unit tests for pure decision helpers extracted from
 * ClientPeerSelector.selectFirstHop().
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorDecisionTest {

    private static final long MINUTE = 60 * 1000L;

    // ---- isStartupGracePeriod ----

    @Test
    public void testNullRouterNotGrace() {
        RouterContext ctx = mock(RouterContext.class);
        assertFalse(ClientPeerSelector.isStartupGracePeriod(ctx));
    }

    @Test
    public void testWithinGrace() {
        RouterContext ctx = mock(RouterContext.class);
        Router router = mock(Router.class);
        when(ctx.router()).thenReturn(router);
        when(router.getUptime()).thenReturn(14 * MINUTE);
        assertTrue(ClientPeerSelector.isStartupGracePeriod(ctx));
    }

    @Test
    public void testExactBoundaryNotGrace() {
        RouterContext ctx = mock(RouterContext.class);
        Router router = mock(Router.class);
        when(ctx.router()).thenReturn(router);
        when(router.getUptime()).thenReturn(15 * MINUTE);
        assertFalse(ClientPeerSelector.isStartupGracePeriod(ctx));
    }

    @Test
    public void testAfterGrace() {
        RouterContext ctx = mock(RouterContext.class);
        Router router = mock(Router.class);
        when(ctx.router()).thenReturn(router);
        when(router.getUptime()).thenReturn(16 * MINUTE);
        assertFalse(ClientPeerSelector.isStartupGracePeriod(ctx));
    }

    // ---- firstHopQualityTier ----

    @Test
    public void testInStartupKeepsTier() {
        assertEquals(0, ClientPeerSelector.firstHopQualityTier(4, true, 0));
        assertEquals(2, ClientPeerSelector.firstHopQualityTier(4, true, 2));
    }

    @Test
    public void testEarlyAttemptsKeepTier() {
        assertEquals(0, ClientPeerSelector.firstHopQualityTier(1, false, 0));
        assertEquals(0, ClientPeerSelector.firstHopQualityTier(3, false, 0));
    }

    @Test
    public void testMidAttemptsPreferConnecting() {
        assertEquals(1, ClientPeerSelector.firstHopQualityTier(4, false, 0));
        assertEquals(1, ClientPeerSelector.firstHopQualityTier(5, false, 0));
    }

    @Test
    public void testLateAttemptsAcceptAny() {
        assertEquals(2, ClientPeerSelector.firstHopQualityTier(6, false, 0));
        assertEquals(2, ClientPeerSelector.firstHopQualityTier(8, false, 0));
    }

    @Test
    public void testDowngradeQuirkPreserved() {
        // Verbatim semantics: attempts 4-5 set tier 1 even from tier 2
        assertEquals(1, ClientPeerSelector.firstHopQualityTier(4, false, 2));
    }

    @Test
    public void testLateAttemptsFromAnyTier() {
        assertEquals(2, ClientPeerSelector.firstHopQualityTier(6, false, 1));
        assertEquals(2, ClientPeerSelector.firstHopQualityTier(6, false, 2));
    }

    // ---- canUseStressFallback ----

    @Test
    public void testNoStressNoHighCapNoPeers() {
        assertFalse(ClientPeerSelector.canUseStressFallback(0.41, false, 0));
    }

    @Test
    public void testNoStressNoHighCapWithPeers() {
        assertFalse(ClientPeerSelector.canUseStressFallback(0.41, false, 3));
    }

    @Test
    public void testHighCapBypassesStressWithPeers() {
        assertTrue(ClientPeerSelector.canUseStressFallback(0.41, true, 3));
    }

    @Test
    public void testHighCapStillNeedsPeers() {
        assertFalse(ClientPeerSelector.canUseStressFallback(0.41, true, 0));
    }

    @Test
    public void testStressWithPeers() {
        assertTrue(ClientPeerSelector.canUseStressFallback(0.10, false, 1));
    }

    @Test
    public void testAttackThresholdBoundaryNotStress() {
        // strict <: buildSuccess == ATTACK_THRESHOLD (0.40) is not stress
        assertFalse(ClientPeerSelector.canUseStressFallback(0.40, false, 3));
    }

    // ---- adoptIfFilled ----

    private static Hash hash(byte b) {
        byte[] data = new byte[32];
        data[0] = b;
        return Hash.create(data);
    }

    @Test
    public void testEmptyFallbackNotAdopted() {
        List<Hash> rv = new ArrayList<>();
        rv.add(hash((byte) 1));
        Set<Hash> fallback = new HashSet<>();
        assertFalse(ClientPeerSelector.adoptIfFilled(rv, fallback));
        assertEquals(Collections.singletonList(hash((byte) 1)), rv);
    }

    @Test
    public void testNonEmptyFallbackAdoptedReplacingRv() {
        List<Hash> rv = new ArrayList<>();
        rv.add(hash((byte) 1));
        Set<Hash> fallback = new HashSet<>();
        fallback.add(hash((byte) 2));
        fallback.add(hash((byte) 3));
        assertTrue(ClientPeerSelector.adoptIfFilled(rv, fallback));
        assertEquals(2, rv.size());
        assertTrue(rv.contains(hash((byte) 2)));
        assertTrue(rv.contains(hash((byte) 3)));
        assertFalse(rv.contains(hash((byte) 1)));
    }
}
