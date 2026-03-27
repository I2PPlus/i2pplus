package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class HexDumpTest {

    @Test
    public void testDumpEmpty() {
        String result = HexDump.dump(new byte[0]);
        assertNotNull(result);
        assertEquals("", result);
    }

    @Test
    public void testDumpSingleByte() {
        String result = HexDump.dump(new byte[] {0x42});
        assertTrue(result.contains("42"));
    }

    @Test
    public void testDumpWithOffset() {
        byte[] data = {0x00, 0x01, 0x02, 0x03, 0x04};
        String result = HexDump.dump(data, 1, 3);
        assertTrue(result.contains("01"));
        assertTrue(result.contains("02"));
        assertTrue(result.contains("03"));
    }

    @Test
    public void testDumpFullRow() {
        byte[] data = new byte[16];
        for (int i = 0; i < 16; i++) data[i] = (byte) i;
        String result = HexDump.dump(data);
        assertTrue(result.contains("00 01 02"));
        assertTrue(result.contains("0f"));
    }

    @Test
    public void testDumpMultipleRows() {
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) data[i] = (byte) (i + 0x10);
        String result = HexDump.dump(data);
        String[] lines = result.split("\n");
        assertTrue(lines.length >= 2);
    }

    @Test
    public void testDump256Bytes() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;
        String result = HexDump.dump(data);
        assertTrue(result.contains("ff"));
        String[] lines = result.split("\n");
        assertEquals(16, lines.length);
    }

    @Test
    public void testDumpIncludesAscii() {
        byte[] data = "Hello".getBytes();
        String result = HexDump.dump(data);
        assertTrue(result.contains("Hello"));
    }

    @Test
    public void testDumpNonPrintable() {
        byte[] data = {0x00, 0x01, 0x7f};
        String result = HexDump.dump(data);
        assertNotNull(result);
        assertTrue(result.contains("00 01 7f"));
    }

    @Test
    public void testDumpToStream() throws Exception {
        byte[] data = {0x41, 0x42, 0x43};
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        HexDump.dump(data, 0, data.length, baos);
        String result = baos.toString("UTF-8");
        assertTrue(result.contains("41 42 43"));
    }
}
