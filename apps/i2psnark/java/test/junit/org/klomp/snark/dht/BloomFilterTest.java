package org.klomp.snark.dht;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the BEP 33 bloom filter against the test vector from the
 * specification: inserting the IPv4 range 192.0.2.0 - 192.0.2.255 and the
 * IPv6 range 2001:DB8:: - 2001:DB8::3E7 (1256 addresses) must produce the
 * exact byte array given in the BEP, and the size estimate for it must be
 * 1224.9308.
 *
 * @since 0.9.71+
 */
public class BloomFilterTest {

    @Test
    public void testVector() {
        BloomFilter filter = new BloomFilter();
        // 192.0.2.0 - 192.0.2.255
        for (int i = 0; i < 256; i++) {
            filter.insert(new byte[] {(byte) 192, (byte) 0, (byte) 2, (byte) i});
        }
        // 2001:DB8:: - 2001:DB8::3E7
        for (int i = 0; i <= 0x3E7; i++) {
            filter.insert(
                    new byte[] {
                        0x20, 0x01, 0x0D, (byte) 0xB8,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        (byte) (i >> 8), (byte) i
                    });
        }
        assertArrayEquals(EXPECTED, filter.getData());
        assertEquals(1224.9308, filter.estimateSize(), 0.001);
    }

    @Test
    public void testEmpty() {
        BloomFilter filter = new BloomFilter();
        assertEquals(2048, filter.countZeroBits());
        assertEquals(0.5, filter.estimateSize(), 0.000001);
        assertFalse(filter.contains(new byte[] {1, 2, 3, 4}));
    }

    @Test
    public void testInsertAndContains() {
        BloomFilter filter = new BloomFilter();
        byte[] value = new byte[32];
        for (int i = 0; i < 32; i++) {
            value[i] = (byte) i;
        }
        filter.insert(value);
        assertTrue(filter.contains(value));
        assertFalse(filter.contains(new byte[] {1, 2, 3, 4}));
        assertEquals(2046, filter.countZeroBits());
    }

    @Test
    public void testFull() {
        BloomFilter filter = new BloomFilter();
        for (int i = 0; i < 10000; i++) {
            byte[] value = new byte[32];
            for (int j = 0; j < 4; j++) {
                value[j] = (byte) (i >> (8 * j));
            }
            filter.insert(value);
        }
        assertEquals(0, filter.countZeroBits());
        assertEquals(0.0, filter.estimateSize(), 0.000001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBadSize() {
        new BloomFilter(new byte[255]);
    }

    private static final byte[] EXPECTED = {
        (byte) 0xF6, (byte) 0xC3, (byte) 0xF5, (byte) 0xEA, (byte) 0xA0, 0x7F, (byte) 0xFD, (byte) 0x91,
        (byte) 0xBD, (byte) 0xE8, (byte) 0x9F, 0x77, 0x7F, 0x26, (byte) 0xFB, 0x2B,
        (byte) 0xFF, 0x37, (byte) 0xBD, (byte) 0xB8, (byte) 0xFB, 0x2B, (byte) 0xBA, (byte) 0xA2,
        (byte) 0xFD, 0x3D, (byte) 0xDD, (byte) 0xE7, (byte) 0xBA, (byte) 0xCF, (byte) 0xFF, 0x75,
        (byte) 0xEE, 0x7C, (byte) 0xCB, (byte) 0xAE, (byte) 0xFE, 0x5E, (byte) 0xED, (byte) 0xB1,
        (byte) 0xFB, (byte) 0xFA, (byte) 0xFF, 0x67, (byte) 0xF6, (byte) 0xAB, (byte) 0xFF, 0x5E,
        0x43, (byte) 0xDD, (byte) 0xBC, (byte) 0xA3, (byte) 0xFD, (byte) 0x9B, (byte) 0x9F, (byte) 0xFD,
        (byte) 0xF4, (byte) 0xFF, (byte) 0xD3, (byte) 0xE9, (byte) 0xDF, (byte) 0xF1, 0x2D, 0x1B,
        (byte) 0xDF, 0x59, (byte) 0xDB, 0x53, (byte) 0xDB, (byte) 0xE9, (byte) 0xFA, 0x5B,
        0x7F, (byte) 0xF3, (byte) 0xB8, (byte) 0xFD, (byte) 0xFC, (byte) 0xDE, 0x1A, (byte) 0xFB,
        (byte) 0x8B, (byte) 0xED, (byte) 0xD7, (byte) 0xBE, 0x2F, 0x3E, (byte) 0xE7, 0x1E,
        (byte) 0xBB, (byte) 0xBF, (byte) 0xE9, 0x3B, (byte) 0xCD, (byte) 0xEE, (byte) 0xFE, 0x14,
        (byte) 0x82, 0x46, (byte) 0xC2, (byte) 0xBC, 0x5D, (byte) 0xBF, (byte) 0xF7, (byte) 0xE7,
        (byte) 0xEF, (byte) 0xDC, (byte) 0xF2, 0x4F, (byte) 0xD8, (byte) 0xDC, 0x7A, (byte) 0xDF,
        (byte) 0xFD, (byte) 0x8F, (byte) 0xFF, (byte) 0xDF, (byte) 0xDD, (byte) 0xFF, (byte) 0xF7, (byte) 0xA4,
        (byte) 0xBB, (byte) 0xEE, (byte) 0xDF, 0x5C, (byte) 0xB9, 0x5C, (byte) 0xE8, 0x1F,
        (byte) 0xC7, (byte) 0xFC, (byte) 0xFF, 0x1F, (byte) 0xF4, (byte) 0xFF, (byte) 0xFF, (byte) 0xDF,
        (byte) 0xE5, (byte) 0xF7, (byte) 0xFD, (byte) 0xCB, (byte) 0xB7, (byte) 0xFD, 0x79, (byte) 0xB3,
        (byte) 0xFA, 0x1F, (byte) 0xC7, 0x7B, (byte) 0xFE, 0x07, (byte) 0xFF, (byte) 0xF9,
        0x05, (byte) 0xB7, (byte) 0xB7, (byte) 0xFF, (byte) 0xC7, (byte) 0xFE, (byte) 0xFE, (byte) 0xFF,
        (byte) 0xE0, (byte) 0xB8, 0x37, 0x0B, (byte) 0xB0, (byte) 0xCD, 0x3F, 0x5B,
        0x7F, 0x2B, (byte) 0xD9, 0x3F, (byte) 0xEB, 0x43, (byte) 0x86, (byte) 0xCF,
        (byte) 0xDD, 0x6F, 0x7F, (byte) 0xD5, (byte) 0xBF, (byte) 0xAF, 0x2E, (byte) 0x9E,
        (byte) 0xBF, (byte) 0xFF, (byte) 0xFE, (byte) 0xEC, (byte) 0xD6, 0x7A, (byte) 0xDB, (byte) 0xF7,
        (byte) 0xC6, 0x7F, 0x17, (byte) 0xEF, (byte) 0xD5, (byte) 0xD7, 0x5E, (byte) 0xBA,
        0x6F, (byte) 0xFE, (byte) 0xBA, 0x7F, (byte) 0xFF, 0x47, (byte) 0xA9, 0x1E,
        (byte) 0xB1, (byte) 0xBF, (byte) 0xBB, 0x53, (byte) 0xE8, (byte) 0xAB, (byte) 0xFB, 0x57,
        0x62, (byte) 0xAB, (byte) 0xE8, (byte) 0xFF, 0x23, 0x72, 0x79, (byte) 0xBF,
        (byte) 0xEF, (byte) 0xBF, (byte) 0xEE, (byte) 0xF5, (byte) 0xFF, (byte) 0xC5, (byte) 0xFE, (byte) 0xBF,
        (byte) 0xDF, (byte) 0xE5, (byte) 0xAD, (byte) 0xFF, (byte) 0xAD, (byte) 0xFE, (byte) 0xE1, (byte) 0xFB,
        0x73, 0x7F, (byte) 0xFF, (byte) 0xFB, (byte) 0xFD, (byte) 0x9F, 0x6A, (byte) 0xEF,
        (byte) 0xFE, (byte) 0xEE, 0x76, (byte) 0xB6, (byte) 0xFD, (byte) 0x8F, 0x72, (byte) 0xEF
    };
}
