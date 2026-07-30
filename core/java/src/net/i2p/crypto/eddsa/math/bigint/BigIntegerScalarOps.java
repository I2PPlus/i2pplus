package net.i2p.crypto.eddsa.math.bigint;

import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.ScalarOps;

import java.math.BigInteger;

/**
 * BigInteger-based implementation of scalar operations for EdDSA cryptography.
 *
 * This class provides fundamental scalar arithmetic operations required for EdDSA
 * signature schemes, including modular reduction and scalar multiplication with addition.
 * All operations are performed modulo the subgroup order l, ensuring results remain
 * within the valid scalar range for EdDSA operations.
 */
public class BigIntegerScalarOps implements ScalarOps {
    /** L */
    private final BigInteger l;
    /** Enc */
    private final BigIntegerLittleEndianEncoding enc;

    /**
     * Create a BigIntegerScalarOps.
     *
     * @param f the finite field
     * @param l the group order (subgroup size)
     */
    public BigIntegerScalarOps(Field f, BigInteger l) {
        this.l = l;
        enc = new BigIntegerLittleEndianEncoding();
        enc.setField(f);
    }

    /** @param s the scalar @return the reduced scalar */
    @Override
    public byte[] reduce(byte[] s) {
        return enc.encode(enc.toBigInteger(s).mod(l));
    }

    /** @param a the first scalar @param b the second scalar @param c the third scalar @return the result */
    @Override
    public byte[] multiplyAndAdd(byte[] a, byte[] b, byte[] c) {
        return enc.encode(enc.toBigInteger(a).multiply(enc.toBigInteger(b)).add(enc.toBigInteger(c)).mod(l));
    }
}
