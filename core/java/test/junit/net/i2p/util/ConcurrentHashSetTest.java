package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class ConcurrentHashSetTest {

    @Test
    public void testAddAndContains() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertTrue(set.add("a"));
        assertTrue(set.contains("a"));
        assertFalse(set.contains("b"));
    }

    @Test
    public void testAddDuplicateReturnsFalse() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertTrue(set.add("a"));
        assertFalse(set.add("a"));
        assertEquals(1, set.size());
    }

    @Test
    public void testRemove() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        set.add("a");
        assertTrue(set.remove("a"));
        assertFalse(set.contains("a"));
        assertEquals(0, set.size());
    }

    @Test
    public void testRemoveNonexistent() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertFalse(set.remove("nope"));
    }

    @Test
    public void testIsEmpty() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertTrue(set.isEmpty());
        set.add("a");
        assertFalse(set.isEmpty());
    }

    @Test
    public void testSize() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertEquals(0, set.size());
        set.add("a");
        set.add("b");
        set.add("c");
        assertEquals(3, set.size());
    }

    @Test
    public void testClear() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        set.add("a");
        set.add("b");
        set.clear();
        assertTrue(set.isEmpty());
    }

    @Test
    public void testAddAll() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        set.addAll(Arrays.asList("a", "b", "c"));
        assertEquals(3, set.size());
    }

    @Test
    public void testIterator() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        set.add("a");
        set.add("b");
        int count = 0;
        for (String s : set) {
            count++;
            assertNotNull(s);
        }
        assertEquals(2, count);
    }

    @Test
    public void testConstructorWithCapacity() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>(64);
        set.add("a");
        assertTrue(set.contains("a"));
    }
}
