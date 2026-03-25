package net.i2p.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

public class SigTypeTest {

    @Test
    public void testEdDSA512Ed25519() {
        SigType st = SigType.EdDSA_SHA512_Ed25519;
        assertEquals(7, st.getCode());
        assertEquals(32, st.getPubkeyLen());
        assertEquals(32, st.getPrivkeyLen());
        assertEquals(64, st.getSigLen());
    }

    @Test
    public void testECDSA256() {
        SigType st = SigType.ECDSA_SHA256_P256;
        assertEquals(1, st.getCode());
        assertEquals(64, st.getPubkeyLen());
        assertEquals(32, st.getPrivkeyLen());
        assertEquals(64, st.getSigLen());
    }

    @Test
    public void testDSA() {
        SigType st = SigType.DSA_SHA1;
        assertEquals(0, st.getCode());
        assertEquals(128, st.getPubkeyLen());
        assertEquals(20, st.getPrivkeyLen());
        assertEquals(40, st.getSigLen());
    }

    @Test
    public void testGetByCode() {
        assertEquals(SigType.EdDSA_SHA512_Ed25519, SigType.getByCode(7));
        assertEquals(SigType.DSA_SHA1, SigType.getByCode(0));
    }

    @Test
    public void testGetByCodeInvalid() {
        assertNull(SigType.getByCode(999));
        assertNull(SigType.getByCode(-1));
    }

    @Test
    public void testCodeUniqueness() {
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (SigType st : SigType.values()) {
            assertTrue("Duplicate code: " + st.getCode(), codes.add(st.getCode()));
        }
    }

    @Test
    public void testParseSigType() {
        assertEquals(SigType.EdDSA_SHA512_Ed25519, SigType.parseSigType("EdDSA_SHA512_Ed25519"));
    }

    @Test
    public void testParseSigTypeInvalid() {
        assertNull(SigType.parseSigType("NONEXISTENT"));
    }

    @Test
    public void testBaseAlgorithm() {
        assertEquals(SigAlgo.EdDSA, SigType.EdDSA_SHA512_Ed25519.getBaseAlgorithm());
        assertEquals(SigAlgo.DSA, SigType.DSA_SHA1.getBaseAlgorithm());
        assertEquals(SigAlgo.EC, SigType.ECDSA_SHA256_P256.getBaseAlgorithm());
        assertEquals(SigAlgo.RSA, SigType.RSA_SHA256_2048.getBaseAlgorithm());
    }

    @Test
    public void testAlgorithmName() {
        assertNotNull(SigType.EdDSA_SHA512_Ed25519.getAlgorithmName());
        assertFalse(SigType.EdDSA_SHA512_Ed25519.getAlgorithmName().isEmpty());
    }

    @Test
    public void testGetOID() {
        assertNotNull(SigType.EdDSA_SHA512_Ed25519.getOID());
    }

    @Test
    public void testSupportedSince() {
        assertNotNull(SigType.EdDSA_SHA512_Ed25519.getSupportedSince());
    }

    @Test
    public void testRedDSA() {
        SigType st = SigType.RedDSA_SHA512_Ed25519;
        assertEquals(11, st.getCode());
        assertEquals(32, st.getPubkeyLen());
        assertEquals(64, st.getSigLen());
    }
}
