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
    private SecureRandom        random;
    private CipherParameters    parameters;

    /**
     * Wrap parameters with random.
     * @param parameters the cipher parameters to wrap; they are returned unchanged by getParameters()
     * @param random the secure random source for operations needing one, resolved through the
     * CryptoServicesRegistrar; null selects the registrar's default instance
     */
    public ParametersWithRandom(
        CipherParameters    parameters,
        SecureRandom        random)
    {
        this.random = CryptoServicesRegistrar.getSecureRandom(random);
        this.parameters = parameters;
    }

    /**
     * Wrap parameters with default random.
     * @param parameters the cipher parameters to wrap; they are returned unchanged by getParameters()
     */
    public ParametersWithRandom(
        CipherParameters    parameters)
    {
        this(parameters, null);
    }

    /**
     * getRandom.
     * @return the secure random source carried by these parameters, never null
     */
    public SecureRandom getRandom()
    {
        return random;
    }

    /**
     * getParameters.
     * @return the wrapped cipher parameters, exactly the instance supplied to the constructor
     */
    public CipherParameters getParameters()
    {
        return parameters;
    }
}
