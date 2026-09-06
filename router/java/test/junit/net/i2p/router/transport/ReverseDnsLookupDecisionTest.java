package net.i2p.router.transport;

import net.i2p.util.SystemVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the pure decision and (de)serialization helpers extracted
 * from the former rDNS cluster of {@link CommSystemFacadeImpl} into
 * {@link ReverseDnsLookup}: memory-dependent cache sizing and lifetimes,
 * accelerated-eviction and expiry thresholds, the persistence-file freshness
 * filter, and the cache-entry line format round trip (including legacy
 * pre-timestamp migration).
 *
 * All helpers are static and independent of a router context, so no mock or
 * running router is required.
 *
 * @since 0.9.71
 */
public class ReverseDnsLookupDecisionTest {

    // ----- expireHours -----

    @Test
    public void expireHoursBelow512Mb() {
        assertEquals(24, ReverseDnsLookup.expireHours(false, false));
        assertEquals(24, ReverseDnsLookup.expireHours(false, true));
    }

    @Test
    public void expireHoursWith1Gb() {
        assertEquals(48, ReverseDnsLookup.expireHours(true, true));
    }

    @Test
    public void expireHoursMidRange() {
        assertEquals(36, ReverseDnsLookup.expireHours(true, false));
    }

    // ----- maxCacheSize -----

    @Test
    public void maxCacheSizeBelow512Mb() {
        assertEquals(8000, ReverseDnsLookup.maxCacheSize(false, false));
        assertEquals(8000, ReverseDnsLookup.maxCacheSize(false, true));
    }

    @Test
    public void maxCacheSizeWith1Gb() {
        assertEquals(24000, ReverseDnsLookup.maxCacheSize(true, true));
    }

    @Test
    public void maxCacheSizeMidRange() {
        assertEquals(16000, ReverseDnsLookup.maxCacheSize(true, false));
    }

    @Test
    public void computedMaxMatchesMemoryBasis() {
        long mem = SystemVersion.getMaxMemory();
        boolean has512 = mem >= 512 * 1024 * 1024L;
        boolean has1GB = mem >= 1024 * 1024 * 1024L;
        assertEquals(ReverseDnsLookup.maxCacheSize(has512, has1GB),
                     ReverseDnsLookup.getMaxRdnsCacheSize());
    }

    // ----- accelerateEviction -----

    @Test
    public void evictionAccelerationStartsAboveNinetyPercent() {
        int max = 8000;
        int atThreshold = max * 90 / 100;
        assertFalse(ReverseDnsLookup.accelerateEviction(atThreshold, max));
        assertTrue(ReverseDnsLookup.accelerateEviction(atThreshold + 1, max));
    }

    @Test
    public void evictionAccelerationNotBelowThreshold() {
        assertFalse(ReverseDnsLookup.accelerateEviction(0, 8000));
        assertFalse(ReverseDnsLookup.accelerateEviction(1, 100));
        assertFalse(ReverseDnsLookup.accelerateEviction(89, 100));
        assertTrue(ReverseDnsLookup.accelerateEviction(91, 100));
    }

    @Test
    public void evictionAccelerationScaleOfFour() {
        int max = 4;
        assertFalse(ReverseDnsLookup.accelerateEviction(3, max));
        assertTrue(ReverseDnsLookup.accelerateEviction(4, max));
    }

    // ----- entryExpired -----

    private static final long HOUR_MS = 60L * 60 * 1000;

    @Test
    public void regularEntryExpiresStrictlyAfterBase() {
        long base = 24 * HOUR_MS;
        assertFalse(ReverseDnsLookup.entryExpired(base, base, false));
        assertFalse(ReverseDnsLookup.entryExpired(base - 1, base, false));
        assertTrue(ReverseDnsLookup.entryExpired(base + 1, base, false));
    }

    @Test
    public void unknownEntryUsesShortFixedWindow() {
        // 90 minutes still exceeds the 15-minute unknown window even though it
        // is far inside a 24-hour base TTL.
        long base = 24 * HOUR_MS;
        assertTrue(ReverseDnsLookup.entryExpired(90L * 60 * 1000, base, true));
    }

    @Test
    public void unknownEntryToleratesUpToFifteenMinutes() {
        long unknownWindow = 15L * 60 * 1000;
        assertFalse(ReverseDnsLookup.entryExpired(unknownWindow, 24 * HOUR_MS, true));
        assertFalse(ReverseDnsLookup.entryExpired(unknownWindow - 1, 24 * HOUR_MS, true));
        assertTrue(ReverseDnsLookup.entryExpired(unknownWindow + 1, 24 * HOUR_MS, true));
    }

    // ----- cacheFileEntryFresh -----

    @Test
    public void cacheFileEntryFreshAtAndBelowThreshold() {
        long threshold = 3L * 24 * 60 * 60 * 1000;
        assertTrue(ReverseDnsLookup.cacheFileEntryFresh(0, threshold));
        assertTrue(ReverseDnsLookup.cacheFileEntryFresh(threshold, threshold));
        assertFalse(ReverseDnsLookup.cacheFileEntryFresh(threshold + 1, threshold));
    }

    // ----- rdnsEntryToString / rdnsEntryFromString round trip -----

    @Test
    public void roundTripPreservesAllFields() {
        long ts = 1700000000000L;
        ReverseDnsLookup.CacheEntry in = new ReverseDnsLookup.CacheEntry("192.0.2.10", "host.example.net", ts);
        String line = ReverseDnsLookup.rdnsEntryToString(in);
        ReverseDnsLookup.CacheEntry out = ReverseDnsLookup.rdnsEntryFromString(line);
        assertNotNull(out);
        assertEquals("192.0.2.10", out.getIpAddress());
        assertEquals("host.example.net", out.getHostname());
        assertEquals(ts, out.getTimestamp());
    }

    @Test
    public void nullHostnameNormalizedToUnknownOnParse() {
        ReverseDnsLookup.CacheEntry in = new ReverseDnsLookup.CacheEntry("192.0.2.12", null);
        assertEquals("unknown", in.getHostname());
        ReverseDnsLookup.CacheEntry out =
            ReverseDnsLookup.rdnsEntryFromString(ReverseDnsLookup.rdnsEntryToString(in));
        assertNotNull(out);
        assertEquals("unknown", out.getHostname());
    }

    @Test
    public void legacyTwoPartLineMigratesWithCurrentTimestamp() {
        long before = System.currentTimeMillis();
        ReverseDnsLookup.CacheEntry out = ReverseDnsLookup.rdnsEntryFromString("192.0.2.13,migrated.example.net");
        long after = System.currentTimeMillis();
        assertNotNull(out);
        assertEquals("192.0.2.13", out.getIpAddress());
        assertEquals("migrated.example.net", out.getHostname());
        assertTrue("migration timestamp must be current", out.getTimestamp() >= before && out.getTimestamp() <= after);
    }

    @Test
    public void malformedLinesReturnNull() {
        assertNull(ReverseDnsLookup.rdnsEntryFromString(""));
        assertNull(ReverseDnsLookup.rdnsEntryFromString("just-an-ip"));
        assertNull(ReverseDnsLookup.rdnsEntryFromString("192.0.2.14,host,not-a-number"));
        assertNull(ReverseDnsLookup.rdnsEntryFromString("a,b,"));
    }

    @Test
    public void cacheEntryRdnsLineMatchesSerializer() {
        long ts = 1700000000000L;
        ReverseDnsLookup.CacheEntry entry = new ReverseDnsLookup.CacheEntry("192.0.2.15", "serial.example.net", ts);
        assertEquals(ReverseDnsLookup.rdnsEntryToString(entry), entry.getRdnsEntry());
    }
}
