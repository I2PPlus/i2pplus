package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for BitField.
 *  Pure data class - no I2P context needed.
 */
public class BitFieldTest {

    @Test
    public void testConstructorSize() {
        BitField bf = new BitField(100);
        assertEquals(100, bf.size());
        assertEquals(0, bf.count());
        assertFalse(bf.complete());
    }

    @Test
    public void testConstructorExactByteBoundary() {
        BitField bf = new BitField(8);
        assertEquals(8, bf.size());
        assertEquals(0, bf.count());
    }

    @Test
    public void testConstructorSingleBit() {
        BitField bf = new BitField(1);
        assertEquals(1, bf.size());
        assertEquals(0, bf.count());
    }

    @Test
    public void testSetAndGet() {
        BitField bf = new BitField(16);
        bf.set(0);
        assertTrue(bf.get(0));
        assertFalse(bf.get(1));
        assertEquals(1, bf.count());

        bf.set(15);
        assertTrue(bf.get(15));
        assertEquals(2, bf.count());
    }

    @Test
    public void testSetMultiple() {
        BitField bf = new BitField(20);
        for (int i = 0; i < 20; i += 2) {
            bf.set(i);
        }
        assertEquals(10, bf.count());
        for (int i = 0; i < 20; i++) {
            assertEquals(i % 2 == 0, bf.get(i));
        }
    }

    @Test
    public void testClear() {
        BitField bf = new BitField(8);
        bf.set(3);
        assertTrue(bf.get(3));
        assertEquals(1, bf.count());

        bf.clear(3);
        assertFalse(bf.get(3));
        assertEquals(0, bf.count());
    }

    @Test
    public void testSetAll() {
        BitField bf = new BitField(13);
        bf.setAll();
        assertEquals(13, bf.count());
        assertTrue(bf.complete());
        for (int i = 0; i < 13; i++) {
            assertTrue(bf.get(i));
        }
    }

    @Test
    public void testComplete() {
        BitField bf = new BitField(5);
        assertFalse(bf.complete());
        for (int i = 0; i < 5; i++) {
            bf.set(i);
        }
        assertTrue(bf.complete());
    }

    @Test
    public void testSetIdempotent() {
        BitField bf = new BitField(10);
        bf.set(5);
        bf.set(5);
        bf.set(5);
        assertEquals(1, bf.count());
    }

    @Test
    public void testClearIdempotent() {
        BitField bf = new BitField(10);
        bf.clear(5);
        bf.clear(5);
        assertEquals(0, bf.count());
    }

    @Test
    public void testConstructorFromBytes() {
        // Set bits 0 and 7 (first byte = 0x81)
        byte[] data = {(byte) 0x81, 0x00};
        BitField bf = new BitField(data, 16);
        assertTrue(bf.get(0));
        assertTrue(bf.get(7));
        assertFalse(bf.get(1));
        assertEquals(2, bf.count());
    }

    @Test
    public void testGetFieldBytes() {
        BitField bf = new BitField(16);
        bf.set(0);
        bf.set(7);
        byte[] bytes = bf.getFieldBytes();
        assertEquals((byte) 0x81, bytes[0]);
    }

    @Test
    public void testEquals() {
        BitField bf1 = new BitField(8);
        BitField bf2 = new BitField(8);
        bf1.set(3);
        bf2.set(3);
        assertEquals(bf1, bf2);
    }

    @Test
    public void testNotEqualsDifferentSize() {
        BitField bf1 = new BitField(8);
        BitField bf2 = new BitField(16);
        assertNotEquals(bf1, bf2);
    }

    @Test
    public void testNotEqualsDifferentBits() {
        BitField bf1 = new BitField(8);
        BitField bf2 = new BitField(8);
        bf1.set(0);
        bf2.set(1);
        assertNotEquals(bf1, bf2);
    }

    @Test
    public void testHashCode() {
        BitField bf1 = new BitField(8);
        BitField bf2 = new BitField(8);
        assertEquals(bf1.hashCode(), bf2.hashCode());
    }

    @Test
    public void testToString() {
        BitField bf = new BitField(8);
        bf.set(0);
        String s = bf.toString();
        assertNotNull(s);
        assertTrue(s.contains("BitField"));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testSetOutOfBounds() {
        BitField bf = new BitField(10);
        bf.set(10);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testSetNegative() {
        BitField bf = new BitField(10);
        bf.set(-1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetOutOfBounds() {
        BitField bf = new BitField(10);
        bf.get(10);
    }

    @Test
    public void testByteBoundaryBits() {
        // Test bits across byte boundaries (0-7 in byte 0, 8-15 in byte 1)
        BitField bf = new BitField(16);
        bf.set(7);
        bf.set(8);
        assertTrue(bf.get(7));
        assertTrue(bf.get(8));
        assertEquals(2, bf.count());
    }
}
