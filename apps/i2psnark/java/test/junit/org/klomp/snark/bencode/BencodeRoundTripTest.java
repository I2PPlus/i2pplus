package org.klomp.snark.bencode;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 *  Tests for BEncoder and BDecoder round-trip encoding/decoding.
 *  Pure I/O classes - no I2P context needed.
 */
public class BencodeRoundTripTest {

    @Test
    public void testEncodeDecodeString() throws Exception {
        String original = "hello world";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(original, baos);
        byte[] encoded = baos.toByteArray();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(original, decoded.getString());
    }

    @Test
    public void testEncodeDecodeEmptyString() throws Exception {
        String original = "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(original, baos);
        byte[] encoded = baos.toByteArray();
        assertNotNull(encoded);

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(original, decoded.getString());
    }

    @Test
    public void testEncodeDecodeInteger() throws Exception {
        int value = 42;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(value, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(value, decoded.getInt());
    }

    @Test
    public void testEncodeDecodeLong() throws Exception {
        long value = 1234567890123L;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(value, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(value, decoded.getLong());
    }

    @Test
    public void testEncodeDecodeNegativeInteger() throws Exception {
        int value = -100;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(value, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(value, decoded.getInt());
    }

    @Test
    public void testEncodeDecodeZero() throws Exception {
        int value = 0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(value, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertEquals(0, decoded.getInt());
    }

    @Test
    public void testEncodeDecodeByteArray() throws Exception {
        byte[] original = {1, 2, 3, 4, 5};
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(original, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        assertArrayEquals(original, decoded.getBytes());
    }

    @Test
    public void testEncodeDecodeList() throws Exception {
        List<Object> list = new ArrayList<>();
        list.add("hello");
        list.add(42);
        list.add("world");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(list, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        List<BEValue> result = decoded.getList();
        assertEquals(3, result.size());
        assertEquals("hello", result.get(0).getString());
        assertEquals(42, result.get(1).getInt());
        assertEquals("world", result.get(2).getString());
    }

    @Test
    public void testEncodeDecodeMap() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "test");
        map.put("value", 123);

        byte[] encoded = BEncoder.bencode(map);
        assertNotNull(encoded);

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        Map<String, BEValue> result = decoded.getMap();
        assertEquals("test", result.get("name").getString());
        assertEquals(123, result.get("value").getInt());
    }

    @Test
    public void testEncodeDecodeNestedList() throws Exception {
        List<Object> inner = new ArrayList<>();
        inner.add("a");
        inner.add("b");

        List<Object> outer = new ArrayList<>();
        outer.add(inner);
        outer.add("c");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode(outer, baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        BEValue decoded = decoder.bdecode();
        List<BEValue> result = decoded.getList();
        assertEquals(2, result.size());

        List<BEValue> innerResult = result.get(0).getList();
        assertEquals(2, innerResult.size());
        assertEquals("a", innerResult.get(0).getString());
        assertEquals("b", innerResult.get(1).getString());
        assertEquals("c", result.get(1).getString());
    }

    @Test
    public void testEncodeDecodeMultipleValues() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BEncoder.bencode("first", baos);
        BEncoder.bencode(2, baos);
        BEncoder.bencode("third", baos);
        byte[] encoded = baos.toByteArray();

        BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
        assertEquals("first", decoder.bdecode().getString());
        assertEquals(2, decoder.bdecode().getInt());
        assertEquals("third", decoder.bdecode().getString());
    }

    @Test(expected = IOException.class)
    public void testDecodeEmptyStream() throws Exception {
        BDecoder decoder = new BDecoder(new ByteArrayInputStream(new byte[0]));
        decoder.bdecode();
    }
}
