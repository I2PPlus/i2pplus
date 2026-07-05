package net.i2p.crypto.elgamal.spec;

import java.security.spec.KeySpec;

/**
 * Base key specification for ElGamal keys.
 *
 * This class provides a foundation for ElGamal key specifications by encapsulating
 * the ElGamal parameters (prime p and generator g). It serves as a base class for
 * more specific key specifications used in key generation and conversion operations.
 */
public class ElGamalKeySpec implements KeySpec {
    private final ElGamalParameterSpec spec;

    /**
     * Constructs an ElGamal key specification with the given parameters.
     *
     * @param spec the ElGamal parameter specification containing prime p and generator g
     */
    public ElGamalKeySpec(ElGamalParameterSpec spec) {
        this.spec = spec;
    }

    /**
     * Returns the ElGamal parameter specification.
     *
     * @return the ElGamal parameter specification
     */
    public ElGamalParameterSpec getParams() {
        return spec;
    }
}
