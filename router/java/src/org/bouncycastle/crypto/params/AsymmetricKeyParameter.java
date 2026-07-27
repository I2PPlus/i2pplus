package org.bouncycastle.crypto.params;

import org.bouncycastle.crypto.CipherParameters;

/**
 * Parameter class for asymmetric key operations.
 * Distinguishes between public and private key usage in cryptographic operations.
 */
public class AsymmetricKeyParameter
    implements CipherParameters
{
    /** whether this is a private key */
    boolean privateKey;

    /**
     * Create a new asymmetric key parameter.
     *
     *  @param privateKey true for private key, false for public key
     */
    public AsymmetricKeyParameter(
        boolean privateKey)
    {
        this.privateKey = privateKey;
    }

    /**
     * Check if this is a private key.
     *
     * @return true if this is a private key
     */
    public boolean isPrivate()
    {
        return privateKey;
    }
}
