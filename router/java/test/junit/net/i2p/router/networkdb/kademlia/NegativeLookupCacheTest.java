package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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
    public void testLookupFailedExceedsThreshold() {
        Hash h = randomHash();
        // MAX_FAILS defaults to 3
        for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
            _cache.lookupFailed(h);
        }
        assertTrue("MAX_FAILS failures should cache", _cache.isCached(h));
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
        Destination dest = new Destination();
        _cache.failPermanently(dest);
        assertTrue("Permanently failed destination should be cached", _cache.isCached(dest.calculateHash()));
    }

    @Test
    public void testGetBadDestReturnsCached() {
        Destination dest = new Destination();
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
        Destination dest = new Destination();
        _cache.failPermanently(dest);
        assertNotNull(_cache.getBadDest(dest.calculateHash()));
        _cache.clear();
        assertNull("After clear, bad dest should be gone", _cache.getBadDest(dest.calculateHash()));
    }

    @Test
    public void testIsCachedAfterCacheAndThreshold() {
        Hash h = randomHash();
        _cache.cache(h);
        assertTrue(_cache.isCached(h));
    }

    @Test
    public void testMultipleDestinations() {
        Destination dest1 = new Destination();
        Destination dest2 = new Destination();
        _cache.failPermanently(dest1);
        _cache.failPermanently(dest2);
        assertNotNull(_cache.getBadDest(dest1.calculateHash()));
        assertNotNull(_cache.getBadDest(dest2.calculateHash()));
    }
}
