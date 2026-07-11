package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * Tests for RequestThrottler threshold clamping and load detection logic.
 *
 * @since 0.9.70+
 */
public class RequestThrottlerTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
    }

    // ===== Static threshold clamping tests =====
    // These run without any RouterContext.

    @Test
    public void testMinLimitClampLow() {
        RequestThrottler.setRequestMinLimit(0);
        assertEquals(1, RequestThrottler.getRequestMinLimit());
    }

    @Test
    public void testMinLimitClampHigh() {
        RequestThrottler.setRequestMinLimit(200);
        assertEquals(100, RequestThrottler.getRequestMinLimit());
    }

    @Test
    public void testMinLimitClampInRange() {
        RequestThrottler.setRequestMinLimit(50);
        assertEquals(50, RequestThrottler.getRequestMinLimit());
    }

    @Test
    public void testMaxLimitClampLow() {
        RequestThrottler.setRequestMaxLimit(5);
        assertEquals(10, RequestThrottler.getRequestMaxLimit());
    }

    @Test
    public void testMaxLimitClampHigh() {
        RequestThrottler.setRequestMaxLimit(5000);
        assertEquals(1000, RequestThrottler.getRequestMaxLimit());
    }

    @Test
    public void testPercentLimitClampLow() {
        RequestThrottler.setRequestPctLimit(0);
        assertEquals(1, RequestThrottler.getRequestPctLimit());
    }

    @Test
    public void testPercentLimitClampHigh() {
        RequestThrottler.setRequestPctLimit(200);
        assertEquals(100, RequestThrottler.getRequestPctLimit());
    }

    @Test
    public void testBurst1sThresholdClampLow() {
        RequestThrottler.setRequestBurst1sThreshold(0);
        assertEquals(1, RequestThrottler.getRequestBurst1sThreshold());
    }

    @Test
    public void testBurst1sThresholdClampHigh() {
        RequestThrottler.setRequestBurst1sThreshold(200);
        assertEquals(100, RequestThrottler.getRequestBurst1sThreshold());
    }

    @Test
    public void testHighLoadLagMsClampLow() {
        RequestThrottler.setHighLoadLagMs(100);
        assertEquals(200, RequestThrottler.getHighLoadLagMs());
    }

    @Test
    public void testHighLoadLagMsClampHigh() {
        RequestThrottler.setHighLoadLagMs(10000);
        assertEquals(5000, RequestThrottler.getHighLoadLagMs());
    }

    @Test
    public void testHighLoadCpuPctClampLow() {
        RequestThrottler.setHighLoadCpuPct(25);
        assertEquals(50, RequestThrottler.getHighLoadCpuPct());
    }

    @Test
    public void testHighLoadCpuPctClampHigh() {
        RequestThrottler.setHighLoadCpuPct(101);
        assertEquals(100, RequestThrottler.getHighLoadCpuPct());
    }

    @Test
    public void testHighLoadSysLoadPctClampLow() {
        RequestThrottler.setHighLoadSysLoadPct(25);
        assertEquals(50, RequestThrottler.getHighLoadSysLoadPct());
    }

    @Test
    public void testHighLoadSysLoadPctClampHigh() {
        RequestThrottler.setHighLoadSysLoadPct(101);
        assertEquals(100, RequestThrottler.getHighLoadSysLoadPct());
    }

    @Test
    public void testModerateLoadLagMsClampLow() {
        RequestThrottler.setModerateLoadLagMs(50);
        assertEquals(100, RequestThrottler.getModerateLoadLagMs());
    }

    @Test
    public void testModerateLoadLagMsClampHigh() {
        RequestThrottler.setModerateLoadLagMs(10000);
        assertEquals(3000, RequestThrottler.getModerateLoadLagMs());
    }

    @Test
    public void testModerateLoadCpuPctClampLow() {
        RequestThrottler.setModerateLoadCpuPct(20);
        assertEquals(40, RequestThrottler.getModerateLoadCpuPct());
    }

    @Test
    public void testModerateLoadCpuPctClampHigh() {
        RequestThrottler.setModerateLoadCpuPct(101);
        assertEquals(100, RequestThrottler.getModerateLoadCpuPct());
    }

    @Test
    public void testModerateLoadSysLoadPctClampLow() {
        RequestThrottler.setModerateLoadSysLoadPct(15);
        assertEquals(30, RequestThrottler.getModerateLoadSysLoadPct());
    }

    @Test
    public void testModerateLoadSysLoadPctClampHigh() {
        RequestThrottler.setModerateLoadSysLoadPct(101);
        assertEquals(100, RequestThrottler.getModerateLoadSysLoadPct());
    }

    @Test
    public void testSustainedHighLoadMsClampLow() {
        RequestThrottler.setSustainedHighLoadMs(1000);
        assertEquals(5000, RequestThrottler.getSustainedHighLoadMs());
    }

    @Test
    public void testSustainedHighLoadMsClampHigh() {
        RequestThrottler.setSustainedHighLoadMs(300_000);
        assertEquals(120_000, RequestThrottler.getSustainedHighLoadMs());
    }

    @Test
    public void testSustainedModerateLoadMsClampLow() {
        RequestThrottler.setSustainedModerateLoadMs(1000);
        assertEquals(10_000, RequestThrottler.getSustainedModerateLoadMs());
    }

    @Test
    public void testSustainedModerateLoadMsClampHigh() {
        RequestThrottler.setSustainedModerateLoadMs(600_000);
        assertEquals(300_000, RequestThrottler.getSustainedModerateLoadMs());
    }

    // ===== Integration tests (require RouterContext) =====

    @Test
    public void testConstructorInitializesFromContext() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RequestThrottler rt = new RequestThrottler(_ctx);
        assertNotNull(rt);
    }

    @Test
    public void testShouldThrottleDefaultHash() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RequestThrottler rt = new RequestThrottler(_ctx);
        // Fresh throttler with random hash should not throttle any request
        assertFalse(rt.shouldThrottle(Hash.create(new byte[32])));
    }
}
