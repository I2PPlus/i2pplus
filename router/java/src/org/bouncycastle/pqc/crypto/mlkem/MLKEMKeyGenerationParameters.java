package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;
import org.bouncycastle.crypto.KeyGenerationParameters;

/**
 * Key generation parameters for ML-KEM (Module-Lattice Key Encapsulation Mechanism).
 * Contains parameters for generating ML-KEM key pairs with specified security level.
 */
public class MLKEMKeyGenerationParameters
    extends KeyGenerationParameters
{
    private final MLKEMParameters params;

    /**
     * Create ML-KEM key generation parameters.
     *
     * @param random the entropy source used for key generation
     * @param mlkemParameters the ML-KEM parameter set selecting the security level
     */
    public MLKEMKeyGenerationParameters(
        SecureRandom random,
        MLKEMParameters mlkemParameters)
    {
        super(random, 256);
        this.params = mlkemParameters;
    }

    /**
     * getParameters.
     *
     * @return the ML-KEM parameter set these generation parameters were built with
     */
    public MLKEMParameters getParameters()
    {
        return params;
    }
}
