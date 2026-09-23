package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Iterator;
import java.util.ConcurrentModificationException;

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

    @Test(expected = ConcurrentModificationException.class)
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

    /**
     * AbstractSet.removeAll uses Iterator.remove() when this.size() &lt;= other.size().
     * ASIterator.remove() used to box the index to Integer and call remove(Object),
     * which never removed the entry — leaving cursor != _size so hasNext() spun
     * forever (BldExecutor hang via ClientPeerSelector.selectSingleHop).
     */
    @Test(timeout = 2000)
    public void testRemoveAllViaIteratorDoesNotHang() {
        ArraySet<String> set = new ArraySet<>(4);
        set.add("a");
        set.add("b");
        set.add("c");
        java.util.Set<String> exclude = new java.util.HashSet<>();
        exclude.add("b");
        // size 3 <= exclude path still iterates this when c is larger;
        // equal/smaller c uses iterator remove on this set.
        java.util.Set<String> big = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) big.add("x" + i);
        big.add("b");
        set.removeAll(big);
        assertEquals(2, set.size());
        assertTrue(set.contains("a"));
        assertFalse(set.contains("b"));
        assertTrue(set.contains("c"));
    }

    @Test(timeout = 2000)
    public void testIteratorRemoveThenIterate() {
        ArraySet<String> set = new ArraySet<>(4);
        set.add("a");
        set.add("b");
        set.add("c");
        Iterator<String> it = set.iterator();
        while (it.hasNext()) {
            if ("b".equals(it.next())) {
                it.remove();
            }
        }
        assertEquals(2, set.size());
        assertFalse(set.contains("b"));
        int count = 0;
        for (String s : set) {
            assertNotNull(s);
            count++;
        }
        assertEquals(2, count);
    }

    @Test(timeout = 2000)
    public void testRemoveAllAllElements() {
        ArraySet<String> set = new ArraySet<>(4);
        set.add("a");
        set.add("b");
        java.util.Set<String> all = new java.util.HashSet<>();
        all.add("a");
        all.add("b");
        all.add("c");
        set.removeAll(all);
        assertTrue(set.isEmpty());
    }
}
