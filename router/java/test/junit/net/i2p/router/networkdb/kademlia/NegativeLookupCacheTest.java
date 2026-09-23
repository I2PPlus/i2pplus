package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link NegativeLookupCache} count-based caching semantics.
 *
 * Pins the contract the data plane relies on: repeated lookup failures
 * accumulate toward {@code netdb.negativeCache.maxFails} within the cleaner
 * window before a key counts as negatively cached, so a single transient
 * search failure must not poison a RouterInfo key and cut off transit
 * traffic through it, while an explicit {@link NegativeLookupCache#cache(Hash)
 * cache()} marks immediately.
 */
public class NegativeLookupCacheTest {

    private static RouterContext _context;
    private NegativeLookupCache _cache;

    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _cache = new NegativeLookupCache(_context);
    }

    private Hash randomHash() {
        Hash h = new Hash();
        byte[] d = new byte[Hash.HASH_LENGTH];
        _context.random().nextBytes(d);
        h.setData(d);
        return h;
    }

    /** A Destination with a complete KeysAndCert so calculateHash() works. */
    private static Destination destination(int seed) {
        Destination d = new Destination();
        d.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        byte[] pk = new byte[PublicKey.KEYSIZE_BYTES];
        pk[pk.length - 1] = (byte) seed;
        d.setPublicKey(new PublicKey(pk));
        byte[] spk = new byte[SigningPublicKey.KEYSIZE_BYTES];
        spk[0] = (byte) seed;
        spk[1] = (byte) (seed >> 8);
        d.setSigningPublicKey(new SigningPublicKey(spk));
        return d;
    }

    @Test
    public void testNotCachedInitially() {
        assertFalse(_cache.isCached(randomHash()));
    }

    @Test
    public void testLookupFailedIncrement() {
        Hash h = randomHash();
        _cache.lookupFailed(h);
        assertFalse("One failure should not cache", _cache.isCached(h));
        _cache.lookupFailed(h);
        assertFalse("Two failures should not cache", _cache.isCached(h));
    }

    @Test
    public void testLookupFailedBoundary() {
        // exact boundary: one below the threshold stays clean, the threshold
        // itself trips, so a single transient blip can never poison a key
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS - 1; i++) {
            _cache.lookupFailed(h);
        }
        assertFalse("Just below maxFails should not cache", _cache.isCached(h));
        _cache.lookupFailed(h);
        assertTrue("maxFails failures should cache", _cache.isCached(h));
    }

    @Test
    public void testCacheSetsToMaxImmediately() {
        Hash h = randomHash();
        _cache.cache(h);
        assertTrue("cache() should immediately mark as cached", _cache.isCached(h));
    }

    @Test
    public void testDifferentHashesIndependent() {
        Hash h1 = randomHash();
        Hash h2 = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupFailed(h1);
        }
        assertTrue(_cache.isCached(h1));
        assertFalse("Different hash should not be cached", _cache.isCached(h2));
    }

    @Test
    public void testFailPermanently() {
        Hash h = randomHash();
        Destination dest = destination(1);
        _cache.failPermanently(dest);
        assertTrue("Permanently failed destination should be cached", _cache.isCached(dest.calculateHash()));
    }

    @Test
    public void testGetBadDestReturnsCached() {
        Destination dest = destination(1);
        Hash h = dest.calculateHash();
        _cache.failPermanently(dest);
        assertEquals(dest, _cache.getBadDest(h));
    }

    @Test
    public void testGetBadDestReturnsNullIfNotCached() {
        assertNull(_cache.getBadDest(randomHash()));
    }

    @Test
    public void testClearResetsCache() {
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupFailed(h);
        }
        assertTrue(_cache.isCached(h));
        _cache.clear();
        assertFalse("After clear, should not be cached", _cache.isCached(h));
    }

    @Test
    public void testClearResetsBadDests() {
        Destination dest = destination(1);
        _cache.failPermanently(dest);
        assertNotNull(_cache.getBadDest(dest.calculateHash()));
        _cache.clear();
        assertNull("After clear, bad dest should be gone", _cache.getBadDest(dest.calculateHash()));
    }

    @Test
    public void testMultipleDestinations() {
        Destination dest1 = destination(1);
        Destination dest2 = destination(2);
        _cache.failPermanently(dest1);
        _cache.failPermanently(dest2);
        assertNotNull(_cache.getBadDest(dest1.calculateHash()));
        assertNotNull(_cache.getBadDest(dest2.calculateHash()));
    }

    @Test
    public void testZeroTryFailureNotDefinitive() {
        assertFalse("Zero peers queried must not count as definitive",
                    NegativeLookupCache.countsAsDefinitiveFail(0));
        assertTrue("Peers queried count as definitive",
                   NegativeLookupCache.countsAsDefinitiveFail(1));
    }

    @Test
    public void testTimeoutThresholdHigherThanDefinitive() {
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupTimeout(h);
        }
        assertFalse("MAX_FAILS timeouts should not cache (higher bar)",
                    _cache.isCached(h));
        for (int i = 0; i < NegativeLookupCache.MAX_TIMEOUT_FAILS - NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupTimeout(h);
        }
        assertTrue("MAX_TIMEOUT_FAILS timeouts should cache", _cache.isCached(h));
    }

    @Test
    public void testClearResetsTimeoutCounter() {
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_TIMEOUT_FAILS; i++) {
            _cache.lookupTimeout(h);
        }
        assertTrue(_cache.isCached(h));
        _cache.clear(h);
        assertFalse("After clear, timeout count should be reset", _cache.isCached(h));
    }

    // --- Pure decision helpers for per-entry TTL ---

    @Test
    public void testEntryTtlFirstTripIsBase() {
        assertEquals("First trip (gen=1) must use base TTL",
                     NegativeLookupCache.ENTRY_TTL_BASE_MS,
                     NegativeLookupCache.entryTtlMs(1));
    }

    @Test
    public void testEntryTtlBackoffDoublesPerTrip() {
        assertEquals("Gen 2 doubles base",
                     NegativeLookupCache.ENTRY_TTL_BASE_MS * 2,
                     NegativeLookupCache.entryTtlMs(2));
        assertEquals("Gen 3 doubles again",
                     NegativeLookupCache.ENTRY_TTL_BASE_MS * 4,
                     NegativeLookupCache.entryTtlMs(3));
    }

    @Test
    public void testEntryTtlCapsAtMax() {
        assertEquals("Gen 4+ must cap at max, not keep doubling",
                     NegativeLookupCache.ENTRY_TTL_MAX_MS,
                     NegativeLookupCache.entryTtlMs(4));
        assertEquals("Gen 100 must still cap",
                     NegativeLookupCache.ENTRY_TTL_MAX_MS,
                     NegativeLookupCache.entryTtlMs(100));
    }

    @Test
    public void testEntryTtlZeroOrNegativeTreatedAsFirstTrip() {
        assertEquals("Gen 0 treated as 1",
                     NegativeLookupCache.ENTRY_TTL_BASE_MS,
                     NegativeLookupCache.entryTtlMs(0));
        assertEquals("Negative gen treated as 1",
                     NegativeLookupCache.ENTRY_TTL_BASE_MS,
                     NegativeLookupCache.entryTtlMs(-5));
    }

    @Test
    public void testIsEntryExpiredBoundary() {
        long until = 1_700_000_000_000L;
        assertFalse("One ms before until must not expire",
                    NegativeLookupCache.isEntryExpired(until, until - 1));
        assertTrue("Exact until is expired (now >= until)",
                   NegativeLookupCache.isEntryExpired(until, until));
        assertTrue("One ms past until must expire",
                   NegativeLookupCache.isEntryExpired(until, until + 1));
    }

    @Test
    public void testIsStreakStale() {
        long last = 1_700_000_000_000L;
        long window = 120_000L;
        assertFalse("No last-fail (-1) is never stale",
                    NegativeLookupCache.isStreakStale(-1, last + window * 10, window));
        assertFalse("Within window not stale",
                    NegativeLookupCache.isStreakStale(last, last + window - 1, window));
        assertTrue("Exact window is stale (age >= window)",
                   NegativeLookupCache.isStreakStale(last, last + window, window));
        assertTrue("Past window is stale",
                   NegativeLookupCache.isStreakStale(last, last + window + 1, window));
    }

    @Test
    public void testClearResetsTripSoRetripWorks() {
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupFailed(h);
        }
        assertTrue("Should be cached after first trip", _cache.isCached(h));
        _cache.clear(h);
        assertFalse("clear(h) must fully reset", _cache.isCached(h));
        // Re-trip works after clear
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupFailed(h);
        }
        assertTrue("Re-trip after clear must cache again", _cache.isCached(h));
    }

    @Test
    public void testClearResetsPartialStreak() {
        Hash h = randomHash();
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS - 1; i++) {
            _cache.lookupFailed(h);
        }
        assertFalse("Partial streak not cached", _cache.isCached(h));
        _cache.clear(h);
        // One more fail after clear must still not cache (counter reset)
        _cache.lookupFailed(h);
        assertFalse("Counter must have been reset by clear(h)", _cache.isCached(h));
    }
}
