package org.bouncycastle.crypto;

/**
 * Properties interface for cryptographic services.
 * Provides metadata about security level, service name, purpose, and parameters.
 */
public interface CryptoServiceProperties
{
    /**
     * Return the security level in bits.
     * @return security level
     */
    int bitsOfSecurity();

    /**
     * Return the name of the cryptographic service.
     * @return service name
     */
    String getServiceName();

    /**
     * Return the purpose of the cryptographic service.
     * @return service purpose
     */
    CryptoServicePurpose getPurpose();

    /**
     * Return the parameters for the cryptographic service.
     * @return service parameters
     */
    Object getParams();
}
