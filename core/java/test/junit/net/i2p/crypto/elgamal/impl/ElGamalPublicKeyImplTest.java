package net.i2p.crypto.elgamal.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.i2p.crypto.CryptoConstants;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;

import org.junit.Test;

import java.math.BigInteger;

public class ElGamalPublicKeyImplTest {

    private static final BigInteger Y = BigInteger.valueOf(42);
    private static final ElGamalParameterSpec PARAMS = CryptoConstants.I2P_ELGAMAL_2048_SPEC;

    @Test
    public void testConstructionFromKeySpec() {
        ElGamalPublicKeySpec keySpec = new ElGamalPublicKeySpec(Y, PARAMS);
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(keySpec);
        assertEquals(Y, key.getY());
        assertEquals(PARAMS.getP(), key.getParameters().getP());
        assertEquals(PARAMS.getG(), key.getParameters().getG());
    }

    @Test
    public void testConstructionFromBigIntegerAndSpec() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals(Y, key.getY());
        assertEquals(PARAMS.getP(), key.getParameters().getP());
        assertEquals(PARAMS.getG(), key.getParameters().getG());
    }

    @Test
    public void testGetY() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals(Y, key.getY());
    }

    @Test
    public void testGetAlgorithm() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals("ElGamal", key.getAlgorithm());
    }

    @Test
    public void testGetFormat() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals("X.509", key.getFormat());
    }

    @Test
    public void testGetEncodedNotNull() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertNotNull(key.getEncoded());
    }

    @Test
    public void testGetEncodedStartsWithSequence() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        byte[] encoded = key.getEncoded();
        assertTrue(encoded.length > 0);
        assertEquals(0x30, encoded[0] & 0xFF);
    }

    @Test
    public void testGetParams() {
        ElGamalPublicKeyImpl key = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals(PARAMS.getP(), key.getParams().getP());
        assertEquals(PARAMS.getG(), key.getParams().getG());
    }

    @Test
    public void testDifferentY() {
        ElGamalPublicKeyImpl key1 = new ElGamalPublicKeyImpl(Y, PARAMS);
        ElGamalPublicKeyImpl key2 = new ElGamalPublicKeyImpl(BigInteger.valueOf(99), PARAMS);
        assertNotEquals(key1.getY(), key2.getY());
    }

    @Test
    public void testSameYDifferentInstances() {
        ElGamalPublicKeyImpl key1 = new ElGamalPublicKeyImpl(Y, PARAMS);
        ElGamalPublicKeyImpl key2 = new ElGamalPublicKeyImpl(Y, PARAMS);
        assertEquals(key1.getY(), key2.getY());
    }
}
