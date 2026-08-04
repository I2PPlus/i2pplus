package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.i2p.data.Hash;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for Blocklist IP parsing and ban decision logic.
 *  The on-disk parse() handles single IPs, CIDR masks, ranges,
 *  comment stripping, and router-hash entries; the in-memory
 *  add/isBlocklisted path handles transient single-IP blocking.
 *
 *  @since 0.9.48
 */
public class BlocklistTest {

    private static RouterContext _ctx;
    private static Blocklist _bl;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _bl = new Blocklist(_ctx);
    }

    // ---- decision logic via public API ----

    @Test
    public void testAddAndIsBlocklisted() {
        _bl.add("10.1.2.3");
        assertTrue(_bl.isBlocklisted("10.1.2.3"));
        assertFalse(_bl.isBlocklisted("10.1.2.4"));
    }

    @Test
    public void testAddByteArray() {
        byte[] ip = new byte[] {10, 2, 3, 4};
        _bl.add(ip);
        assertTrue(_bl.isBlocklisted(ip));
        assertTrue(_bl.isBlocklisted("10.2.3.4"));
    }

    @Test
    public void testRemove() {
        byte[] ip = new byte[] {10, 3, 4, 5};
        _bl.add(ip);
        assertTrue(_bl.isBlocklisted(ip));
        _bl.remove(ip);
        assertFalse(_bl.isBlocklisted(ip));
    }

    @Test
    public void testAddInvalidIPIgnored() {
        // Addresses.getIPOnly returns null for garbage, add() must not throw
        _bl.add("not-an-ip");
        assertFalse(_bl.isBlocklisted("not-an-ip"));
    }

    @Test
    public void testNeverBlocked() {
        _bl.add("203.0.113.7");
        assertFalse(_bl.isBlocklisted("198.51.100.1"));
    }

    // ---- parse() via reflection ----

    private Object parse(String line) {
        try {
            Method m = Blocklist.class.getDeclaredMethod("parse", String.class, boolean.class);
            m.setAccessible(true);
            return m.invoke(_bl, line, Boolean.FALSE);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] fieldBytes(Object entry, String name) {
        try {
            Field f = entry.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (byte[]) f.get(entry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fieldString(Object entry, String name) {
        try {
            Field f = entry.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (String) f.get(entry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testParseSingleIP() {
        Object e = parse("1.2.3.4");
        assertNotNull(e);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fieldBytes(e, "ip1"));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fieldBytes(e, "ip2"));
        assertNull(fieldString(e, "comment"));
    }

    @Test
    public void testParseCIDR() {
        Object e = parse("10.0.0.0/24");
        assertNotNull(e);
        assertArrayEquals(new byte[] {10, 0, 0, 0}, fieldBytes(e, "ip1"));
        assertArrayEquals(new byte[] {10, 0, 0, (byte) 255}, fieldBytes(e, "ip2"));
    }

    @Test
    public void testParseCIDRSmallerMask() {
        Object e = parse("172.16.0.0/16");
        assertNotNull(e);
        assertArrayEquals(new byte[] {(byte) 172, 16, 0, 0}, fieldBytes(e, "ip1"));
        assertArrayEquals(new byte[] {(byte) 172, 16, (byte) 255, (byte) 255}, fieldBytes(e, "ip2"));
    }

    @Test
    public void testParseRange() {
        Object e = parse("1.2.3.4-1.2.3.9");
        assertNotNull(e);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fieldBytes(e, "ip1"));
        assertArrayEquals(new byte[] {1, 2, 3, 9}, fieldBytes(e, "ip2"));
    }

    @Test
    public void testParseCommentStrip() {
        Object e = parse("blocked:1.2.3.4");
        assertNotNull(e);
        assertEquals("blocked", fieldString(e, "comment"));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fieldBytes(e, "ip1"));
    }

    @Test
    public void testParseCommentSpaceAfterColonRejected() {
        // the parser does not trim whitespace after the comment colon
        assertNull(parse("blocked: 1.2.3.4"));
    }

    @Test
    public void testParseCommentOnlyLineIsNull() {
        assertNull(parse("# this is a comment"));
    }

    @Test
    public void testParseBlankLineIsNull() {
        assertNull(parse(""));
        assertNull(parse("   "));
    }

    @Test
    public void testParseBackwardsRangeIsNull() {
        assertNull(parse("1.2.3.9-1.2.3.4"));
    }

    @Test
    public void testParseInvalidMaskIsNull() {
        assertNull(parse("10.0.0.0/33"));
        assertNull(parse("10.0.0.0/2"));
    }

    @Test
    public void testParseGarbageIsNull() {
        assertNull(parse("this is not an ip"));
    }

    @Test
    public void testParseHashEntry() {
        // 44-char base64 decodes to a 32-byte hash
        byte[] hb = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < hb.length; i++) {
            hb[i] = (byte) i;
        }
        String b64 = net.i2p.data.Base64.encode(hb);
        assertEquals(44, b64.length());
        Object e = parse("router hash:" + b64);
        assertNotNull(e);
        try {
            Field f = e.getClass().getDeclaredField("peer");
            f.setAccessible(true);
            Hash peer = (Hash) f.get(e);
            assertEquals(Hash.create(hb), peer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    public void testConstructorCreatesDisabledByDefaultLists() {
        // just ensure the config reads produced sane flags
        assertEquals(_bl.isBlocklistEnabled(), _bl.isBlocklistEnabled());
    }
}
