package net.i2p.router.crypto;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests the hourly maintenance over the family-key caches.
 * <p>
 * The pass existed to stop the negative cache growing without bound, and it did
 * that - but the positive cache next to it was never bounded at all. It holds an
 * entry per router whose family signature has been checked, each carrying the
 * family name and the signature string, so it only ever got larger. Bounding it
 * has to not throw away the entries that are still paying their way, so what
 * matters is the asymmetry: one cache is cleared every pass, the other only once
 * it has outgrown its bound.
 *
 * @since 0.9.72+
 */
public class FamilyKeyCryptoCacheCapTest {

    /**
     * A cache holding n distinct entries.
     *
     * @param n entry count
     * @return a fresh map
     */
    private static Map<String, String> cache(int n) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < n; i++) {
            m.put("router" + i, "entry");
        }
        return m;
    }

    /**
     * The negative cache is dropped every pass, whatever its size - a miss just
     * re-checks the router, and clearing is what lets a router that has since
     * joined the family be seen.
     */
    @Test
    public void testNegativeCacheIsAlwaysCleared() {
        Map<String, String> negative = cache(3);
        Map<String, String> verified = cache(3);

        int[] cleared = FamilyKeyCrypto.cleanupCaches(negative, verified, 100);

        assertTrue(negative.isEmpty());
        assertEquals(3, cleared[0]);
    }

    /**
     * Inside its bound the positive cache is left alone. Clearing it costs a
     * signature verification for every family router on the next lookup, and
     * that is exactly the work the cache exists to avoid.
     */
    @Test
    public void testPositiveCacheSurvivesInsideItsBound() {
        Map<String, String> negative = cache(5);
        Map<String, String> verified = cache(100);

        int[] cleared = FamilyKeyCrypto.cleanupCaches(negative, verified, 100);

        assertEquals(100, verified.size());
        assertEquals(0, cleared[1]);
    }

    /**
     * Past the bound the whole cache goes. The bound is there to cap memory, and
     * an entry that has to go is worth about as much as one that stays, so
     * clearing is proportionate where an LRU would be bookkeeping for nothing.
     */
    @Test
    public void testPositiveCacheIsClearedPastItsBound() {
        Map<String, String> negative = cache(1);
        Map<String, String> verified = cache(101);

        int[] cleared = FamilyKeyCrypto.cleanupCaches(negative, verified, 100);

        assertTrue(verified.isEmpty());
        assertEquals(101, cleared[1]);
    }

    /**
     * The bound is inclusive: at the bound the cache is still within it, one
     * entry past it is not.
     */
    @Test
    public void testBoundBoundary() {
        Map<String, String> at = cache(64);
        Map<String, String> over = cache(65);

        FamilyKeyCrypto.cleanupCaches(cache(0), at, 64);
        FamilyKeyCrypto.cleanupCaches(cache(0), over, 64);

        assertEquals(64, at.size());
        assertTrue(over.isEmpty());
    }

    /**
     * An empty positive cache stays empty and reports nothing cleared, so the
     * hourly pass costs nothing on a router with no families.
     */
    @Test
    public void testEmptyCachesReportNothing() {
        int[] cleared = FamilyKeyCrypto.cleanupCaches(cache(0), cache(0), 100);

        assertEquals(0, cleared[0]);
        assertEquals(0, cleared[1]);
    }

    /**
     * The bound has to be a bound. Set high enough it never trips, and the
     * positive cache is unbounded again while the code still reads as capped.
     */
    @Test
    public void testBoundIsARealBound() {
        assertTrue("bound must be positive", FamilyKeyCrypto.MAX_VERIFIED > 0);
        assertTrue("bound is too high to ever trip: " + FamilyKeyCrypto.MAX_VERIFIED,
                   FamilyKeyCrypto.MAX_VERIFIED <= 65536);
    }
}
