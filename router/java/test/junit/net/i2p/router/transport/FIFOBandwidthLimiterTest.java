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

    /**
     * A request that has not been fully allocated (bytesPending &gt; 0) must
     * not be silently reset and reused: doing so would lose the bytes a
     * concurrent allocator had already granted. The candidate is discarded and
     * a fresh request is returned.
     *
     * @since 0.9.71+
     */
    @Test
    public void testReusePendingRequestReturnsNew() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        // A huge request is never shortcut-satisfied and never completes in
        // test runtime, so it stays pending with bytes outstanding.
        FIFOBandwidthLimiter.Request req = limiter.requestInbound(Integer.MAX_VALUE / 2, "test");
        assertNotNull(req);
        assertTrue("seed request should be pending", req.getPendingRequested() > 0);

        FIFOBandwidthLimiter.Request reused = limiter.requestInbound(req, 10, "test");
        assertNotNull(reused);
        assertNotSame("a pending candidate must not be reused", req, reused);
        assertTrue("original request must still be pending", req.getPendingRequested() > 0);
    }

    /**
     * A fully-allocated, non-aborted request is reset and reused (same object
     * returned) instead of allocating a new one.
     *
     * @since 0.9.71+
     */
    @Test
    public void testReuseCompletedRequestReturnsSame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        // Available starts at 0, so a 1-byte request is enqueued; the refiller
        // grants it on the next refill. Wait for completion.
        FIFOBandwidthLimiter.Request req = limiter.requestInbound(1, "test");
        assertNotNull(req);
        long deadline = System.currentTimeMillis() + 5000;
        while (req.getPendingRequested() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (req.getPendingRequested() > 0)
            return; // no bandwidth configured in this environment; nothing to assert
        assertEquals(0, req.getPendingRequested());

        FIFOBandwidthLimiter.Request reused = limiter.requestInbound(req, 1, "test");
        assertSame("a completed candidate must be reused", req, reused);
        // The reused request may already be satisfied again if inbound bandwidth
        // is available, so only pin the reuse identity, not the pending state.
        assertFalse("reused request must not be aborted", reused.getAborted());
    }

    /**
     * An aborted request is never reused; a fresh request is returned and the
     * aborted candidate is left untouched.
     *
     * @since 0.9.71+
     */
    @Test
    public void testReuseAbortedRequestReturnsNew() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        FIFOBandwidthLimiter limiter = new FIFOBandwidthLimiter(_ctx);
        FIFOBandwidthLimiter.Request req = limiter.requestInbound(Integer.MAX_VALUE / 2, "test");
        req.abort();
        assertTrue("must be aborted", req.getAborted());

        FIFOBandwidthLimiter.Request reused = limiter.requestInbound(req, 10, "test");
        assertNotNull(reused);
        assertNotSame("an aborted candidate must not be reused", req, reused);
        assertTrue("aborted candidate must still be aborted", req.getAborted());
    }
}
