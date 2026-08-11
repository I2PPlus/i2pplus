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
    /** The group order (subgroup size) l. */
    private final BigInteger l;
    /** The little-endian encoding for field elements. */
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

    /**
     * Reduce the given scalar modulo the group order.
     *
     * @param s The scalar to reduce.
     * @return The reduced scalar.
     */
    @Override
    public byte[] reduce(byte[] s) {
        return enc.encode(enc.toBigInteger(s).mod(l));
    }

    /**
     * Multiply a by b, add c, and reduce modulo the group order.
     *
     * @param a The first scalar.
     * @param b The second scalar.
     * @param c The third scalar.
     * @return The result.
     */
    @Override
    public byte[] multiplyAndAdd(byte[] a, byte[] b, byte[] c) {
        return enc.encode(enc.toBigInteger(a).multiply(enc.toBigInteger(b)).add(enc.toBigInteger(c)).mod(l));
    }
}
