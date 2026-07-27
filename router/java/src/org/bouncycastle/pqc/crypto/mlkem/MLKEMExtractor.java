package org.bouncycastle.pqc.crypto.mlkem;

/**
 * Extractor for ML-KEM private key operations.
 * Provides functionality to extract shared secrets from encapsulated data using private keys.
 */
public class MLKEMExtractor
{
    private final MLKEMPrivateKeyParameters privateKey;
    private final MLKEMEngine engine;

    /**
     * Creates a new MLKEMExtractor instance.
     *
     * @param privateKey the private key to use for decapsulation
     */
    public MLKEMExtractor(MLKEMPrivateKeyParameters privateKey)
    {
        if (privateKey == null)
        {
            throw new NullPointerException("'privateKey' cannot be null");
        }

        this.privateKey = privateKey;
        this.engine = privateKey.getParameters().getEngine();
    }

    /**
     * Extract the shared secret from the encapsulated data.
     *
     * @param encapsulation the encapsulated data
     * @return the shared secret
     */
    public byte[] extractSecret(byte[] encapsulation)
    {
        return engine.kemDecrypt(privateKey.getEncoded(), encapsulation);
    }

    /**
     * Get the encapsulation length.
     *
     * @return the encapsulation length in bytes
     */
    public int getEncapsulationLength()
    {
        return engine.getCryptoCipherTextBytes();
    }
}
