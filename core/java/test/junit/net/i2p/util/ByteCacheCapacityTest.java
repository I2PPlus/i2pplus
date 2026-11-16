package net.i2p.util;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.ByteArray;

/**
 * Tests for how one shared {@link ByteCache} is sized. There is one cache per entry
 * size for the life of the JVM, and components ask for the same size with different
 * capacities - the streaming managers ask for 128 of 4 KB while every connection
 * handler asks for 32, and the handlers run once per stream. Resizing on every
 * request made the last caller win: each new stream cut the shared pool back to 32
 * and the busiest caller went back to allocating buffers, which is allocation and GC
 * churn on the send path. A request only ever grows the pool, so it is sized for its
 * largest caller while every request is still trimmed to the global byte budget.
 * <p>
 * Every test asks for an entry size no other component uses, so the caches under test
 * cannot be resized by anything else running in the same JVM. Buffers are compared by
 * identity, and the cache is last-in first-out, so a reuse check counts membership of
 * the set that was released rather than assuming an order.
 *
 * @since 0.9.72+
 */
public class ByteCacheCapacityTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public ByteCacheCapacityTest() {}

    /**
     * A smaller request leaves the shared pool alone. This is the regression: the
     * per-connection handlers ask for 32 of 4 KB while the managers ask for 128, so
     * shrinking here cut the pool on every stream that opened.
     */
    @Test
    public void testASmallerRequestKeepsTheSharedPool() {
        int size = 3011;
        ByteCache wide = ByteCache.getInstance(128, size);
        ByteCache narrow = ByteCache.getInstance(32, size);
        assertSame("callers of one entry size share one cache", wide, narrow);
        assertEquals("a smaller request must not shrink the shared pool",
                     128, narrow.getCapacity());
        // and the deeper pool is really usable, not just a bigger number
        Set<ByteArray> made = release(wide, 128, size);
        assertEquals(128, reusedOf(wide, made, 128));
    }

    /**
     * A larger request grows the pool, and the extra room is available to every
     * caller of that size at once - the growth is not deferred to the next cache
     * creation, and buffers released past the old capacity are pooled rather than
     * discarded.
     */
    @Test
    public void testALargerRequestGrowsTheSharedPool() {
        int size = 3012;
        ByteCache narrow = ByteCache.getInstance(2, size);
        assertEquals(2, narrow.getCapacity());
        Set<ByteArray> made = release(narrow, 2, size);
        ByteCache wide = ByteCache.getInstance(4, size);
        assertEquals(4, wide.getCapacity());
        made.addAll(release(wide, 2, size));
        assertEquals("the grown pool must hold past the old capacity",
                     4, reusedOf(wide, made, 4));
    }

    /**
     * Growth is monotonic across callers: the pool ends at the largest capacity
     * asked for, whichever order the requests arrive in, so no caller can be
     * starved by one that started later.
     */
    @Test
    public void testThePoolSettlesAtTheLargestRequest() {
        int size = 3013;
        ByteCache first = ByteCache.getInstance(64, size);
        ByteCache second = ByteCache.getInstance(16, size);
        ByteCache third = ByteCache.getInstance(96, size);
        ByteCache fourth = ByteCache.getInstance(8, size);
        assertSame(first, fourth);
        assertEquals(96, fourth.getCapacity());
        assertEquals(96, second.getCapacity());
        assertEquals(96, third.getCapacity());
    }

    /**
     * A request far past the global byte budget is trimmed rather than trusted, so
     * one caller cannot size a shared pool in gigabytes. The trim keeps a usable
     * floor, and a zero entry size is raised to one byte so the request finds a
     * cache instead of dividing by zero.
     */
    @Test
    public void testAnOversizedRequestIsTrimmedToTheBudget() {
        ByteCache huge = ByteCache.getInstance(Integer.MAX_VALUE, 1500);
        long bytes = (long) huge.getCapacity() * 1500;
        assertTrue("the trimmed pool must stay under the 8MB ceiling, was " + bytes,
                   bytes <= 8L * 1024 * 1024);
        assertTrue("the trimmed pool must keep a usable floor",
                   huge.getCapacity() >= 16);
        ByteCache zero = ByteCache.getInstance(0, 0);
        assertEquals("a zero entry size is raised to 1 byte",
                     1, zero.acquire().getData().length);
    }

    /**
     * A zero-entry request does not clear a pool that is already holding buffers,
     * the same last-caller-wins defect seen from the other side.
     */
    @Test
    public void testAZeroRequestKeepsBuffersAlreadyInThePool() {
        int size = 3014;
        ByteCache cache = ByteCache.getInstance(4, size);
        Set<ByteArray> made = release(cache, 4, size);
        ByteCache again = ByteCache.getInstance(0, size);
        assertSame(cache, again);
        assertEquals(4, again.getCapacity());
        assertEquals(4, reusedOf(again, made, 4));
    }

    /**
     * Makes and releases n buffers of one size.
     *
     * @param cache the cache to hand the buffers back to
     * @param n how many buffers to make and release
     * @param size the byte length of each buffer
     * @return the buffers released, by identity, for a reuse check to compare against
     */
    private static Set<ByteArray> release(ByteCache cache, int n, int size) {
        Set<ByteArray> made =
                Collections.newSetFromMap(new IdentityHashMap<ByteArray, Boolean>());
        for (int i = 0; i < n; i++) {
            ByteArray ba = new ByteArray(new byte[size]);
            made.add(ba);
            cache.release(ba, false);
        }
        return made;
    }

    /**
     * Takes n buffers and counts how many are ones that were released earlier, so a
     * pool that was shrunk or capped shows up as fewer reuses.
     *
     * @param cache the cache to take from
     * @param made the buffers that were released, by identity
     * @param n how many buffers to take
     * @return how many of them came back from the cache
     */
    private static int reusedOf(ByteCache cache, Set<ByteArray> made, int n) {
        int reused = 0;
        for (int i = 0; i < n; i++) {
            if (made.contains(cache.acquire())) {
                reused++;
            }
        }
        return reused;
    }
}
