package net.i2p.router.sybil;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigInteger;

/**
 *  Tests for Util.biLog2, the log-2 metric used by the Sybil analyzer
 *  and netdb renderer to measure DHT distance. Values are exact for
 *  the fractional bits of the binary representation.
 *
 *  @since 0.9.38
 */
public class UtilTest {

    @Test
    public void testPowerOfTwo() {
        // log2(2^n) = n + 0.5
        assertEquals(0.5, Util.biLog2(BigInteger.ONE), 0.000001);
        assertEquals(1.5, Util.biLog2(BigInteger.valueOf(2)), 0.000001);
        assertEquals(2.5, Util.biLog2(BigInteger.valueOf(4)), 0.000001);
        assertEquals(3.5, Util.biLog2(BigInteger.valueOf(8)), 0.000001);
        assertEquals(9.5, Util.biLog2(BigInteger.valueOf(512)), 0.000001);
    }

    @Test
    public void testFractionalValues() {
        // 3 = 0b11 -> 1 + 0.5 + 0.25 = 1.75
        assertEquals(1.75, Util.biLog2(BigInteger.valueOf(3)), 0.000001);
        // 5 = 0b101 -> 2 + 0.5 + 0.125 = 2.625
        assertEquals(2.625, Util.biLog2(BigInteger.valueOf(5)), 0.000001);
        // 255 = 0b11111111 -> 7 + sum(0.5..0.00390625) = 7.99609375
        assertEquals(7.99609375, Util.biLog2(BigInteger.valueOf(255)), 0.000001);
    }

    @Test
    public void testMonotonicIncreasing() {
        BigInteger prev = BigInteger.valueOf(1);
        double prevLog = Util.biLog2(prev);
        for (int i = 2; i <= 10000; i++) {
            BigInteger cur = BigInteger.valueOf(i);
            double curLog = Util.biLog2(cur);
            assertTrue("log2(" + i + ") should exceed log2(" + (i - 1) + ")",
                       curLog > prevLog);
            prevLog = curLog;
        }
    }

    @Test
    public void testLargeNumber() {
        BigInteger big = BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE);
        double d = Util.biLog2(big);
        // 2^256 - 1 -> 255 + (1 - 2^-256), rounds to exactly 256.0 in double
        assertTrue(d >= 255.0);
        assertTrue(d <= 256.0);
        assertTrue(d > 255.5);
    }

    @Test
    public void testDistanceBetweenPowerAndHalf() {
        // log2(2^32 - 2^30) = 31 + 0.5 + 0.25 = 31.75
        BigInteger near = BigInteger.valueOf(2).pow(32).subtract(BigInteger.valueOf(2).pow(30));
        double d = Util.biLog2(near);
        assertTrue(d > 31.5);
        assertTrue(d < 32.5);
    }
}
