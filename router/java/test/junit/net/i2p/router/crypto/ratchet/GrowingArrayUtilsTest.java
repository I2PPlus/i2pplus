package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import org.junit.Test;

public class GrowingArrayUtilsTest {

    @Test
    public void testAppendObjectArrayWithinCapacity() {
        String[] arr = new String[5];
        arr[0] = "a";
        arr[1] = "b";
        String[] result = GrowingArrayUtils.append(arr, 2, "c");
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testAppendObjectArrayGrow() {
        String[] arr = new String[2];
        arr[0] = "a";
        arr[1] = "b";
        String[] result = GrowingArrayUtils.append(arr, 2, "c");
        assertTrue(result.length >= 3);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
        assertTrue(result.length >= 3);
    }

    @Test
    public void testAppendCharArrayWithinCapacity() {
        char[] arr = new char[5];
        arr[0] = 'a';
        arr[1] = 'b';
        char[] result = GrowingArrayUtils.append(arr, 2, 'c');
        assertEquals('a', result[0]);
        assertEquals('b', result[1]);
        assertEquals('c', result[2]);
    }

    @Test
    public void testAppendCharArrayGrow() {
        char[] arr = new char[2];
        arr[0] = 'a';
        arr[1] = 'b';
        char[] result = GrowingArrayUtils.append(arr, 2, 'c');
        assertTrue(result.length >= 3);
        assertEquals('a', result[0]);
        assertEquals('b', result[1]);
        assertEquals('c', result[2]);
    }

    @Test
    public void testInsertObjectArrayMiddle() {
        String[] arr = new String[5];
        arr[0] = "a";
        arr[1] = "c";
        String[] result = GrowingArrayUtils.insert(arr, 2, 1, "b");
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testInsertObjectArrayBeginning() {
        String[] arr = new String[5];
        arr[0] = "b";
        arr[1] = "c";
        String[] result = GrowingArrayUtils.insert(arr, 2, 0, "a");
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testInsertObjectArrayEnd() {
        String[] arr = new String[5];
        arr[0] = "a";
        arr[1] = "b";
        String[] result = GrowingArrayUtils.insert(arr, 2, 2, "c");
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testInsertObjectArrayGrow() {
        String[] arr = new String[2];
        arr[0] = "a";
        arr[1] = "c";
        String[] result = GrowingArrayUtils.insert(arr, 2, 1, "b");
        assertTrue(result.length >= 3);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testInsertCharArrayMiddle() {
        char[] arr = new char[5];
        arr[0] = 'a';
        arr[1] = 'c';
        char[] result = GrowingArrayUtils.insert(arr, 2, 1, 'b');
        assertEquals('a', result[0]);
        assertEquals('b', result[1]);
        assertEquals('c', result[2]);
    }

    @Test
    public void testInsertCharArrayGrow() {
        char[] arr = new char[2];
        arr[0] = 'a';
        arr[1] = 'c';
        char[] result = GrowingArrayUtils.insert(arr, 2, 1, 'b');
        assertTrue(result.length >= 3);
        assertEquals('a', result[0]);
        assertEquals('b', result[1]);
        assertEquals('c', result[2]);
    }

    @Test
    public void testGrowSizeSmall() {
        assertEquals(8, GrowingArrayUtils.growSize(0));
        assertEquals(8, GrowingArrayUtils.growSize(1));
        assertEquals(8, GrowingArrayUtils.growSize(2));
        assertEquals(8, GrowingArrayUtils.growSize(3));
        assertEquals(8, GrowingArrayUtils.growSize(4));
    }

    @Test
    public void testGrowSizeLarge() {
        assertEquals(10, GrowingArrayUtils.growSize(5));
        assertEquals(20, GrowingArrayUtils.growSize(10));
        assertEquals(100, GrowingArrayUtils.growSize(50));
    }

    @Test
    public void testAppendMultipleTimes() {
        String[] arr = new String[1];
        arr[0] = "a";
        String[] result = GrowingArrayUtils.append(arr, 1, "b");
        result = GrowingArrayUtils.append(result, 2, "c");
        result = GrowingArrayUtils.append(result, 3, "d");
        assertTrue(result.length >= 4);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
        assertEquals("d", result[3]);
    }

    @Test
    public void testAppendEmptyArray() {
        String[] arr = new String[0];
        String[] result = GrowingArrayUtils.append(arr, 0, "first");
        assertTrue(result.length >= 1);
        assertEquals("first", result[0]);
    }
}
