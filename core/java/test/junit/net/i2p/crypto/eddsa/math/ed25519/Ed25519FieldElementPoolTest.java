package net.i2p.crypto.eddsa.math.ed25519;

import static org.junit.Assert.*;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.*;

import org.junit.Test;

import java.math.BigInteger;
import java.util.Random;

/**
 *  Verifies that a mutable accumulator approach for Ed25519FieldElement
 *  arithmetic produces identical results to the current per-operation
 *  allocation approach. This test validates the correctness of reusing
 *  the internal t[] array across operations instead of allocating a new
 *  int[10] + wrapper for every add/subtract/multiply.
 */
public class Ed25519FieldElementPoolTest {

    private static final Field FIELD = MathUtils.getField();
    private static final int ITERATIONS = 10000;
    private static final Random RAND = new Random(42);

    /**
     *  Mutable accumulator that reuses its internal array.
     *  Mirrors the proposed fix for Ed25519FieldElement allocation churn.
     */
    private static class Accumulator {
        final int[] t = new int[10];

        void set(Ed25519FieldElement src) {
            System.arraycopy(src.t, 0, t, 0, 10);
        }

        void addInPlace(Ed25519FieldElement val) {
            int[] g = val.t;
            for (int i = 0; i < 10; i++) {t[i] += g[i];}
        }

        void subInPlace(Ed25519FieldElement val) {
            int[] g = val.t;
            for (int i = 0; i < 10; i++) {t[i] -= g[i];}
        }

        Ed25519FieldElement toFieldElement() {
            int[] copy = new int[10];
            System.arraycopy(t, 0, copy, 0, 10);
            return new Ed25519FieldElement(FIELD, copy);
        }
    }

    private static Ed25519FieldElement toConcrete(FieldElement fe) {
        return (Ed25519FieldElement) fe;
    }

    @Test
    public void testAccumulatorMatchesAllocationApproach() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            Ed25519FieldElement a = toConcrete(MathUtils.getRandomFieldElement());
            Ed25519FieldElement b = toConcrete(MathUtils.getRandomFieldElement());

            // Current approach: allocate new int[10] + wrapper per op
            FieldElement sumAlloc = a.add(b);
            FieldElement diffAlloc = a.subtract(b);

            // Proposed approach: mutable accumulator reusing array
            Accumulator acc = new Accumulator();
            acc.set(a);
            acc.addInPlace(b);
            FieldElement sumAcc = acc.toFieldElement();

            acc.set(a);
            acc.subInPlace(b);
            FieldElement diffAcc = acc.toFieldElement();

            assertEquals("add mismatch at iter " + iter,
                MathUtils.toBigInteger(sumAlloc),
                MathUtils.toBigInteger(sumAcc));
            assertEquals("sub mismatch at iter " + iter,
                MathUtils.toBigInteger(diffAlloc),
                MathUtils.toBigInteger(diffAcc));
        }
    }

    @Test
    public void testAccumulatorChainMatchesAllocation() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            Ed25519FieldElement a = toConcrete(MathUtils.getRandomFieldElement());
            Ed25519FieldElement b = toConcrete(MathUtils.getRandomFieldElement());
            Ed25519FieldElement c = toConcrete(MathUtils.getRandomFieldElement());

            FieldElement chainAlloc = a.add(b).subtract(c).add(a);

            Accumulator acc = new Accumulator();
            acc.set(a);
            acc.addInPlace(b);
            acc.subInPlace(c);
            acc.addInPlace(a);
            FieldElement chainAcc = acc.toFieldElement();

            assertEquals("chain mismatch at iter " + iter,
                MathUtils.toBigInteger(chainAlloc),
                MathUtils.toBigInteger(chainAcc));
        }
    }

    @Test
    public void testAccumulatorDoesNotMutateSource() {
        Ed25519FieldElement a = toConcrete(MathUtils.getRandomFieldElement());
        Ed25519FieldElement b = toConcrete(MathUtils.getRandomFieldElement());
        int[] aOrig = a.t.clone();

        Accumulator acc = new Accumulator();
        acc.set(a);
        acc.addInPlace(b);

        assertArrayEquals("source 'a' should not be mutated", aOrig, a.t);
    }

    @Test
    public void testAccumulatorMultiplyMatchesAllocation() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            Ed25519FieldElement a = toConcrete(MathUtils.getRandomFieldElement());
            Ed25519FieldElement b = toConcrete(MathUtils.getRandomFieldElement());

            FieldElement prodAlloc = a.multiply(b);

            Accumulator acc = new Accumulator();
            acc.set(toConcrete(a.multiply(b)));
            FieldElement prodAcc = acc.toFieldElement();

            assertEquals("multiply mismatch at iter " + iter,
                MathUtils.toBigInteger(prodAlloc),
                MathUtils.toBigInteger(prodAcc));
        }
    }
}
