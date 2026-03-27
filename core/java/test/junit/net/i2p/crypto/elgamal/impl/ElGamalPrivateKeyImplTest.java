package net.i2p.crypto.elgamal.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.i2p.crypto.CryptoConstants;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPrivateKeySpec;

import org.junit.Test;

import java.math.BigInteger;

public class ElGamalPrivateKeyImplTest {

    private static final BigInteger X = BigInteger.valueOf(12345);
    private static final ElGamalParameterSpec PARAMS = CryptoConstants.I2P_ELGAMAL_2048_SPEC;

    @Test
    public void testConstructionFromKeySpec() {
        ElGamalPrivateKeySpec keySpec = new ElGamalPrivateKeySpec(X, PARAMS);
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(keySpec);
        assertEquals(X, key.getX());
        assertEquals(PARAMS.getP(), key.getParameters().getP());
        assertEquals(PARAMS.getG(), key.getParameters().getG());
    }

    @Test
    public void testConstructionFromBigIntegerAndSpec() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertEquals(X, key.getX());
        assertEquals(PARAMS.getP(), key.getParameters().getP());
        assertEquals(PARAMS.getG(), key.getParameters().getG());
    }

    @Test
    public void testGetX() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertEquals(X, key.getX());
    }

    @Test
    public void testGetAlgorithm() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertEquals("ElGamal", key.getAlgorithm());
    }

    @Test
    public void testGetFormat() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertEquals("PKCS#8", key.getFormat());
    }

    @Test
    public void testGetEncodedNotNull() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertNotNull(key.getEncoded());
    }

    @Test
    public void testGetEncodedStartsWithSequence() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        byte[] encoded = key.getEncoded();
        assertTrue(encoded.length > 0);
        assertEquals(0x30, encoded[0] & 0xFF);
    }

    @Test
    public void testGetParams() {
        ElGamalPrivateKeyImpl key = new ElGamalPrivateKeyImpl(X, PARAMS);
        assertEquals(PARAMS.getP(), key.getParams().getP());
        assertEquals(PARAMS.getG(), key.getParams().getG());
    }
}
