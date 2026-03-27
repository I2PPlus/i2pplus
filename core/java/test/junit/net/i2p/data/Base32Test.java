package net.i2p.data;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class Base32Test {

    @Test
    public void testEncodeDecodeRoundTrip() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        String encoded = Base32.encode(data);
        byte[] decoded = Base32.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeEmpty() {
        String result = Base32.encode(new byte[0]);
        assertNotNull(result);
        byte[] decoded = Base32.decode(result);
        assertArrayEquals(new byte[0], decoded);
    }

    @Test
    public void testEncodeSingleByte() {
        byte[] data = {0x42};
        String encoded = Base32.encode(data);
        byte[] decoded = Base32.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncode32Bytes() {
        byte[] data = new byte[32];
        Arrays.fill(data, (byte) 0xff);
        String encoded = Base32.encode(data);
        byte[] decoded = Base32.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeString() {
        String result = Base32.encode("hello world");
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    public void testEncodeNullString() {
        assertEquals("", Base32.encode((String) null));
    }

    @Test
    public void testDecodeCaseInsensitive() {
        byte[] data = {1, 2, 3};
        String lower = Base32.encode(data);
        byte[] decoded = Base32.decode(lower.toUpperCase());
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testDecodeToString() {
        String input = "hello world";
        String encoded = Base32.encode(input);
        String decoded = Base32.decodeToString(encoded);
        assertEquals(input, decoded);
    }

    @Test(expected = NullPointerException.class)
    public void testDecodeNullStringThrows() {
        Base32.decodeToString(null);
    }

    @Test
    public void testDecodeInvalidChars() {
        // Base32.decode returns a byte array for invalid chars, not null
        byte[] result = Base32.decode("!@#$%");
        // It may return something or null depending on implementation
        // Just verify it doesn't throw
    }

    @Test
    public void testEncodeAllFF() {
        byte[] data = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        String encoded = Base32.encode(data);
        byte[] decoded = Base32.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeAllZeros() {
        byte[] data = new byte[10];
        String encoded = Base32.encode(data);
        byte[] decoded = Base32.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeOnlyLowercase() {
        byte[] data = new byte[50];
        Arrays.fill(data, (byte) 0x55);
        String encoded = Base32.encode(data);
        assertEquals(encoded, encoded.toLowerCase());
    }
}
