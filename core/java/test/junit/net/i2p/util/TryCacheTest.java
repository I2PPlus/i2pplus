package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TryCacheTest {

    @Test
    public void testAcquireCreatesNewWhenEmpty() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 5);
        int[] obj = cache.acquire();
        assertNotNull(obj);
        assertEquals(10, obj.length);
    }

    @Test
    public void testReleaseAndReuse() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 5);
        int[] obj = cache.acquire();
        cache.release(obj);
        assertEquals(1, cache.size());
        int[] reused = cache.acquire();
        assertSame(obj, reused);
    }

    @Test
    public void testCapacityLimit() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 2);
        // Acquire 3 items, release all - only 2 should be cached
        int[] a = cache.acquire();
        int[] b = cache.acquire();
        int[] c = cache.acquire();
        cache.release(a);
        cache.release(b);
        cache.release(c); // Discarded because capacity reached
        assertEquals(2, cache.size());
    }

    @Test
    public void testClear() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 5);
        int[] a = cache.acquire();
        int[] b = cache.acquire();
        cache.release(a);
        cache.release(b);
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void testSizeAfterAcquireRelease() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 5);
        assertEquals(0, cache.size());
        int[] obj = cache.acquire();
        assertEquals(0, cache.size()); // Not in cache after acquire
        cache.release(obj);
        assertEquals(1, cache.size()); // Back in cache after release
    }

    @Test
    public void testShrink() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 10);
        // Acquire and release multiple items to fill the cache
        List<int[]> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(cache.acquire());
        }
        assertEquals(0, cache.size()); // All acquired, none in cache
        for (int[] item : items) {
            cache.release(item);
        }
        assertEquals(10, cache.size());
        cache.shrink(3);
        assertEquals(3, cache.size());
    }

    @Test
    public void testCapacityZero() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 0);
        int[] obj = cache.acquire();
        assertNotNull(obj);
        cache.release(obj);
        // Capacity 0 means nothing can be cached
        assertEquals(0, cache.size());
    }

    @Test
    public void testMultipleAcquireRelease() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 3);
        for (int i = 0; i < 100; i++) {
            int[] obj = cache.acquire();
            assertNotNull(obj);
            cache.release(obj);
        }
        // After each acquire/release, exactly 1 item cached
        assertEquals(1, cache.size());
    }

    @Test
    public void testFullCacheDiscardsRelease() {
        TryCache<int[]> cache = new TryCache<>(() -> new int[10], 1);
        int[] a = cache.acquire();
        int[] b = cache.acquire();
        cache.release(a);
        assertEquals(1, cache.size());
        cache.release(b); // Discarded - full
        assertEquals(1, cache.size());
    }
}
