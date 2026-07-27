package org.bouncycastle.crypto;

import java.security.SecureRandom;

/**
 * Registry for cryptographic services and utilities.
 * Provides secure random source and constraint checking functionality.
 */
public class CryptoServicesRegistrar {

    /** default constructor */
    public CryptoServicesRegistrar() {}

    /** @param csp the service properties */
    public static void checkConstraints(CryptoServiceProperties csp) {}

    private static final SecureRandom sr = new SecureRandom();

    /**
     * Return the default source of randomness.
     *
     * @param secureRandom the secure random to check
     * @return the default SecureRandom
     */
    public static SecureRandom getSecureRandom(SecureRandom secureRandom)
    {
        return null == secureRandom ? sr : secureRandom;
    }
}
