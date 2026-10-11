package org.bouncycastle.crypto;

/**
 * Properties interface for cryptographic services.
 * Provides metadata about security level, service name, purpose, and parameters.
 */
public interface CryptoServiceProperties
{
    /**
     * Return the security level in bits.
     * @return security level in bits
     */
    int bitsOfSecurity();

    /**
     * Return the name of the cryptographic service.
     * @return service name
     */
    String getServiceName();

    /**
     * Return the purpose of the cryptographic service.
     * @return what the service is for: agreement, encryption, decryption, key
     *         generation, signing, verifying, authentication, a PRF, or any
     */
    CryptoServicePurpose getPurpose();

    /**
     * Return the parameters for the cryptographic service.
     * @return the service properties that describe this algorithm
     */
    Object getParams();
}
