package org.bouncycastle.crypto.params;

import org.bouncycastle.crypto.CipherParameters;

/**
 * Parameter class for asymmetric key operations.
 * Distinguishes between public and private key usage in cryptographic operations.
 */
public class AsymmetricKeyParameter
    implements CipherParameters
{
    boolean privateKey;

    public AsymmetricKeyParameter(
        boolean privateKey)
    {
        this.privateKey = privateKey;
    }

    public boolean isPrivate()
    {
        return privateKey;
    }
}
