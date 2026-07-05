/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>lt;https://creativecommons.org/publicdomain/zero/1.0/<https://creativecommons.org/publicdomain/zero/1.0/>gt;.
 *
 */
package net.i2p.crypto.eddsa.spec;

import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;

/**
 * Parameter specification for an EdDSA algorithm.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAParameterSpec implements AlgorithmParameterSpec, Serializable {
    private static final long serialVersionUID = 8274987108472012L;
    private final Curve curve;
    private final String hashAlgo;
    private final ScalarOps sc;
    private final GroupElement B;

    /**
     * Create a new EdDSA parameter specification.
     *
     * @param curve the curve
     * @param hashAlgo the JCA string for the hash algorithm
     * @param sc the parameter L represented as ScalarOps
     * @param B the parameter B
     * @throws IllegalArgumentException if hash algorithm is unsupported or length is wrong
     */
    public EdDSAParameterSpec(Curve curve, String hashAlgo, ScalarOps sc, GroupElement B) {
        try {
            MessageDigest hash = MessageDigest.getInstance(hashAlgo);
            // EdDSA hash function must produce 2b-bit output
            if (curve.getField().getb() / 4 != hash.getDigestLength()) throw new IllegalArgumentException("Hash output is not 2b-bit");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm");
        }

        this.curve = curve;
        this.hashAlgo = hashAlgo;
        this.sc = sc;
        this.B = B;
    }

    /**
     * Returns the curve used by this parameter specification.
     *
     * @return the curve
     */
    public Curve getCurve() {
        return curve;
    }

    /**
     * Returns the JCA hash algorithm name.
     *
     * @return the hash algorithm name
     */
    public String getHashAlgorithm() {
        return hashAlgo;
    }

    /**
     * Returns the scalar operations implementation.
     *
     * @return the scalar operations
     */
    public ScalarOps getScalarOps() {
        return sc;
    }

    /**
     * Return the base (generator) point.
     *
     *  @return the base (generator)
     */
    public GroupElement getB() {
        return B;
    }

    /**
     * Returns a hash code for this parameter specification.
     *
     * @return the hash code
     * @since 0.9.25
     */
    @Override
    public int hashCode() {
        return hashAlgo.hashCode() ^ curve.hashCode() ^ B.hashCode();
    }

    /**
     * Compares this parameter specification to another object for equality.
     *
     * @param o the object to compare
     * @return true if the objects are equal
     * @since 0.9.25
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof EdDSAParameterSpec)) return false;
        EdDSAParameterSpec s = (EdDSAParameterSpec) o;
        return hashAlgo.equals(s.getHashAlgorithm()) && curve.equals(s.getCurve()) && B.equals(s.getB());
    }
}
