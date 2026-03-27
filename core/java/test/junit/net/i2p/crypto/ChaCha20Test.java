package net.i2p.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for ChaCha20 stream cipher.
 * Includes RFC 7539 Section 2.3.2 known-answer test vectors.
 */
public class ChaCha20Test {

    // RFC 7539 Section 2.3.2 test vector
    private static final byte[] RFC_KEY = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
    };

    private static final byte[] RFC_IV = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x4a, 0x00, 0x00, 0x00, 0x00};

    private static final byte[] RFC_PLAINTEXT = ("Ladies and Gentlemen of the class of '99: " + "If I could offer you only one tip for the future, " + "sunscreen would be it.").getBytes();

    private static final byte[] RFC_CIPHERTEXT = {
        0x6e,
        0x2e,
        0x35,
        (byte) 0x9a,
        0x25,
        0x68,
        (byte) 0xf9,
        (byte) 0x80,
        0x41,
        (byte) 0xba,
        0x07,
        0x28,
        (byte) 0xdd,
        0x0d,
        0x69,
        (byte) 0x81,
        (byte) 0xe9,
        0x7e,
        0x7a,
        (byte) 0xec,
        0x1d,
        0x43,
        0x60,
        (byte) 0xc2,
        0x0a,
        0x27,
        (byte) 0xaf,
        (byte) 0xcc,
        (byte) 0xfd,
        (byte) 0x9f,
        (byte) 0xae,
        0x0b,
        (byte) 0xf9,
        0x1b,
        0x65,
        (byte) 0xc5,
        0x52,
        0x47,
        0x33,
        (byte) 0xab,
        (byte) 0x8f,
        0x59,
        0x3d,
        (byte) 0xab,
        (byte) 0xcd,
        0x62,
        (byte) 0xb3,
        0x57,
        0x16,
        0x39,
        (byte) 0xd6,
        0x24,
        (byte) 0xe6,
        0x51,
        0x52,
        (byte) 0xab,
        (byte) 0x8f,
        0x53,
        0x0c,
        0x35,
        (byte) 0x9f,
        0x08,
        0x61,
        (byte) 0xd8,
        0x07,
        (byte) 0xca,
        0x0d,
        (byte) 0xbf,
        0x50,
        0x0d,
        0x6a,
        0x61,
        0x56,
        (byte) 0xa3,
        (byte) 0x8e,
        0x08,
        (byte) 0x8a,
        0x22,
        (byte) 0xb6,
        0x5e,
        0x52,
        (byte) 0xbc,
        0x51,
        0x4d,
        0x16,
        (byte) 0xcc,
        (byte) 0xf8,
        0x06,
        (byte) 0x81,
        (byte) 0x8c,
        (byte) 0xe9,
        0x1a,
        (byte) 0xb7,
        0x79,
        0x37,
        0x36,
        0x5a,
        (byte) 0xf9,
        0x0b,
        (byte) 0xbf,
        0x74,
        (byte) 0xa3,
        0x5b,
        (byte) 0xe6,
        (byte) 0xb4,
        0x0b,
        (byte) 0x8e,
        (byte) 0xed,
        (byte) 0xf2,
        0x78,
        0x5e,
        0x42,
        (byte) 0x87,
        0x4d
    };

    @Test
    public void testRFC7539Encrypt() {
        byte[] ciphertext = new byte[RFC_PLAINTEXT.length];
        ChaCha20.encrypt(RFC_KEY, RFC_IV, RFC_PLAINTEXT, 0, ciphertext, 0, RFC_PLAINTEXT.length);
        assertArrayEquals(RFC_CIPHERTEXT, ciphertext);
    }

    @Test
    public void testRFC7539Decrypt() {
        byte[] plaintext = new byte[RFC_CIPHERTEXT.length];
        ChaCha20.decrypt(RFC_KEY, RFC_IV, RFC_CIPHERTEXT, 0, plaintext, 0, RFC_CIPHERTEXT.length);
        assertArrayEquals(RFC_PLAINTEXT, plaintext);
    }

    @Test
    public void testRoundTripEmpty() {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] plain = new byte[0];
        byte[] cipher = new byte[0];
        ChaCha20.encrypt(key, iv, plain, 0, cipher, 0, 0);
        byte[] decrypted = new byte[0];
        ChaCha20.decrypt(key, iv, cipher, 0, decrypted, 0, 0);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void testRoundTripSingleByte() {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] plain = {0x42};
        byte[] cipher = new byte[1];
        ChaCha20.encrypt(key, iv, plain, 0, cipher, 0, 1);
        byte[] decrypted = new byte[1];
        ChaCha20.decrypt(key, iv, cipher, 0, decrypted, 0, 1);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void testRoundTripMultipleBlocks() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0xaa);
        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 0xbb);
        byte[] plain = new byte[1000];
        java.util.Arrays.fill(plain, (byte) 0x42);

        byte[] cipher = new byte[1000];
        ChaCha20.encrypt(key, iv, plain, 0, cipher, 0, 1000);

        byte[] decrypted = new byte[1000];
        ChaCha20.decrypt(key, iv, cipher, 0, decrypted, 0, 1000);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void testDifferentKeysProduceDifferentCiphertext() {
        byte[] key1 = new byte[32];
        byte[] key2 = new byte[32];
        key2[0] = 1;
        byte[] iv = new byte[12];
        byte[] plain = new byte[64];
        byte[] cipher1 = new byte[64];
        byte[] cipher2 = new byte[64];

        ChaCha20.encrypt(key1, iv, plain, 0, cipher1, 0, 64);
        ChaCha20.encrypt(key2, iv, plain, 0, cipher2, 0, 64);
        assertFalse(java.util.Arrays.equals(cipher1, cipher2));
    }

    @Test
    public void testDifferentIVsProduceDifferentCiphertext() {
        byte[] key = new byte[32];
        byte[] iv1 = new byte[12];
        byte[] iv2 = new byte[12];
        iv2[0] = 1;
        byte[] plain = new byte[64];
        byte[] cipher1 = new byte[64];
        byte[] cipher2 = new byte[64];

        ChaCha20.encrypt(key, iv1, plain, 0, cipher1, 0, 64);
        ChaCha20.encrypt(key, iv2, plain, 0, cipher2, 0, 64);
        assertFalse(java.util.Arrays.equals(cipher1, cipher2));
    }

    @Test
    public void testEncryptDecryptSymmetry() {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] data = new byte[256];
        java.util.Arrays.fill(data, (byte) 0x5a);

        byte[] encrypted = new byte[256];
        ChaCha20.encrypt(key, iv, data, 0, encrypted, 0, 256);

        byte[] backToPlain = new byte[256];
        ChaCha20.encrypt(key, iv, encrypted, 0, backToPlain, 0, 256);
        assertArrayEquals(data, backToPlain);
    }
}
