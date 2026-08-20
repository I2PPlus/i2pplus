package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

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
}