package net.i2p.crypto.elgamal.spec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.i2p.crypto.CryptoConstants;

import org.junit.Test;

import java.math.BigInteger;

public class ElGamalParameterSpecTest {

    private static final BigInteger P = BigInteger.valueOf(23);
    private static final BigInteger G = BigInteger.valueOf(5);

    @Test
    public void testGetP() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertEquals(P, spec.getP());
    }

    @Test
    public void testGetG() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertEquals(G, spec.getG());
    }

    @Test
    public void testConstructorStoresValues() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertEquals(P, spec.getP());
        assertEquals(G, spec.getG());
    }

    @Test
    public void testEqualsSameValues() {
        ElGamalParameterSpec spec1 = new ElGamalParameterSpec(P, G);
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(P, G);
        assertTrue(spec1.equals(spec2));
        assertTrue(spec2.equals(spec1));
    }

    @Test
    public void testEqualsSameReference() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertTrue(spec.equals(spec));
    }

    @Test
    public void testEqualsNull() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertFalse(spec.equals(null));
    }

    @Test
    public void testEqualsDifferentType() {
        ElGamalParameterSpec spec = new ElGamalParameterSpec(P, G);
        assertFalse(spec.equals("not a spec"));
    }

    @Test
    public void testNotEqualsDifferentP() {
        ElGamalParameterSpec spec1 = new ElGamalParameterSpec(P, G);
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(BigInteger.valueOf(29), G);
        assertFalse(spec1.equals(spec2));
    }

    @Test
    public void testNotEqualsDifferentG() {
        ElGamalParameterSpec spec1 = new ElGamalParameterSpec(P, G);
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(P, BigInteger.valueOf(7));
        assertFalse(spec1.equals(spec2));
    }

    @Test
    public void testHashCodeEqual() {
        ElGamalParameterSpec spec1 = new ElGamalParameterSpec(P, G);
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(P, G);
        assertEquals(spec1.hashCode(), spec2.hashCode());
    }

    @Test
    public void testHashCodeDifferent() {
        ElGamalParameterSpec spec1 = new ElGamalParameterSpec(P, G);
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(BigInteger.valueOf(29), G);
        assertNotEquals(spec1.hashCode(), spec2.hashCode());
    }

    @Test
    public void testWithI2PElgamal2048Spec() {
        ElGamalParameterSpec spec = CryptoConstants.I2P_ELGAMAL_2048_SPEC;
        assertEquals(CryptoConstants.elgp, spec.getP());
        assertEquals(CryptoConstants.elgg, spec.getG());
    }

    @Test
    public void testEqualsI2PElgamal2048Spec() {
        ElGamalParameterSpec spec1 = CryptoConstants.I2P_ELGAMAL_2048_SPEC;
        ElGamalParameterSpec spec2 = new ElGamalParameterSpec(CryptoConstants.elgp, CryptoConstants.elgg);
        assertTrue(spec1.equals(spec2));
        assertEquals(spec1.hashCode(), spec2.hashCode());
    }
}
