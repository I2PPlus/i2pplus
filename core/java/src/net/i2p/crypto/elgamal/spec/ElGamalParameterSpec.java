package net.i2p.crypto.elgamal.spec;

import java.math.BigInteger;
import java.security.spec.AlgorithmParameterSpec;

/**
 *  Copied from org.bouncycastle.jce.spec
 *  This can't actually be passed to the BC provider, we would have to
 *  use reflection to create a "real" org.bouncycasle.jce.spec.ElGamalParameterSpec.
 *
 *  @since 0.9.18, moved from net.i2p.crypto in 0.9.25
 */
public class ElGamalParameterSpec implements AlgorithmParameterSpec {
    private final BigInteger p;
    private final BigInteger g;

    /**
     * Constructs a parameter set for Diffie-Hellman, using a prime modulus
     * <code>p</code> and a base generator <code>g</code>.
     *
     * @param p the prime modulus
     * @param g the base generator
     */
    public ElGamalParameterSpec(BigInteger p, BigInteger g) {
        this.p = p;
        this.g = g;
    }

    /**
     * Returns the prime modulus <code>p</code>.
     *
     * @return the prime modulus <code>p</code>
     */
    public BigInteger getP() {
        return p;
    }

    /**
     * Returns the base generator <code>g</code>.
     *
     * @return the base generator <code>g</code>
     */
    public BigInteger getG() {
        return g;
    }

    /**
     * Returns a hash code for this parameter specification.
     *
     * @return the hash code
     * @since 0.9.25
     */
    @Override
    public int hashCode() {
        return p.hashCode() ^ g.hashCode();
    }

    /**
     * Compares this ElGamal parameter spec to another object for equality.
     *
     * @param obj the object to compare
     * @return true if the objects are equal
     * @since 0.9.25
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        BigInteger op, og;
        if (obj instanceof ElGamalParameterSpec) {
            ElGamalParameterSpec egps = (ElGamalParameterSpec) obj;
            op = egps.getP();
            og = egps.getG();
        } else {
            return false;
        }
        return p.equals(op) && g.equals(og);
    }
}
