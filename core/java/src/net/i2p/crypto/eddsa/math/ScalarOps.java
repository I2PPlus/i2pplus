package net.i2p.crypto.eddsa.math;

import java.io.Serializable;

/**
 * Interface for scalar operations in EdDSA.
 *
 * @since 0.9.15
 */
public interface ScalarOps extends Serializable {
    /**
     * Reduce the given scalar mod $l$.
     * <p>
     * From the Ed25519 paper:<br>
     * Here we interpret $2b$-bit strings in little-endian form as integers in
     * $\{0, 1,..., 2^{(2b)}-1\}$.
     *
     * @param s the scalar to reduce
     * @return $s \bmod l$
     */
    public byte[] reduce(byte[] s);

    /**
     * $r = (a * b + c) \bmod l$
     *
     * @param a a scalar
     * @param b a scalar
     * @param c a scalar
     * @return $(a*b + c) \bmod l$
     */
    public byte[] multiplyAndAdd(byte[] a, byte[] b, byte[] c);
}
