package net.i2p.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigInteger;
import java.security.InvalidKeyException;

public class SigUtilTest {

    @Test
    public void testRectifyExactLength() throws InvalidKeyException {
        byte[] data = {1, 2, 3, 4, 5};
        BigInteger bi = new BigInteger(1, data);
        byte[] result = SigUtil.rectify(bi, 5);
        assertArrayEquals(data, result);
    }

    @Test
    public void testRectifyPadShort() throws InvalidKeyException {
        byte[] data = {1, 2, 3};
        BigInteger bi = new BigInteger(1, data);
        byte[] result = SigUtil.rectify(bi, 5);
        assertEquals(5, result.length);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
        assertEquals(1, result[2]);
        assertEquals(2, result[3]);
        assertEquals(3, result[4]);
    }

    @Test
    public void testRectifyTruncateLeadingZero() throws InvalidKeyException {
        byte[] data = {0, 1, 2, 3};
        BigInteger bi = new BigInteger(1, data);
        byte[] result = SigUtil.rectify(bi, 3);
        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals(2, result[1]);
        assertEquals(3, result[2]);
    }

    @Test
    public void testRectifyZero() throws InvalidKeyException {
        BigInteger bi = BigInteger.ZERO;
        byte[] result = SigUtil.rectify(bi, 4);
        assertEquals(4, result.length);
        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
        assertEquals(0, result[2]);
        assertEquals(0, result[3]);
    }

    @Test(expected = InvalidKeyException.class)
    public void testRectifyNegativeThrows() throws Exception {
        BigInteger bi = BigInteger.valueOf(-1);
        SigUtil.rectify(bi, 4);
    }

    @Test(expected = InvalidKeyException.class)
    public void testRectifyTooBigThrows() throws Exception {
        byte[] data = {1, 2, 3, 4, 5, 6};
        BigInteger bi = new BigInteger(1, data);
        SigUtil.rectify(bi, 3);
    }

    @Test
    public void testCombineAndSplitRoundTrip() throws Exception {
        BigInteger x = new BigInteger("123456789");
        BigInteger y = new BigInteger("987654321");
        byte[] combined = SigUtil.combine(x, y, 8);
        assertEquals(8, combined.length);

        int half = combined.length / 2;
        BigInteger xBack = new BigInteger(1, java.util.Arrays.copyOfRange(combined, 0, half));
        BigInteger yBack = new BigInteger(1, java.util.Arrays.copyOfRange(combined, half, combined.length));
        assertEquals(x, xBack);
        assertEquals(y, yBack);
    }

    @Test(expected = InvalidKeyException.class)
    public void testCombineOddLengthThrows() throws Exception {
        SigUtil.combine(BigInteger.ONE, BigInteger.ONE, 5);
    }

    @Test
    public void testSigBytesToASN1SmallValues() {
        BigInteger r = BigInteger.ZERO;
        BigInteger s = BigInteger.ONE;
        byte[] asn1 = SigUtil.sigBytesToASN1(r, s);
        assertNotNull(asn1);
        assertTrue(asn1.length > 4);
        assertEquals(0x30, asn1[0] & 0xff);
    }

    @Test
    public void testIntToASN1SingleByte() {
        byte[] buf = new byte[4];
        int idx = SigUtil.intToASN1(buf, 0, 42);
        assertEquals(1, idx);
        assertEquals(42, buf[0] & 0xff);
    }

    @Test
    public void testIntToASN1TwoBytes() {
        byte[] buf = new byte[4];
        int idx = SigUtil.intToASN1(buf, 0, 200);
        assertEquals(2, idx);
        assertEquals(0x81, buf[0] & 0xff);
        assertEquals(200, buf[1] & 0xff);
    }

    @Test
    public void testIntToASN1ThreeBytes() {
        byte[] buf = new byte[4];
        int idx = SigUtil.intToASN1(buf, 0, 1000);
        assertEquals(3, idx);
        assertEquals(0x82, buf[0] & 0xff);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIntToASN1NegativeThrows() {
        byte[] buf = new byte[4];
        SigUtil.intToASN1(buf, 0, -1);
    }

    @Test
    public void testCombineSmallValues() throws InvalidKeyException {
        BigInteger x = BigInteger.valueOf(255);
        BigInteger y = BigInteger.valueOf(128);
        byte[] combined = SigUtil.combine(x, y, 4);
        assertEquals(4, combined.length);
    }
}
