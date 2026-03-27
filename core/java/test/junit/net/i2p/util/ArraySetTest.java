package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Iterator;

public class ArraySetTest {

    @Test
    public void testAddAndContains() {
        ArraySet<String> set = new ArraySet<>();
        assertTrue(set.add("a"));
        assertTrue(set.contains("a"));
    }

    @Test
    public void testAddDuplicate() {
        ArraySet<String> set = new ArraySet<>();
        assertTrue(set.add("a"));
        assertFalse(set.add("a"));
        assertEquals(1, set.size());
    }

    @Test
    public void testRemove() {
        ArraySet<String> set = new ArraySet<>();
        set.add("a");
        set.add("b");
        assertTrue(set.remove("a"));
        assertFalse(set.contains("a"));
        assertEquals(1, set.size());
    }

    @Test
    public void testSize() {
        ArraySet<String> set = new ArraySet<>();
        assertEquals(0, set.size());
        set.add("a");
        set.add("b");
        assertEquals(2, set.size());
    }

    @Test
    public void testIsEmpty() {
        ArraySet<String> set = new ArraySet<>();
        assertTrue(set.isEmpty());
        set.add("a");
        assertFalse(set.isEmpty());
    }

    @Test
    public void testClear() {
        ArraySet<String> set = new ArraySet<>();
        set.add("a");
        set.add("b");
        set.clear();
        assertTrue(set.isEmpty());
    }

    @Test(expected = ArraySet.SetFullException.class)
    public void testFullThrows() {
        ArraySet<String> set = new ArraySet<>(2);
        set.add("a");
        set.add("b");
        set.add("c");
    }

    @Test
    public void testOverwriteMode() {
        ArraySet<String> set = new ArraySet<>(2, false);
        set.add("a");
        set.add("b");
        set.add("c");
        assertEquals(2, set.size());
    }

    @Test
    public void testMaxCapacity() {
        assertEquals(32, ArraySet.MAX_CAPACITY);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroCapacityThrows() {
        new ArraySet<String>(0);
    }

    @Test
    public void testGet() {
        ArraySet<String> set = new ArraySet<>();
        set.add("a");
        set.add("b");
        assertEquals("a", set.get(0));
        assertEquals("b", set.get(1));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetOutOfBounds() {
        ArraySet<String> set = new ArraySet<>();
        set.get(0);
    }

    @Test
    public void testIterator() {
        ArraySet<String> set = new ArraySet<>();
        set.add("a");
        set.add("b");
        set.add("c");
        int count = 0;
        for (String s : set) {
            count++;
            assertNotNull(s);
        }
        assertEquals(3, count);
    }

    @Test(expected = java.util.ConcurrentModificationException.class)
    public void iteratorFailFast() {
        ArraySet<String> set = new ArraySet<>();
        set.add("a");
        Iterator<String> it = set.iterator();
        set.add("b");
        it.next();
    }

    @Test
    public void testCopyConstructor() {
        ArraySet<String> orig = new ArraySet<>();
        orig.add("a");
        orig.add("b");
        ArraySet<String> copy = new ArraySet<>(orig);
        assertEquals(orig, copy);
    }
}
