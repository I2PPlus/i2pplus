package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import net.i2p.router.RouterContext;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for TunnelHistory.
 */
public class TunnelHistoryTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        try {
            _ctx = new RouterContext(null);
        } catch (Exception e) {
            _ctx = null;
        }
    }

    @Test
    public void testCreation() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th);
    }

    @Test
    public void testInitialValues() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertEquals(0, th.getLifetimeAgreedTo());
        assertEquals(0, th.getLifetimeRejected());
        assertEquals(0, th.getLifetimeFailed());
    }

    @Test
    public void testIncrementAgreedTo() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementAgreedTo();
        assertEquals(1, th.getLifetimeAgreedTo());
        assertTrue(th.getLastAgreedTo() > 0);
    }

    @Test
    public void testIncrementRejectedProbabalistic() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedProbabalistic() > 0);
    }

    @Test
    public void testIncrementRejectedBandwidth() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedBandwidth() > 0);
    }

    @Test
    public void testIncrementRejectedTransient() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedTransient() > 0);
    }

    @Test
    public void testIncrementFailed() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementFailed(100);
        assertEquals(1, th.getLifetimeFailed());
        assertTrue(th.getLastFailed() > 0);
    }

    @Test
    public void testMultipleIncrements() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        for (int i = 0; i < 10; i++) {
            th.incrementAgreedTo();
        }
        assertEquals(10, th.getLifetimeAgreedTo());

        for (int i = 0; i < 3; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        }
        assertEquals(3, th.getLifetimeRejected());

        for (int i = 0; i < 2; i++) {
            th.incrementFailed(100);
        }
        assertEquals(2, th.getLifetimeFailed());
    }

    @Test
    public void testRejectionRateStat() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th.getRejectionRate());
    }

    @Test
    public void testFailedRateStat() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th.getFailedRate());
    }

    @Test
    public void testRejectConstants() {
        assertEquals(10, TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT);
        assertEquals(20, TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD);
        assertEquals(30, TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        assertEquals(50, TunnelHistory.TUNNEL_REJECT_CRIT);
    }
}
