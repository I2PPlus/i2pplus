package net.i2p.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The SDS cache table is bounded by the size the cache was built for, not by the
 * number of distinct keys the router has parsed. Every Hash, PublicKey and
 * SigningPublicKey read off the wire is looked up here, so a table that only
 * ever grows turns a stream of new keys into heap that is never given back.
 *
 * @since 0.9.72+
 */
public class SDSCacheRetentionTest {

    /** Holds no state; each case builds its own cache. */
    public SDSCacheRetentionTest() {}

    /**
     * A stream of distinct keys whose values are held nowhere else stops at the
     * ceiling instead of doubling the table for every key that arrives.
     */
    @Test
    public void unheldValuesDoNotGrowTheTableForever() {
        SDSCache.IntWeakCache<Object> cache = new SDSCache.IntWeakCache<>(16);
        for (int i = 0; i < 100_000; i++) {
            cache.put(i, new WeakReference<>(new Object()));
        }
        assertTrue("table grew to " + cache.capacity() + " slots for a cache built for 16",
                   cache.capacity() <= 32);
        assertTrue("occupied slots " + cache.size() + " exceed half the table",
                   cache.size() <= cache.capacity() / 2);
    }

    /**
     * With every value held strongly, the table stops at twice the size the
     * cache was built for and never exceeds half occupancy, which is what keeps
     * the probes short.
     */
    @Test
    public void tableStopsAtTwiceTheExpectedSize() {
        SDSCache.IntWeakCache<Object> cache = new SDSCache.IntWeakCache<>(1024);
        List<Object> held = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            Object v = new Object();
            held.add(v);
            cache.put(i, new WeakReference<>(v));
        }
        assertEquals(2048, cache.capacity());
        assertTrue("occupied slots " + cache.size() + " exceed half the table",
                   cache.size() <= cache.capacity() / 2);
    }

    /**
     * A second put for a key that is already in the table replaces the value in
     * place rather than taking another slot.
     */
    @Test
    public void repeatedKeyReplacesItsOwnSlot() {
        SDSCache.IntWeakCache<Object> cache = new SDSCache.IntWeakCache<>(16);
        Object first = new Object();
        Object second = new Object();
        cache.put(42, new WeakReference<>(first));
        cache.put(42, new WeakReference<>(second));
        assertEquals(1, cache.size());
        assertSame(second, cache.get(42).get());
    }
}
