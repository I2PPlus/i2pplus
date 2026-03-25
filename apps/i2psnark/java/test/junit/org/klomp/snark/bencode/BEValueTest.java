package org.klomp.snark.bencode;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 *  Tests for BEValue.
 *  Pure data class - no I2P context needed.
 */
public class BEValueTest {

    @Test
    public void testByteArrayConstructor() throws Exception {
        byte[] data = "hello".getBytes("UTF-8");
        BEValue bv = new BEValue(data);
        assertArrayEquals(data, bv.getBytes());
        assertEquals("hello", bv.getString());
    }

    @Test
    public void testNumberConstructor() throws Exception {
        BEValue bv = new BEValue(42);
        assertEquals(42, bv.getInt());
        assertEquals(42L, bv.getLong());
    }

    @Test
    public void testLongConstructor() throws Exception {
        BEValue bv = new BEValue(1234567890123L);
        assertEquals(1234567890123L, bv.getLong());
    }

    @Test
    public void testListConstructor() throws Exception {
        List<BEValue> list = new ArrayList<>();
        list.add(new BEValue(1));
        list.add(new BEValue("two".getBytes("UTF-8")));
        BEValue bv = new BEValue(list);
        List<BEValue> result = bv.getList();
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getInt());
        assertEquals("two", result.get(1).getString());
    }

    @Test
    public void testMapConstructor() throws Exception {
        Map<String, BEValue> map = new HashMap<>();
        map.put("key", new BEValue("value".getBytes("UTF-8")));
        BEValue bv = new BEValue(map);
        Map<String, BEValue> result = bv.getMap();
        assertEquals("value", result.get("key").getString());
    }

    @Test(expected = InvalidBEncodingException.class)
    public void testGetBytesOnNumber() throws Exception {
        BEValue bv = new BEValue(42);
        bv.getBytes();
    }

    @Test(expected = InvalidBEncodingException.class)
    public void testGetStringOnNumber() throws Exception {
        BEValue bv = new BEValue(42);
        bv.getString();
    }

    @Test(expected = InvalidBEncodingException.class)
    public void testGetNumberOnBytes() throws Exception {
        BEValue bv = new BEValue("hello".getBytes("UTF-8"));
        bv.getNumber();
    }

    @Test(expected = InvalidBEncodingException.class)
    public void testGetListOnBytes() throws Exception {
        BEValue bv = new BEValue("hello".getBytes("UTF-8"));
        bv.getList();
    }

    @Test(expected = InvalidBEncodingException.class)
    public void testGetMapOnNumber() throws Exception {
        BEValue bv = new BEValue(42);
        bv.getMap();
    }

    @Test
    public void testGetValue() {
        byte[] data = "test".getBytes();
        BEValue bv = new BEValue(data);
        assertSame(data, bv.getValue());
    }

    @Test
    public void testToStringBytes() {
        BEValue bv = new BEValue("hello".getBytes());
        String s = bv.toString();
        assertNotNull(s);
        assertTrue(s.contains("BEValue"));
    }

    @Test
    public void testToStringNumber() {
        BEValue bv = new BEValue(42);
        String s = bv.toString();
        assertNotNull(s);
        assertTrue(s.contains("42"));
    }

    @Test
    public void testEmptyByteArray() throws Exception {
        BEValue bv = new BEValue(new byte[0]);
        assertEquals(0, bv.getBytes().length);
    }

    @Test
    public void testZeroNumber() throws Exception {
        BEValue bv = new BEValue(0);
        assertEquals(0, bv.getInt());
        assertEquals(0L, bv.getLong());
    }

    @Test
    public void testNegativeNumber() throws Exception {
        BEValue bv = new BEValue(-1);
        assertEquals(-1, bv.getInt());
        assertEquals(-1L, bv.getLong());
    }
}
