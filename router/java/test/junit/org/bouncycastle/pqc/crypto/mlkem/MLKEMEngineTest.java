package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class MLKEMEngineTest {

    @Test
    public void testKyberNConstant() {
        assertEquals(256, MLKEMEngine.KyberN);
    }

    @Test
    public void testKyberQConstant() {
        assertEquals(3329, MLKEMEngine.KyberQ);
    }

    @Test
    public void testKyberPolyBytes() {
        assertEquals(384, MLKEMEngine.KyberPolyBytes);
    }

    @Test
    public void testKyberSymBytes() {
        assertEquals(32, MLKEMEngine.KyberSymBytes);
    }

    @Test
    public void testEngineK2Sizes() {
        MLKEMEngine e = new MLKEMEngine(2);
        assertEquals(2, e.getKyberK());
        assertEquals(3, e.getKyberEta1());
        assertEquals(128, e.getKyberPolyCompressedBytes());
        assertEquals(2 * 320, e.getKyberPolyVecCompressedBytes());
        assertEquals(2 * 384, e.getKyberPolyVecBytes());
    }

    @Test
    public void testEngineK3Sizes() {
        MLKEMEngine e = new MLKEMEngine(3);
        assertEquals(3, e.getKyberK());
        assertEquals(2, e.getKyberEta1());
        assertEquals(128, e.getKyberPolyCompressedBytes());
        assertEquals(3 * 320, e.getKyberPolyVecCompressedBytes());
        assertEquals(3 * 384, e.getKyberPolyVecBytes());
    }

    @Test
    public void testEngineK4Sizes() {
        MLKEMEngine e = new MLKEMEngine(4);
        assertEquals(4, e.getKyberK());
        assertEquals(2, e.getKyberEta1());
        assertEquals(160, e.getKyberPolyCompressedBytes());
        assertEquals(4 * 352, e.getKyberPolyVecCompressedBytes());
        assertEquals(4 * 384, e.getKyberPolyVecBytes());
    }

    @Test
    public void testEngineInvalidK() {
        try {
            new MLKEMEngine(1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void testEngineK5Invalid() {
        try {
            new MLKEMEngine(5);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void testIndCpaMsgBytes() {
        assertEquals(32, MLKEMEngine.getKyberIndCpaMsgBytes());
    }

    @Test
    public void testKyberEta2() {
        assertEquals(2, MLKEMEngine.getKyberEta2());
    }

    @Test
    public void testEngineCryptoBytes() {
        MLKEMEngine e = new MLKEMEngine(2);
        assertEquals(32, e.getCryptoBytes());
    }

    @Test
    public void testEnginePublicKeyBytesPositive() {
        MLKEMEngine e2 = new MLKEMEngine(2);
        MLKEMEngine e3 = new MLKEMEngine(3);
        MLKEMEngine e4 = new MLKEMEngine(4);
        assertTrue(e2.getCryptoPublicKeyBytes() > 0);
        assertTrue(e3.getCryptoPublicKeyBytes() > 0);
        assertTrue(e4.getCryptoPublicKeyBytes() > 0);
        assertTrue(e4.getCryptoPublicKeyBytes() > e3.getCryptoPublicKeyBytes());
        assertTrue(e3.getCryptoPublicKeyBytes() > e2.getCryptoPublicKeyBytes());
    }

    @Test
    public void testEngineSecretKeyBytesPositive() {
        MLKEMEngine e2 = new MLKEMEngine(2);
        MLKEMEngine e3 = new MLKEMEngine(3);
        MLKEMEngine e4 = new MLKEMEngine(4);
        assertTrue(e2.getCryptoSecretKeyBytes() > 0);
        assertTrue(e3.getCryptoSecretKeyBytes() > 0);
        assertTrue(e4.getCryptoSecretKeyBytes() > 0);
        assertTrue(e4.getCryptoSecretKeyBytes() > e3.getCryptoSecretKeyBytes());
    }

    @Test
    public void testEngineCipherTextBytesPositive() {
        MLKEMEngine e2 = new MLKEMEngine(2);
        MLKEMEngine e3 = new MLKEMEngine(3);
        MLKEMEngine e4 = new MLKEMEngine(4);
        assertTrue(e2.getCryptoCipherTextBytes() > 0);
        assertTrue(e3.getCryptoCipherTextBytes() > 0);
        assertTrue(e4.getCryptoCipherTextBytes() > 0);
    }
}
