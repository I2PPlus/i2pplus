package net.i2p.data;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 *  Boundary and edge-case tests for DataHelper string escaping,
 *  integer serialization, and charset round-trips. These helpers
 *  are used in user-facing output, so escaping mistakes are
 *  cross-site-scripting bugs; serialization mistakes corrupt
 *  wire-format data.
 *
 *  @since 0.8.3
 */
public class DataHelperEdgeTest {

    // ---- HTML escaping ----

    @Test
    public void testEscapeHTMLSpecialChars() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;",
                     DataHelper.escapeHTML("&<>\"'"));
    }

    @Test
    public void testEscapeHTMLPlainText() {
        assertEquals("hello world", DataHelper.escapeHTML("hello world"));
    }

    @Test
    public void testEscapeHTMLNull() {
        assertNull(DataHelper.escapeHTML(null));
    }

    @Test
    public void testEscapeHTMLEmpty() {
        assertEquals("", DataHelper.escapeHTML(""));
    }

    @Test
    public void testEscapeHTMLMixed() {
        assertEquals("a &amp; b", DataHelper.escapeHTML("a & b"));
    }

    @Test
    public void testStripHTML() {
        // each special char becomes a single space, leading space preserved
        assertEquals(" x   y   z ", DataHelper.stripHTML("<x> \"y\" 'z'"));
        assertEquals("", DataHelper.stripHTML(null));
        assertEquals("", DataHelper.stripHTML(""));
    }

    // ---- getASCII / getUTF8 ----

    @Test
    public void testGetASCII() {
        byte[] rv = DataHelper.getASCII("I2P!");
        assertEquals(4, rv.length);
        assertArrayEquals(new byte[] {0x49, 0x32, 0x50, 0x21}, rv);
    }

    @Test
    public void testGetUTF8RoundTrip() {
        String s = "caf\u00e9 \u20ac";
        assertArrayEquals(DataHelper.getUTF8(s), s.getBytes(StandardCharsets.UTF_8));
        assertEquals(s, new String(DataHelper.getUTF8(s), StandardCharsets.UTF_8));
    }

    // ---- toLong / fromLong boundary values ----

    @Test
    public void testToFromLongZero() throws Exception {
        byte[] b = DataHelper.toLong(4, 0);
        assertEquals(0, DataHelper.fromLong(b, 0, 4));
    }

    @Test
    public void testToFromLongMax4Byte() throws Exception {
        byte[] b = DataHelper.toLong(4, 0xFFFFFFFFL);
        assertEquals(0xFFFFFFFFL, DataHelper.fromLong(b, 0, 4));
    }

    @Test
    public void testToFromLong8Byte() throws Exception {
        byte[] b = DataHelper.toLong(8, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, DataHelper.fromLong(b, 0, 8));
    }

    @Test
    public void testToLongValueFitsIn1Byte() throws Exception {
        byte[] b = DataHelper.toLong(1, 0x7F);
        assertArrayEquals(new byte[] {0x7F}, b);
        assertEquals(0x7F, DataHelper.fromLong(b, 0, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testToLongNegativeRejected() {
        DataHelper.toLong(4, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testToLongTooManyBytesRejected() {
        DataHelper.toLong(9, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testToLongZeroBytesRejected() {
        DataHelper.toLong(0, 1);
    }
}
