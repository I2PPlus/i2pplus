package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class SystemVersionCPULoadCacheTest {

    @Test
    public void testNeverQueriedIsStale() {
        assertTrue(SystemVersion.cpuLoadCacheStale(0, System.currentTimeMillis()));
    }

    @Test
    public void testFreshWithinInterval() {
        long now = 1000000L;
        assertFalse(SystemVersion.cpuLoadCacheStale(now - 1, now));
    }

    @Test
    public void testExactIntervalBoundaryIsStale() {
        long now = 1000000L;
        long cachedFrom = now - SystemVersion.CPU_LOAD_CACHE_MS;
        assertTrue(SystemVersion.cpuLoadCacheStale(cachedFrom, now));
    }

    @Test
    public void testJustUnderIntervalIsFresh() {
        long now = 1000000L;
        long cachedFrom = now - (SystemVersion.CPU_LOAD_CACHE_MS - 1);
        assertFalse(SystemVersion.cpuLoadCacheStale(cachedFrom, now));
    }

    @Test
    public void testMuchOlderIsStale() {
        long now = 1000000L;
        assertTrue(SystemVersion.cpuLoadCacheStale(now - 100 * SystemVersion.CPU_LOAD_CACHE_MS, now));
    }
}
