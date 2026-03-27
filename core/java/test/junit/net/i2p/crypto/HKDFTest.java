package net.i2p.crypto;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;

public class HKDFTest {

    private static HKDF hkdf;

    @BeforeClass
    public static void setup() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        hkdf = new HKDF(ctx);
    }

    @Test
    public void testSingleOutputDeterministic() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0b);
        byte[] data = "test data".getBytes();
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];

        hkdf.calculate(key, data, out1);
        hkdf.calculate(key, data, out2);
        assertArrayEquals(out1, out2);
    }

    @Test
    public void testSingleOutputSize() {
        byte[] key = new byte[32];
        byte[] data = new byte[16];
        byte[] out = new byte[32];
        hkdf.calculate(key, data, out);
        assertEquals(32, out.length);
    }

    @Test
    public void testSingleOutputWithInfo() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0c);
        byte[] data = "input".getBytes();
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];

        hkdf.calculate(key, data, "info1", out1);
        hkdf.calculate(key, data, "info2", out2);
        assertFalse(Arrays.equals(out1, out2));
    }

    @Test
    public void testSingleOutputWithEmptyInfo() {
        byte[] key = new byte[32];
        byte[] data = new byte[16];
        byte[] out = new byte[32];
        hkdf.calculate(key, data, "", out);
        assertEquals(32, out.length);
    }

    @Test
    public void testTwoOutputsNoInfo() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0b);
        byte[] data = "test data".getBytes();
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];

        hkdf.calculate(key, data, out1, out2, 0);
        assertEquals(32, out1.length);
        assertEquals(32, out2.length);
        assertFalse(Arrays.equals(out1, out2));
    }

    @Test
    public void testTwoOutputsWithInfo() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x0c);
        byte[] data = "input".getBytes();
        byte[] out1a = new byte[32];
        byte[] out2a = new byte[32];
        byte[] out1b = new byte[32];
        byte[] out2b = new byte[32];

        hkdf.calculate(key, data, "info", out1a, out2a, 0);
        hkdf.calculate(key, data, "different", out1b, out2b, 0);
        assertFalse(Arrays.equals(out1a, out1b));
        assertFalse(Arrays.equals(out2a, out2b));
    }

    @Test
    public void testTwoOutputsWithOffset() {
        byte[] key = new byte[32];
        byte[] data = new byte[16];
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[64];

        hkdf.calculate(key, data, "", out1, out2, 32);
        assertEquals(64, out2.length);
    }

    @Test
    public void testDifferentKeysProduceDifferentOutput() {
        byte[] key1 = new byte[32];
        byte[] key2 = new byte[32];
        key2[0] = 1;
        byte[] data = new byte[16];
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];

        hkdf.calculate(key1, data, out1);
        hkdf.calculate(key2, data, out2);
        assertFalse(Arrays.equals(out1, out2));
    }

    @Test
    public void testDifferentDataProduceDifferentOutput() {
        byte[] key = new byte[32];
        byte[] data1 = new byte[16];
        byte[] data2 = new byte[16];
        data2[0] = 1;
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];

        hkdf.calculate(key, data1, out1);
        hkdf.calculate(key, data2, out2);
        assertFalse(Arrays.equals(out1, out2));
    }

    @Test
    public void testEmptyData() {
        byte[] key = new byte[32];
        byte[] data = new byte[0];
        byte[] out = new byte[32];
        hkdf.calculate(key, data, out);
        assertEquals(32, out.length);
    }
}
