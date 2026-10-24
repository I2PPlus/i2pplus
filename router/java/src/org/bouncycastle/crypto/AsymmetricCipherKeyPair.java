package org.bouncycastle.crypto;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

/**
 * A holding class for public/private parameter pairs.
 */
public class AsymmetricCipherKeyPair
{
    /** The public key's parameters. */
    private AsymmetricKeyParameter    publicParam;
    /** The private key's parameters. */
    private AsymmetricKeyParameter    privateParam;

    /**
     * Creates a key pair from its parts.
     *
     * @param publicParam a public key parameters object.
     * @param privateParam the corresponding private key parameters.
     */
    public AsymmetricCipherKeyPair(
        AsymmetricKeyParameter    publicParam,
        AsymmetricKeyParameter    privateParam)
    {
        this.publicParam = publicParam;
        this.privateParam = privateParam;
    }

    /**
     * Creates a key pair from its parts.
     *
     * @param publicParam a public key parameters object.
     * @param privateParam the corresponding private key parameters.
     * @deprecated use {@link AsymmetricKeyParameter}
     */
    public AsymmetricCipherKeyPair(
        CipherParameters    publicParam,
        CipherParameters    privateParam)
    {
        this.publicParam = (AsymmetricKeyParameter)publicParam;
        this.privateParam = (AsymmetricKeyParameter)privateParam;
    }

    /**
     * Return the public key parameters.
     *
     * @return the public key parameters.
     */
    public AsymmetricKeyParameter getPublic()
    {
        return publicParam;
    }

    /**
     * Return the private key parameters.
     *
     * @return the private key parameters.
     */
    public AsymmetricKeyParameter getPrivate()
    {
        return privateParam;
    }
}
