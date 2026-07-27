package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.KeyGenerationParameters;

/**
 * Generator for ML-KEM (Module-Lattice Key Encapsulation Mechanism) key pairs.
 * Creates public/private key pairs for post-quantum cryptographic operations.
 */
/**
 * Creates a new MLKEMKeyPairGenerator instance.
 */
public class MLKEMKeyPairGenerator
{
    private MLKEMParameters mlkemParams;

    private SecureRandom random;

    private void initialize(
        KeyGenerationParameters param)
    {
        this.mlkemParams = ((MLKEMKeyGenerationParameters)param).getParameters();
        this.random = param.getRandom();

    }

    private AsymmetricCipherKeyPair genKeyPair()
    {
        MLKEMEngine engine = mlkemParams.getEngine();

        engine.init(random);

        byte[][] keyPair = engine.generateKemKeyPair();

        MLKEMPublicKeyParameters pubKey = new MLKEMPublicKeyParameters(mlkemParams, keyPair[0], keyPair[1]);
        MLKEMPrivateKeyParameters privKey = new MLKEMPrivateKeyParameters(mlkemParams,  keyPair[2], keyPair[3], keyPair[4], keyPair[0], keyPair[1], keyPair[5]);

        return new AsymmetricCipherKeyPair(pubKey, privKey);
    }

    /**
     * Initialize the key pair generator.
     *
     * @param param the key generation parameters
     */
    public void init(KeyGenerationParameters param)
    {
        this.initialize(param);
    }

    /**
     * Generate a new ML-KEM key pair.
     *
     * @return the generated key pair
     */
    public AsymmetricCipherKeyPair generateKeyPair()
    {
        return genKeyPair();
    }

    /**
     * Generate a key pair using the specified seed values.
     *
     * @param d the seed for the key generation
     * @param z the additional seed for the key generation
     * @return the generated key pair
     */
    public AsymmetricCipherKeyPair internalGenerateKeyPair(byte[] d, byte[] z)
    {
        byte[][] keyPair = mlkemParams.getEngine().generateKemKeyPairInternal(d, z);

        MLKEMPublicKeyParameters pubKey = new MLKEMPublicKeyParameters(mlkemParams, keyPair[0], keyPair[1]);
        MLKEMPrivateKeyParameters privKey = new MLKEMPrivateKeyParameters(mlkemParams,  keyPair[2], keyPair[3], keyPair[4], keyPair[0], keyPair[1], keyPair[5]);

        return new AsymmetricCipherKeyPair(pubKey, privKey);
    }
}
