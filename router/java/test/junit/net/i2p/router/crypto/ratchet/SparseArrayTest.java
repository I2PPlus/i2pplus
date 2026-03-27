package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class SparseArrayTest {

    private SparseArray<String> sa;

    @Before
    public void setUp() {
        sa = new SparseArray<String>();
    }

    @Test
    public void testPutAndGet() {
        sa.put(1, "one");
        sa.put(2, "two");
        sa.put(10, "ten");
        assertEquals("one", sa.get(1));
        assertEquals("two", sa.get(2));
        assertEquals("ten", sa.get(10));
    }

    @Test
    public void testGetMissingKey() {
        assertNull(sa.get(99));
    }

    @Test
    public void testGetWithDefault() {
        sa.put(1, "one");
        assertEquals("one", sa.get(1, "default"));
        assertEquals("default", sa.get(99, "default"));
    }

    @Test
    public void testPutReplace() {
        sa.put(1, "one");
        sa.put(1, "ONE");
        assertEquals("ONE", sa.get(1));
        assertEquals(1, sa.size());
    }

    @Test
    public void testDelete() {
        sa.put(1, "one");
        sa.put(2, "two");
        sa.delete(1);
        assertNull(sa.get(1));
        assertEquals("two", sa.get(2));
        assertEquals(1, sa.size());
    }

    @Test
    public void testRemove() {
        sa.put(1, "one");
        sa.remove(1);
        assertNull(sa.get(1));
        assertEquals(0, sa.size());
    }

    @Test
    public void testDeleteNonExistent() {
        sa.put(1, "one");
        sa.delete(99);
        assertEquals(1, sa.size());
    }

    @Test
    public void testRemoveReturnOld() {
        sa.put(1, "one");
        String old = sa.removeReturnOld(1);
        assertEquals("one", old);
        assertNull(sa.get(1));
        assertEquals(0, sa.size());
    }

    @Test
    public void testRemoveReturnOldMissing() {
        assertNull(sa.removeReturnOld(99));
    }

    @Test
    public void testSize() {
        assertEquals(0, sa.size());
        sa.put(1, "one");
        assertEquals(1, sa.size());
        sa.put(2, "two");
        assertEquals(2, sa.size());
        sa.delete(1);
        assertEquals(1, sa.size());
    }

    @Test
    public void testKeyAtOrdering() {
        sa.put(30, "thirty");
        sa.put(10, "ten");
        sa.put(20, "twenty");
        assertEquals(10, sa.keyAt(0));
        assertEquals(20, sa.keyAt(1));
        assertEquals(30, sa.keyAt(2));
    }

    @Test
    public void testValueAtOrdering() {
        sa.put(30, "thirty");
        sa.put(10, "ten");
        sa.put(20, "twenty");
        assertEquals("ten", sa.valueAt(0));
        assertEquals("twenty", sa.valueAt(1));
        assertEquals("thirty", sa.valueAt(2));
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void testKeyAtOutOfBounds() {
        sa.put(1, "one");
        sa.keyAt(5);
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void testValueAtOutOfBounds() {
        sa.put(1, "one");
        sa.valueAt(5);
    }

    @Test
    public void testIndexOfKey() {
        sa.put(10, "ten");
        sa.put(20, "twenty");
        assertEquals(0, sa.indexOfKey(10));
        assertEquals(1, sa.indexOfKey(20));
        assertTrue(sa.indexOfKey(99) < 0);
    }

    @Test
    public void testIndexOfValue() {
        String s1 = "one";
        String s2 = "two";
        sa.put(1, s1);
        sa.put(2, s2);
        assertEquals(0, sa.indexOfValue(s1));
        assertEquals(1, sa.indexOfValue(s2));
        assertTrue(sa.indexOfValue("missing") < 0);
    }

    @Test
    public void testClear() {
        sa.put(1, "one");
        sa.put(2, "two");
        sa.put(3, "three");
        sa.clear();
        assertEquals(0, sa.size());
        assertNull(sa.get(1));
        assertNull(sa.get(2));
        assertNull(sa.get(3));
    }

    @Test
    public void testAppend() {
        sa.append(1, "one");
        sa.append(2, "two");
        sa.append(3, "three");
        assertEquals(3, sa.size());
        assertEquals("one", sa.get(1));
        assertEquals("two", sa.get(2));
        assertEquals("three", sa.get(3));
    }

    @Test
    public void testAppendOutOfOrder() {
        sa.append(10, "ten");
        sa.append(5, "five");
        sa.append(15, "fifteen");
        assertEquals(3, sa.size());
        assertEquals("five", sa.valueAt(0));
        assertEquals("ten", sa.valueAt(1));
        assertEquals("fifteen", sa.valueAt(2));
    }

    @Test
    public void testClone() {
        sa.put(1, "one");
        sa.put(2, "two");
        SparseArray<String> clone = sa.clone();
        assertNotNull(clone);
        assertEquals(sa.size(), clone.size());
        assertEquals("one", clone.get(1));
        assertEquals("two", clone.get(2));
    }

    @Test
    public void testCloneIndependence() {
        sa.put(1, "one");
        SparseArray<String> clone = sa.clone();
        clone.put(2, "two");
        assertNull(sa.get(2));
        assertEquals(1, sa.size());
        assertEquals(2, clone.size());
    }

    @Test
    public void testInitialCapacity() {
        SparseArray<String> big = new SparseArray<String>(20);
        big.put(1, "one");
        big.put(2, "two");
        assertEquals(2, big.size());
        assertEquals("one", big.get(1));
    }

    @Test
    public void testZeroInitialCapacity() {
        SparseArray<String> zero = new SparseArray<String>(0);
        zero.put(1, "one");
        assertEquals(1, zero.size());
        assertEquals("one", zero.get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutNegativeKey() {
        sa.put(-1, "negative");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutKeyTooLarge() {
        sa.put(65536, "toolarge");
    }

    @Test
    public void testGetNegativeKey() {
        sa.put(1, "one");
        assertNull(sa.get(-1));
        assertEquals("default", sa.get(-1, "default"));
    }

    @Test
    public void testDeleteNegativeKey() {
        sa.put(1, "one");
        sa.delete(-1);
        assertEquals(1, sa.size());
    }

    @Test
    public void testRemoveAt() {
        sa.put(1, "one");
        sa.put(2, "two");
        sa.removeAt(0);
        assertEquals(1, sa.size());
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void testRemoveAtOutOfBounds() {
        sa.put(1, "one");
        sa.removeAt(5);
    }

    @Test
    public void testSetValueAt() {
        sa.put(1, "one");
        sa.setValueAt(0, "ONE");
        assertEquals("ONE", sa.valueAt(0));
    }

    @Test
    public void testToString() {
        sa.put(1, "one");
        String str = sa.toString();
        assertNotNull(str);
        assertTrue(str.contains("1=one"));
    }

    @Test
    public void testToStringEmpty() {
        assertEquals("{}", sa.toString());
    }

    @Test
    public void testMultipleOperations() {
        sa.put(5, "five");
        sa.put(3, "three");
        sa.put(7, "seven");
        sa.put(1, "one");
        assertEquals(4, sa.size());
        assertEquals(1, sa.keyAt(0));
        assertEquals(3, sa.keyAt(1));
        assertEquals(5, sa.keyAt(2));
        assertEquals(7, sa.keyAt(3));
        sa.delete(3);
        assertEquals(3, sa.size());
        sa.delete(7);
        assertEquals(2, sa.size());
        sa.put(10, "ten");
        assertEquals(3, sa.size());
    }

    @Test
    public void testDeleteThenReinsert() {
        sa.put(1, "one");
        sa.delete(1);
        assertNull(sa.get(1));
        sa.put(1, "ONE");
        assertEquals("ONE", sa.get(1));
        assertEquals(1, sa.size());
    }

    @Test
    public void testLargeValues() {
        for (int i = 0; i < 100; i++) {
            sa.put(i, "value" + i);
        }
        assertEquals(100, sa.size());
        for (int i = 0; i < 100; i++) {
            assertEquals("value" + i, sa.get(i));
        }
    }
}
