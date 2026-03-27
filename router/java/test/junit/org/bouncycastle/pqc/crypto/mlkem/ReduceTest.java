package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReduceTest {

    private static final int Q = MLKEMEngine.KyberQ;

    @Test
    public void testBarretReduceZero() {
        assertEquals(0, Reduce.barretReduce((short) 0));
    }

    @Test
    public void testBarretReduceWithinRange() {
        for (short i = 0; i < Q; i++) {
            short r = Reduce.barretReduce(i);
            assertTrue("result should be in [0, Q) or negative close to 0: " + r,
                       r >= 0 && r < Q || r > -Q && r < 0);
        }
    }

    @Test
    public void testBarretReduceAtQ() {
        short r = Reduce.barretReduce((short) Q);
        int ir = r;
        assertTrue("barretReduce(Q) should reduce to a value close to 0: " + r,
                   ir >= -Q && ir < Q);
    }

    @Test
    public void testBarretReduceNegative() {
        short r = Reduce.barretReduce((short) -1);
        assertTrue("negative input should return a value in [-Q, Q)", r > -Q && r < Q);
    }

    @Test
    public void testMontgomeryReduceZero() {
        assertEquals(0, Reduce.montgomeryReduce(0));
    }

    @Test
    public void testMontgomeryReduceSmall() {
        int r = Reduce.montgomeryReduce(1);
        assertTrue("montgomeryReduce(1) should be a small short", r >= Short.MIN_VALUE && r <= Short.MAX_VALUE);
    }

    @Test
    public void testMontgomeryReduceIsShort() {
        for (int i = 0; i < 100; i++) {
            short r = Reduce.montgomeryReduce(i * 137);
            assertTrue("result must fit in short range", r >= Short.MIN_VALUE && r <= Short.MAX_VALUE);
        }
    }

    @Test
    public void testConditionalSubQZero() {
        assertEquals(0, Reduce.conditionalSubQ((short) 0));
    }

    @Test
    public void testConditionalSubQBelowQ() {
        for (short i = 0; i < Q; i++) {
            assertEquals(i, Reduce.conditionalSubQ(i));
        }
    }

    @Test
    public void testConditionalSubQAtQ() {
        assertEquals(0, Reduce.conditionalSubQ((short) Q));
    }

    @Test
    public void testConditionalSubQAboveQ() {
        short r = Reduce.conditionalSubQ((short) (Q + 1));
        assertEquals(1, r);
    }

    @Test
    public void testConditionalSubQNearMax() {
        // conditionalSubQ only subtracts Q once, so it works for [0, 2*Q)
        short r = Reduce.conditionalSubQ((short) (2 * Q - 1));
        assertEquals(Q - 1, r);
    }
}
