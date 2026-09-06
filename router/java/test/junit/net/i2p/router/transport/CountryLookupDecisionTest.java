package net.i2p.router.transport;

import java.util.HashMap;
import java.util.Map;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the pure decision and memory-independent helpers extracted
 * from the former country/GeoIP cluster of {@link CommSystemFacadeImpl} into
 * {@link CountryLookup}: multi-part-TLD domain extraction, IP selection
 * predicates, the per-peer cache eviction decision, and over-capacity
 * trimming.
 *
 * All helpers are static and independent of a router context, so no mock or
 * running router is required for these tests.
 *
 * @since 0.9.71
 */
public class CountryLookupDecisionTest {

    // ----- getDomain -----

    @Test
    public void domainTwoPart() {
        assertEquals("example.com", CountryLookup.getDomain("host.example.com"));
        assertEquals("example.com", CountryLookup.getDomain("example.com"));
    }

    @Test
    public void domainMultiPartTld() {
        assertEquals("example.co.uk", CountryLookup.getDomain("foo.example.co.uk"));
        assertEquals("example.co.uk", CountryLookup.getDomain("a.b.example.co.uk"));
    }

    @Test
    public void domainMultiPartAustralian() {
        assertEquals("example.com.au", CountryLookup.getDomain("host.example.com.au"));
    }

    @Test
    public void domainIgnoresCaseAndTrim() {
        assertEquals("example.com", CountryLookup.getDomain("  HOST.EXAMPLE.COM  "));
        assertEquals("example.co.uk", CountryLookup.getDomain("HOST.EXAMPLE.CO.UK"));
    }

    @Test
    public void domainDropsLeadingDot() {
        assertEquals("example.com", CountryLookup.getDomain(".host.example.com"));
    }

    @Test
    public void domainNullAndEmpty() {
        assertEquals("", CountryLookup.getDomain(null));
        assertEquals("", CountryLookup.getDomain(""));
        assertEquals("", CountryLookup.getDomain("   "));
    }

    @Test
    public void domainSingleLabel() {
        assertEquals("localhost", CountryLookup.getDomain("localhost"));
    }

    // ----- getValidIP / getCompatibleIP -----

    // getValidIP / getCompatibleIP / getFirstValidIPOfType need populated
    // RouterAddress lists, which the data model does not expose for unit
    // construction without a running router; their null-safety is asserted
    // here and the address-scanning logic stays covered by compilation.

    @Test
    public void getValidIPNullSafe() {
        assertNull(CountryLookup.getValidIP(null));
        assertNull(CountryLookup.getCompatibleIP(null));
    }

    @Test
    public void firstValidIPOfTypeNullSafe() {
        assertNull(CountryLookup.getFirstValidIPOfType(null, false));
        assertNull(CountryLookup.getFirstValidIPOfType(new RouterInfo(), true));
    }

    // ----- isUnknownCountryCode -----

    @Test
    public void unknownCountryMarker() {
        assertTrue(CountryLookup.isUnknownCountryCode("xx"));
        assertFalse(CountryLookup.isUnknownCountryCode("us"));
        assertFalse(CountryLookup.isUnknownCountryCode(null));
        assertFalse(CountryLookup.isUnknownCountryCode(""));
    }

    // ----- evictExpired -----

    private static final long HOUR_MS = 60L * 60 * 1000;

    private static int _hashCounter = 0;

    private static Hash h() {
        byte[] b = new byte[32];
        // deterministic counter makes each returned Hash unique within a test
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) ((_hashCounter >>> (i * 8)) & 0xff);
        }
        _hashCounter++;
        return Hash.create(b);
    }

    @Test
    public void evictExpiredDropsStaleButKeepsFresh() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        Hash fresh = h(); cache.put(fresh, "us"); ts.put(fresh, 0L);
        Hash stale = h(); cache.put(stale, "ca"); ts.put(stale, -(2 * HOUR_MS));
        long now = 0L;
        assertEquals(1, CountryLookup.evictExpired(cache, ts, now, HOUR_MS));
        assertTrue(cache.containsKey(fresh));
        assertFalse(cache.containsKey(stale));
    }

    @Test
    public void evictExpiredStrictBoundary() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        Hash atBoundary = h(); cache.put(atBoundary, "us"); ts.put(atBoundary, -HOUR_MS);
        assertEquals(0, CountryLookup.evictExpired(cache, ts, 0L, HOUR_MS));
        assertTrue(cache.containsKey(atBoundary));
    }

    @Test
    public void evictExpiredAlwaysDropsUnknownMarker() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        Hash unresolved = h(); cache.put(unresolved, "xx"); ts.put(unresolved, 0L);
        assertEquals(1, CountryLookup.evictExpired(cache, ts, 0L, HOUR_MS));
        assertFalse(cache.containsKey(unresolved));
    }

    @Test
    public void evictExpiredNullTimestampTreatedAsFresh() {
        // Production uses ConcurrentHashMap, which forbids null values, so a
        // null timestamp cannot occur for a present key; the predicate is
        // conservative and keeps the entry.
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        Hash noTs = h(); cache.put(noTs, "us"); ts.put(noTs, null);
        assertEquals(0, CountryLookup.evictExpired(cache, ts, 0L, HOUR_MS));
        assertTrue(cache.containsKey(noTs));
    }

    @Test
    public void evictExpiredEmpty() {
        assertEquals(0, CountryLookup.evictExpired(new HashMap<>(), new HashMap<>(), 0L, HOUR_MS));
    }

    // ----- trimToCapacity -----

    @Test
    public void trimBelowCapacityNoop() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        for (int i = 0; i < 5; i++) {Hash k = h(); cache.put(k, "us"); ts.put(k, (long) i);}
        CountryLookup.trimToCapacity(cache, ts, 1000L, HOUR_MS, 10);
        assertEquals(5, cache.size());
    }

    @Test
    public void trimAboveCapacityDropsOldestQuarter() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        int max = 12;
        for (int i = 0; i < max; i++) {Hash k = h(); cache.put(k, "us"); ts.put(k, (long) i);}
        // at or above max -> evict expired (none stale here), then drop oldest max/4
        CountryLookup.trimToCapacity(cache, ts, 1000L, HOUR_MS, max);
        assertEquals(max - max / 4, cache.size());
    }

    @Test
    public void trimExpiresBeforeDroppingOldest() {
        Map<Hash, String> cache = new HashMap<>();
        Map<Hash, Long> ts = new HashMap<>();
        int max = 12;
        // make half of them stale so the expired-pass alone drops them
        for (int i = 0; i < max; i++) {
            Hash k = h();
            cache.put(k, "us");
            ts.put(k, i < max / 2 ? -(2 * HOUR_MS) : (long) i);
        }
        CountryLookup.trimToCapacity(cache, ts, 1000L, HOUR_MS, max);
        // 6 stale dropped -> 6 fresh remain, below max, no oldest eviction
        assertEquals(max / 2, cache.size());
    }
}
