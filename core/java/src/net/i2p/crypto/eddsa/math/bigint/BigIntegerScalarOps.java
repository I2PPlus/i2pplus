/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated
 * <a href=https://creativecommons.org/publicdomain/zero/1.0/>CC0</a> with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 */
package net.i2p.crypto.eddsa.math.bigint;

import java.math.BigInteger;

import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.ScalarOps;

/**
 * BigInteger-based implementation of scalar operations for EdDSA cryptography.
 *
 * This class provides fundamental scalar arithmetic operations required for EdDSA
 * signature schemes, including modular reduction and scalar multiplication with addition.
 * All operations are performed modulo the subgroup order l, ensuring results remain
 * within the valid scalar range for EdDSA operations.
 */
public class BigIntegerScalarOps implements ScalarOps {
    private final BigInteger l;
    private final BigIntegerLittleEndianEncoding enc;

    public BigIntegerScalarOps(Field f, BigInteger l) {
        this.l = l;
        enc = new BigIntegerLittleEndianEncoding();
        enc.setField(f);
    }

    public byte[] reduce(byte[] s) {
        return enc.encode(enc.toBigInteger(s).mod(l));
    }

    public byte[] multiplyAndAdd(byte[] a, byte[] b, byte[] c) {
        return enc.encode(enc.toBigInteger(a).multiply(enc.toBigInteger(b)).add(enc.toBigInteger(c)).mod(l));
    }

}
