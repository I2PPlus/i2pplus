package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class NttTest {

    @Test
    public void testZeroNttInverseRoundtrip() {
        short[] zero = new short[MLKEMEngine.KyberN];
        short[] nttResult = Ntt.ntt(zero);
        short[] recovered = Ntt.invNtt(nttResult);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            short r = Reduce.barretReduce(recovered[i]);
            assertEquals("coefficient " + i + " should recover to 0", 0, r);
        }
    }

    @Test
    public void testNttZetasLength() {
        assertEquals(128, Ntt.nttZetas.length);
    }

    @Test
    public void testNttZetasInvLength() {
        assertEquals(128, Ntt.nttZetasInv.length);
    }

    @Test
    public void testNttZetasInRange() {
        for (int i = 0; i < Ntt.nttZetas.length; i++) {
            assertTrue("zeta " + i + " should be in valid range",
                       Ntt.nttZetas[i] > 0 && Ntt.nttZetas[i] < MLKEMEngine.KyberQ);
        }
    }

    @Test
    public void testNttZetasInvInRange() {
        for (int i = 0; i < Ntt.nttZetasInv.length; i++) {
            assertTrue("zetaInv " + i + " should be in valid range",
                       Ntt.nttZetasInv[i] > 0 && Ntt.nttZetasInv[i] < MLKEMEngine.KyberQ);
        }
    }

    @Test
    public void testFactorQMulMontZero() {
        assertEquals(0, Ntt.factorQMulMont((short) 0, (short) 100));
        assertEquals(0, Ntt.factorQMulMont((short) 100, (short) 0));
    }

    @Test
    public void testNttDoesNotModifyInput() {
        short[] original = new short[MLKEMEngine.KyberN];
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            original[i] = (short) (i % MLKEMEngine.KyberQ);
        }
        short[] copy = original.clone();
        Ntt.ntt(copy);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals("input should be unmodified at " + i, original[i], copy[i]);
        }
    }

    @Test
    public void testInvNttDoesNotModifyInput() {
        short[] original = new short[MLKEMEngine.KyberN];
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            original[i] = (short) (i % MLKEMEngine.KyberQ);
        }
        short[] copy = original.clone();
        Ntt.invNtt(copy);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals("input should be unmodified at " + i, original[i], copy[i]);
        }
    }

    @Test
    public void testNttZeroProducesZero() {
        short[] zero = new short[MLKEMEngine.KyberN];
        short[] result = Ntt.ntt(zero);
        for (int i = 0; i < MLKEMEngine.KyberN; i++) {
            assertEquals(0, result[i]);
        }
    }

    @Test
    public void testNttOutputLength() {
        short[] input = new short[MLKEMEngine.KyberN];
        short[] result = Ntt.ntt(input);
        assertEquals(MLKEMEngine.KyberN, result.length);
    }

    @Test
    public void testInvNttOutputLength() {
        short[] input = new short[MLKEMEngine.KyberN];
        short[] result = Ntt.invNtt(input);
        assertEquals(MLKEMEngine.KyberN, result.length);
    }
}
