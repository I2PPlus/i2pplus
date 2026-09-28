package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Boundary tests for the getSystemLoad() sample cache.
 *
 * getSystemLoad() calls OperatingSystemMXBean.getSystemLoadAverage(), a native
 * /proc read that sits on the per-build-request path of both tunnel throttlers.
 * The staleness predicate is factored out so the refresh interval can be
 * verified without touching the MXBean or the static query state.
 *
 * @since 0.9.71+
 */
public class SystemVersionSystemLoadCacheTest {

    private static final long NOW = 1000000L;

    @Test
    public void testNeverQueriedIsStale() {
        assertTrue(SystemVersion.sysLoadCacheStale(0, NOW));
    }

    @Test
    public void testFreshWithinInterval() {
        assertFalse(SystemVersion.sysLoadCacheStale(NOW - 1, NOW));
    }

    @Test
    public void testExactIntervalBoundaryIsStale() {
        assertTrue(SystemVersion.sysLoadCacheStale(NOW - SystemVersion.SYSTEM_LOAD_CACHE_MS, NOW));
    }

    @Test
    public void testJustUnderIntervalIsFresh() {
        assertFalse(SystemVersion.sysLoadCacheStale(NOW - (SystemVersion.SYSTEM_LOAD_CACHE_MS - 1), NOW));
    }

    @Test
    public void testMuchOlderIsStale() {
        assertTrue(SystemVersion.sysLoadCacheStale(NOW - 100 * SystemVersion.SYSTEM_LOAD_CACHE_MS, NOW));
    }

    /**
     * A wall-clock step backwards (NTP correction, manual clock change) must
     * not pin a stale sample forever: the negative age reads as stale and
     * forces a refresh.
     */
    @Test
    public void testClockWentBackwardsIsStale() {
        assertTrue(SystemVersion.sysLoadCacheStale(NOW + 5000L, NOW));
    }

    /**
     * The load average is a 1/5/15 minute metric, so the refresh interval must
     * stay well below its own resolution or the cache would be the limiting
     * factor. Guard against someone "optimizing" it up to 30s+.
     */
    @Test
    public void testCacheIntervalBelowLoadAverageResolution() {
        assertTrue("cache interval must stay well under the 1-minute load average window",
                   SystemVersion.SYSTEM_LOAD_CACHE_MS <= 10000L);
    }

    /**
     * getSystemLoad() must be callable without a stat manager and must not throw.
     */
    @Test
    public void testGetSystemLoadIsNonNegative() {
        assertTrue(SystemVersion.getSystemLoad() >= 0);
    }

    /**
     * The cached handle must not change the observable result: repeated calls
     * stay in range, and the second call is served from cache.
     */
    @Test
    public void testGetSystemLoadCachedValueStable() {
        int first = SystemVersion.getSystemLoad();
        int second = SystemVersion.getSystemLoad();
        assertTrue(first >= 0);
        assertTrue(second >= 0);
        assertEquals("second call should be served from the 1s cache", first, second);
    }

    /**
     * getCPULoadAvg() previously performed two string-keyed statManager()
     * lookups per call; it now resolves the handle once. Either way an
     * unregistered "router.cpuLoad" stat must yield 0 rather than throwing.
     */
    @Test
    public void testGetCpuLoadAvgNonNegative() {
        assertTrue(SystemVersion.getCPULoadAvg() >= 0);
        assertTrue(SystemVersion.getCPULoadAvg() <= 100);
    }

    /**
     * The core-count helper feeding getSystemLoad() is itself cached and must
     * never report zero, which would make the load average divide by zero.
     */
    @Test
    public void testGetCoresIsPositive() {
        assertTrue(SystemVersion.getCores() > 0);
    }
}
