package net.i2p.router.transport;

import static org.junit.Assert.*;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for FIFOBandwidthLimiter.
 */
public class FIFOBandwidthLimiterTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        _ctx = RouterTestHelper.getContext();
    }

    @Test
    public void testCreation() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        assertNotNull(limiter);
    }

    @Test
    public void testTotalAllocatedInbound() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        long total = limiter.getTotalAllocatedInboundBytes();
        assertTrue(total >= 0);
    }

    @Test
    public void testTotalAllocatedOutbound() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        long total = limiter.getTotalAllocatedOutboundBytes();
        assertTrue(total >= 0);
    }

    @Test
    public void testInboundBurstBytes() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        int burst = limiter.getInboundBurstBytes();
        assertTrue(burst >= 0);
    }

    @Test
    public void testOutboundBurstBytes() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        int burst = limiter.getOutboundBurstBytes();
        assertTrue(burst >= 0);
    }

    @Test
    public void testSendBps() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        float bps = limiter.getSendBps();
        assertTrue(bps >= 0);
    }

    @Test
    public void testReceiveBps() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        float bps = limiter.getReceiveBps();
        assertTrue(bps >= 0);
    }

    @Test
    public void testInboundKBytesPerSecond() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        int kbps = limiter.getInboundKBytesPerSecond();
        assertTrue(kbps >= 0);
    }

    @Test
    public void testOutboundKBytesPerSecond() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        int kbps = limiter.getOutboundKBytesPerSecond();
        assertTrue(kbps >= 0);
    }

    @Test
    public void testRequestInbound() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        FIFOBandwidthLimiter.Request req = limiter.requestInbound(100, "test");
        assertNotNull(req);
    }

    @Test
    public void testRequestOutbound() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        FIFOBandwidthLimiter.Request req = limiter.requestOutbound(100, 0, "test");
        assertNotNull(req);
    }
}
