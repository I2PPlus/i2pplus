package org.bouncycastle.crypto.params;

import java.security.SecureRandom;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.CryptoServicesRegistrar;

/**
 * Cipher parameters with associated random number generator.
 * Wraps cipher parameters with a secure random source for cryptographic operations.
 */
public class ParametersWithRandom
    implements CipherParameters
{
    /** ignored */
    private SecureRandom        random;
    /** ignored */
    private CipherParameters    parameters;

    /** Wrap parameters with random. */
    public ParametersWithRandom(
        CipherParameters    parameters,
        SecureRandom        random)
    {
        this.random = CryptoServicesRegistrar.getSecureRandom(random);
        this.parameters = parameters;
    }

    /** Wrap parameters with default random. */
    public ParametersWithRandom(
        CipherParameters    parameters)
    {
        this(parameters, null);
    }

    /**
     * getRandom.
     */
    public SecureRandom getRandom()
    {
        return random;
    }

    /**
     * getParameters.
     */
    public CipherParameters getParameters()
    {
        return parameters;
    }
}
