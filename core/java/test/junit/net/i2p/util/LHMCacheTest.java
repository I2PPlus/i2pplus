package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class LHMCacheTest {

    @Test
    public void testEvictsEldest() {
        LHMCache<String, Integer> cache = new LHMCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(3, cache.size());
        cache.put("d", 4);
        assertEquals(3, cache.size());
        assertFalse(cache.containsKey("a"));
        assertTrue(cache.containsKey("d"));
    }

    @Test
    public void testLRUOrder() {
        LHMCache<String, Integer> cache = new LHMCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        // Access "a" to make it recently used
        cache.get("a");
        cache.put("d", 4);
        // "b" should be evicted (least recently used)
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("a"));
    }

    @Test
    public void testPutGet() {
        LHMCache<String, String> cache = new LHMCache<>(10);
        cache.put("key", "value");
        assertEquals("value", cache.get("key"));
    }

    @Test
    public void testRemove() {
        LHMCache<String, Integer> cache = new LHMCache<>(10);
        cache.put("a", 1);
        assertEquals(1, (int) cache.remove("a"));
        assertFalse(cache.containsKey("a"));
    }

    @Test
    public void testContainsKey() {
        LHMCache<String, Integer> cache = new LHMCache<>(10);
        assertFalse(cache.containsKey("a"));
        cache.put("a", 1);
        assertTrue(cache.containsKey("a"));
    }

    @Test
    public void testSize() {
        LHMCache<String, Integer> cache = new LHMCache<>(10);
        assertEquals(0, cache.size());
        cache.put("a", 1);
        assertEquals(1, cache.size());
    }

    @Test
    public void testMaxSizeOne() {
        LHMCache<String, Integer> cache = new LHMCache<>(1);
        cache.put("a", 1);
        assertEquals(1, cache.size());
        cache.put("b", 2);
        assertEquals(1, cache.size());
        assertFalse(cache.containsKey("a"));
        assertTrue(cache.containsKey("b"));
    }
}
