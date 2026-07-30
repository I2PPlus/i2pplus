package net.i2p.crypto.eddsa.spec;

import java.security.spec.AlgorithmParameterSpec;

/**
 * Implementation of AlgorithmParameterSpec that holds the name of a named
 * EdDSA curve specification.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAGenParameterSpec implements AlgorithmParameterSpec {
    private final String name;

    /**
     * Constructs a parameter specification for the named EdDSA curve.
     *
     * @param stdName the standard name of the EdDSA curve
     */
    public EdDSAGenParameterSpec(String stdName) {
        name = stdName;
    }

    /**
     * Returns the name of the EdDSA curve.
     *
     * @return the curve name
     */
    public String getName() {
        return name;
    }
}
