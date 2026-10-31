package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by dr|z3d in 2026 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 * Tests the cached short hash forms used to name a router or a destination in
 * a log line: that they agree with the truncation they replace, that they are
 * cached, and that the data is write-once, so a cached form cannot go stale.
 *
 */
public class HashShortFormTest {

    private static Hash randomHash(long seed) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        new Random(seed).nextBytes(data);
        return new Hash(data);
    }

    @Test
    public void testShortBase64MatchesTruncation() {
        Hash h = randomHash(0x1234L);
        assertEquals(6, h.toShortBase64().length());
        assertEquals(h.toBase64().substring(0, 6), h.toShortBase64());
    }

    @Test
    public void testShortBase32MatchesTruncation() {
        Hash h = randomHash(0x1234L);
        assertEquals(8, h.toShortBase32().length());
        assertEquals(h.toBase32().substring(0, 8), h.toShortBase32());
        // the address suffix is not part of the short form
        assertFalse(h.toShortBase32().contains("."));
    }

    @Test
    public void testShortFormsAreCached() {
        Hash h = randomHash(0x5678L);
        assertSame("a hot log path must not allocate per call",
                   h.toShortBase64(), h.toShortBase64());
        assertSame(h.toShortBase32(), h.toShortBase32());
    }

    @Test
    public void testTheDataIsWriteOnce() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Arrays.fill(data, (byte) 1);
        Hash h = new Hash(data);
        String short64 = h.toShortBase64();
        // Both setters refuse a second write, so the cached short forms cannot
        // go stale - the reason there is no invalidation test here.
        try {
            h.setData(data);
            fail("Expected RuntimeException for a second setData");
        } catch (RuntimeException re) {
            // expected
        }
        try {
            h.readBytes(new ByteArrayInputStream(data));
            fail("Expected RuntimeException for a second readBytes");
        } catch (RuntimeException re) {
            // expected
        } catch (Exception e) {
            fail("Expected RuntimeException, got " + e);
        }
        assertEquals(short64, h.toShortBase64());
    }

    @Test
    public void testUnsetDataHasNoShortForm() {
        Hash h = new Hash();
        assertNull(h.toShortBase64());
        assertNull(h.toShortBase32());
    }

    @Test
    public void testSetDataOnAFreshHash() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        Arrays.fill(data, (byte) 3);
        Hash h = new Hash();
        h.setData(data);
        assertEquals(new Hash(data).toShortBase64(), h.toShortBase64());
        assertEquals(new Hash(data).toShortBase32(), h.toShortBase32());
    }

    @Test
    public void testShortFormsDistinguishHashes() {
        Hash h = randomHash(0x9abcL);
        Hash other = randomHash(0x9abdL);
        assertNotEquals(h.toShortBase64(), other.toShortBase64());
        assertNotEquals(h.toShortBase32(), other.toShortBase32());
    }
}
