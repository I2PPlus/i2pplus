package net.i2p.crypto.eddsa.spec;

import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;

/**
 * EdDSA Curve specification that can also be referred to by name.
 *
 * @author str4d
 */
public class EdDSANamedCurveSpec extends EdDSAParameterSpec {
    /** The curve name. */
    private final String name;

    /**
     * Create a named curve specification.
     *
     * @param name the curve name
     * @param curve the curve parameters
     * @param hashAlgo the hash algorithm
     * @param sc the scalar ops
     * @param b the base point
     */
    public EdDSANamedCurveSpec(String name, Curve curve, String hashAlgo, ScalarOps sc, GroupElement b) {
        super(curve, hashAlgo, sc, b);
        this.name = name;
    }

    /**
     * Return the curve name.
     *
     * @return the curve name
     */
    public String getName() {
        return name;
    }
}
