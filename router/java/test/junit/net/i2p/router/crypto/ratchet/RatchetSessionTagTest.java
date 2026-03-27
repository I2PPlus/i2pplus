package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import org.junit.Test;

public class RatchetSessionTagTest {

    @Test
    public void testLongConstructor() {
        long val = 0x0102030405060708L;
        RatchetSessionTag tag = new RatchetSessionTag(val);
        assertEquals(val, tag.getLong());
    }

    @Test
    public void testLongConstructorZero() {
        RatchetSessionTag tag = new RatchetSessionTag(0L);
        assertEquals(0L, tag.getLong());
    }

    @Test
    public void testLongConstructorNegative() {
        long val = -1L;
        RatchetSessionTag tag = new RatchetSessionTag(val);
        assertEquals(val, tag.getLong());
    }

    @Test
    public void testByteArrayConstructor() {
        byte[] data = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        RatchetSessionTag tag = new RatchetSessionTag(data);
        byte[] result = tag.getData();
        assertArrayEquals(data, result);
    }

    @Test
    public void testByteArrayConstructorZero() {
        byte[] data = new byte[8];
        RatchetSessionTag tag = new RatchetSessionTag(data);
        assertEquals(0L, tag.getLong());
    }

    @Test
    public void testByteArrayConstructorLongerArray() {
        byte[] data = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a};
        RatchetSessionTag tag = new RatchetSessionTag(data);
        byte[] result = tag.getData();
        assertEquals(8, result.length);
        assertEquals(0x01, result[0]);
        assertEquals(0x08, result[7]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testByteArrayConstructorTooShort() {
        byte[] data = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        new RatchetSessionTag(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testByteArrayConstructorEmpty() {
        new RatchetSessionTag(new byte[0]);
    }

    @Test
    public void testGetData() {
        long val = 0x0102030405060708L;
        RatchetSessionTag tag = new RatchetSessionTag(val);
        byte[] data = tag.getData();
        assertEquals(8, data.length);
        assertEquals((byte) 0x01, data[0]);
        assertEquals((byte) 0x08, data[7]);
    }

    @Test
    public void testLength() {
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        assertEquals(8, tag.length());
        assertEquals(8, RatchetSessionTag.LENGTH);
    }

    @Test
    public void testToBase64() {
        RatchetSessionTag tag = new RatchetSessionTag(0x0102030405060708L);
        String b64 = tag.toBase64();
        assertNotNull(b64);
        assertEquals(12, b64.length());
        assertTrue(b64.endsWith("="));
    }

    @Test
    public void testToBase64Consistency() {
        long val = 0x0102030405060708L;
        RatchetSessionTag tag1 = new RatchetSessionTag(val);
        RatchetSessionTag tag2 = new RatchetSessionTag(val);
        assertEquals(tag1.toBase64(), tag2.toBase64());
    }

    @Test
    public void testToString() {
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        String str = tag.toString();
        assertNotNull(str);
        assertTrue(str.startsWith("RatchetSessionTag: "));
    }

    @Test
    public void testEqualsSameValue() {
        RatchetSessionTag tag1 = new RatchetSessionTag(42L);
        RatchetSessionTag tag2 = new RatchetSessionTag(42L);
        assertEquals(tag1, tag2);
    }

    @Test
    public void testEqualsSameObject() {
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        assertEquals(tag, tag);
    }

    @Test
    public void testNotEqualsDifferentValue() {
        RatchetSessionTag tag1 = new RatchetSessionTag(42L);
        RatchetSessionTag tag2 = new RatchetSessionTag(43L);
        assertNotEquals(tag1, tag2);
    }

    @Test
    public void testNotEqualsNull() {
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        assertNotEquals(tag, null);
    }

    @Test
    public void testNotEqualsDifferentType() {
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        assertNotEquals(tag, "not a tag");
    }

    @Test
    public void testHashCodeSameValue() {
        RatchetSessionTag tag1 = new RatchetSessionTag(42L);
        RatchetSessionTag tag2 = new RatchetSessionTag(42L);
        assertEquals(tag1.hashCode(), tag2.hashCode());
    }

    @Test
    public void testHashCodeConsistency() {
        RatchetSessionTag tag = new RatchetSessionTag(12345L);
        int h1 = tag.hashCode();
        int h2 = tag.hashCode();
        assertEquals(h1, h2);
    }

    @Test
    public void testGetDataRoundTrip() {
        byte[] original = new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        RatchetSessionTag tag = new RatchetSessionTag(original);
        byte[] result = tag.getData();
        assertArrayEquals(original, result);
    }
}
