package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class PolyTest {

    @Test
    public void testPolyInitZero() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals(0, poly.getCoeffIndex(i));
        }
    }

    @Test
    public void testPolySetAndGet() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        poly.setCoeffIndex(0, (short) 100);
        poly.setCoeffIndex(127, (short) -50);
        poly.setCoeffIndex(255, (short) 3328);
        assertEquals(100, poly.getCoeffIndex(0));
        assertEquals(-50, poly.getCoeffIndex(127));
        assertEquals(3328, poly.getCoeffIndex(255));
    }

    @Test
    public void testPolyNttZeroRoundtrip() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        poly.polyNtt();
        poly.polyInverseNttToMont();
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short r = Reduce.barretReduce(poly.getCoeffIndex(i));
            assertEquals("coefficient " + i, 0, r);
        }
    }

    @Test
    public void testPolyToBytesFromBytesRoundtrip() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            poly.setCoeffIndex(i, (short) (i % MLKEMEngine.KyberQ));
        }
        byte[] bytes = poly.toBytes();
        assertEquals(MLKEMEngine.KyberPolyBytes, bytes.length);
        Poly poly2 = new Poly(engine);
        poly2.fromBytes(bytes);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short r = Reduce.barretReduce(poly2.getCoeffIndex(i));
            short expected = Reduce.barretReduce(poly.getCoeffIndex(i));
            assertEquals("coefficient " + i, expected, r);
        }
    }

    @Test
    public void testPolyAddCoeffs() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly a = new Poly(engine);
        Poly b = new Poly(engine);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            a.setCoeffIndex(i, (short) 1);
            b.setCoeffIndex(i, (short) 2);
        }
        a.addCoeffs(b);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short r = Reduce.barretReduce(a.getCoeffIndex(i));
            assertEquals(3, r);
        }
    }

    @Test
    public void testPolySubtract() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly a = new Poly(engine);
        Poly b = new Poly(engine);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            a.setCoeffIndex(i, (short) 1);
            b.setCoeffIndex(i, (short) 10);
        }
        a.polySubtract(b);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short r = Reduce.barretReduce(a.getCoeffIndex(i));
            assertEquals(9, r);
        }
    }

    @Test
    public void testPolyReduce() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        poly.setCoeffIndex(0, (short) (MLKEMEngine.KyberQ + 5));
        poly.setCoeffIndex(1, (short) (MLKEMEngine.KyberQ * 2 + 13));
        poly.reduce();
        assertTrue(poly.getCoeffIndex(0) < MLKEMEngine.KyberQ);
        assertTrue(poly.getCoeffIndex(1) < MLKEMEngine.KyberQ);
    }

    @Test
    public void testPolyCoeffsLength() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        assertEquals(MLKEMEngine.KyberN, poly.getCoeffs().length);
    }

    @Test
    public void testPolySetCoeffs() {
        MLKEMEngine engine = new MLKEMEngine(3);
        Poly poly = new Poly(engine);
        short[] data = new short[MLKEMEngine.KyberN];
        for (int i = 0; i < data.length; i++) {
            data[i] = (short) (i * 13);
        }
        poly.setCoeffs(data);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals(data[i], poly.getCoeffIndex(i));
        }
    }
}
