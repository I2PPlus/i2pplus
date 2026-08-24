package net.i2p.router.web;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Test;

/**
 * Tests for ConsolePasswordManager.HexDecode()/HexEncode().
 *
 * Regression guard for bd6fb3cc0a: that commit delegated hex decoding to
 * DataHelper.fromHexString(), which is a minimum-length, sign-aware
 * BigInteger decode. A leading '0' nibble loses a byte (32 stored bytes
 * decode to 31) and a first nibble of 8-f gains an extra 0x00 byte (decodes
 * to 33). PBKDF2 salts and hashes must round-trip at their exact stored
 * width, so roughly 15 of 16 credentials became unverifiable and console
 * login failed with unchanged passwords.
 *
 * The vectors below are literal hex strings on purpose: they pin the decode
 * contract independently of our own encoder, so a swap to any
 * variable-length decoder fails here immediately.
 *
 * @since 0.9.71+
 */
public class ConsolePasswordManagerHexDecodeTest {

    @Test
    public void testLeadingZeroByteKeepsFullWidth() {
        // fromHexString() returns 31 bytes for this; login broke on exactly this.
        byte[] out = ConsolePasswordManager.HexDecode(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        assertEquals(32, out.length);
        assertEquals(0, out[0]);
        assertEquals(0x1f, out[31] & 0xff);
    }

    @Test
    public void testHighBitFirstByteStaysOneByteWide() {
        // fromHexString() returns {0x00, 0xff} (2 bytes, sign padding) for "ff".
        byte[] single = ConsolePasswordManager.HexDecode("ff");
        assertEquals(1, single.length);
        assertEquals((byte) 0xff, single[0]);

        // ...and 33 bytes for a full hash starting with f.
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {sb.append('f');}
        assertEquals(32, ConsolePasswordManager.HexDecode(sb.toString()).length);
    }

    @Test
    public void testKnownVectors() {
        assertArrayEquals(new byte[] {0}, ConsolePasswordManager.HexDecode("00"));
        assertArrayEquals(new byte[] {1}, ConsolePasswordManager.HexDecode("01"));
        assertArrayEquals(new byte[] {(byte) 0x7f, (byte) 0x80},
                ConsolePasswordManager.HexDecode("7f80"));
        assertArrayEquals(new byte[] {(byte) 0xab, (byte) 0xcd},
                ConsolePasswordManager.HexDecode("ABcd"));
    }

    @Test
    public void testAllByteValuesRoundTripAtFixedWidth() {
        // Covers every leading-nibble case: stripped zeros, sign-extended highs.
        byte[] src = new byte[256];
        for (int i = 0; i < 256; i++) {src[i] = (byte) i;}
        String hex = ConsolePasswordManager.HexEncode(src);
        assertEquals(512, hex.length());
        assertArrayEquals(src, ConsolePasswordManager.HexDecode(hex));
    }

    @Test
    public void testRandomSaltsAndHashesRoundTripAtFixedWidth() {
        Random r = new Random(12345);
        for (int i = 0; i < 100; i++) {
            byte[] salt = new byte[32];
            byte[] hash = new byte[32];
            r.nextBytes(salt);
            r.nextBytes(hash);
            assertArrayEquals(salt,
                    ConsolePasswordManager.HexDecode(ConsolePasswordManager.HexEncode(salt)));
            assertArrayEquals(hash,
                    ConsolePasswordManager.HexDecode(ConsolePasswordManager.HexEncode(hash)));
        }
    }

    @Test
    public void testInvalidInputThrows() {
        try {
            ConsolePasswordManager.HexDecode("abc");
            fail("odd-length accepted");
        } catch (IllegalArgumentException expected) {}
        try {
            ConsolePasswordManager.HexDecode("zz");
            fail("non-hex accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
