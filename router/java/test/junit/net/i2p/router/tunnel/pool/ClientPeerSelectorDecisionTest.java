package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

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

    // ---- insertNewPeers ----

    private static Hash hash(byte b) {
        byte[] data = new byte[32];
        data[0] = b;
        return Hash.create(data);
    }

    @Test
    public void testEmptyPickedInsertsNothing() {
        List<Hash> rv = new ArrayList<>();
        rv.add(hash((byte) 1));
        assertEquals(0, ClientPeerSelector.insertNewPeers(rv, rv.size(), Collections.<Hash>emptyList(), 3));
        assertEquals(Collections.singletonList(hash((byte) 1)), rv);
    }

    @Test
    public void testDuplicatesAreSkippedAndOrderPreserved() {
        List<Hash> rv = new ArrayList<>();
        rv.add(hash((byte) 1));
        rv.add(hash((byte) 9));
        Set<Hash> picked = new LinkedHashSet<>();
        picked.add(hash((byte) 2));
        picked.add(hash((byte) 1));
        picked.add(hash((byte) 3));
        // insert before index 1 (the recorded first hop) -> endpoint stays put
        assertEquals(2, ClientPeerSelector.insertNewPeers(rv, 1, picked, 5));
        assertEquals(Arrays.asList(hash((byte) 1), hash((byte) 2), hash((byte) 3), hash((byte) 9)), rv);
    }

    @Test
    public void testNeedCapsInsertions() {
        List<Hash> rv = new ArrayList<>();
        Set<Hash> picked = new LinkedHashSet<>();
        picked.add(hash((byte) 2));
        picked.add(hash((byte) 3));
        picked.add(hash((byte) 4));
        assertEquals(2, ClientPeerSelector.insertNewPeers(rv, 0, picked, 2));
        assertEquals(Arrays.asList(hash((byte) 2), hash((byte) 3)), rv);
    }

    @Test
    public void testIndexClampedToBounds() {
        List<Hash> rv = new ArrayList<>();
        rv.add(hash((byte) 1));
        Set<Hash> picked = new LinkedHashSet<>();
        picked.add(hash((byte) 2));
        assertEquals(1, ClientPeerSelector.insertNewPeers(rv, 99, picked, 1));
        assertEquals(Arrays.asList(hash((byte) 1), hash((byte) 2)), rv);
        assertEquals(1, ClientPeerSelector.insertNewPeers(rv, -1, Collections.singleton(hash((byte) 3)), 1));
        assertEquals(Arrays.asList(hash((byte) 3), hash((byte) 1), hash((byte) 2)), rv);
    }

    // ---- minRequestedLength ----

    @Test
    public void testMinRequestedLengthUsesConfiguredLength() {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setLength(3);
        settings.setLengthVariance(0);
        assertEquals(3, ClientPeerSelector.minRequestedLength(settings));
    }

    @Test
    public void testMinRequestedLengthAppliesNegativeVariance() {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setLength(3);
        settings.setLengthVariance(-1);
        assertEquals(2, ClientPeerSelector.minRequestedLength(settings));
    }

    @Test
    public void testMinRequestedLengthNeverNegative() {
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setLength(1);
        settings.setLengthVariance(-3);
        assertEquals(0, ClientPeerSelector.minRequestedLength(settings));
    }
}
