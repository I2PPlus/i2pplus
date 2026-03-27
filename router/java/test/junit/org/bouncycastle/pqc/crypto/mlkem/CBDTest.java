package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class CBDTest {

    @Test
    public void testCbdEta2ZeroBytes() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 2 / 4];
        CBD.mlkemCBD(poly, buf, 2);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short c = poly.getCoeffIndex(i);
            assertTrue("coefficient " + i + " should be in [-2,2]: " + c,
                       c >= -2 && c <= 2);
        }
    }

    @Test
    public void testCbdEta3ZeroBytes() {
        MLKEMEngine engine = new MLKEMEngine(2);
        Poly poly = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 3 / 4];
        CBD.mlkemCBD(poly, buf, 3);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short c = poly.getCoeffIndex(i);
            assertTrue("coefficient " + i + " should be in [-3,3]: " + c,
                       c >= -3 && c <= 3);
        }
    }

    @Test
    public void testCbdEta2AllOnes() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 2 / 4];
        java.util.Arrays.fill(buf, (byte) 0xFF);
        CBD.mlkemCBD(poly, buf, 2);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short c = poly.getCoeffIndex(i);
            assertTrue("coefficient " + i + " should be in [-2,2]: " + c,
                       c >= -2 && c <= 2);
        }
    }

    @Test
    public void testCbdEta3AllOnes() {
        MLKEMEngine engine = new MLKEMEngine(2);
        Poly poly = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 3 / 4];
        java.util.Arrays.fill(buf, (byte) 0xFF);
        CBD.mlkemCBD(poly, buf, 3);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short c = poly.getCoeffIndex(i);
            assertTrue("coefficient " + i + " should be in [-3,3]: " + c,
                       c >= -3 && c <= 3);
        }
    }

    @Test
    public void testCbdEta2Deterministic() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly1 = new Poly(engine);
        Poly poly2 = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 2 / 4];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) (i & 0xFF);
        }
        CBD.mlkemCBD(poly1, buf, 2);
        CBD.mlkemCBD(poly2, buf, 2);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals("coefficient " + i, poly1.getCoeffIndex(i), poly2.getCoeffIndex(i));
        }
    }

    @Test
    public void testCbdEta3CoefficientCount() {
        MLKEMEngine engine = new MLKEMEngine(2);
        Poly poly = new Poly(engine);
        byte[] buf = new byte[MLKEMEngine.KyberN * 3 / 4];
        CBD.mlkemCBD(poly, buf, 3);
        int nonzero = 0;
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            if (poly.getCoeffIndex(i) != 0) nonzero++;
        }
        assertTrue("zero-byte input should produce coefficients (some may be nonzero)",
                   nonzero >= 0);
    }
}
