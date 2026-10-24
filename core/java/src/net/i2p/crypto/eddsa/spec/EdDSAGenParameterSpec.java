package net.i2p.crypto.eddsa.spec;

import java.security.spec.AlgorithmParameterSpec;

/**
 * Implementation of AlgorithmParameterSpec that holds the name of a named
 * EdDSA curve specification.
 *
 * @since 0.9.15
 *
 */
public class EdDSAGenParameterSpec implements AlgorithmParameterSpec {
    /** Name of the curve to generate the key pair on. */
    private final String name;

    /**
     * Parameter specification for the named EdDSA curve.
     *
     * @param stdName The standard name of the EdDSA curve.
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
