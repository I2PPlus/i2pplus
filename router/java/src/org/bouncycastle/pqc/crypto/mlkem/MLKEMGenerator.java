package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.pqc.crypto.util.SecretWithEncapsulationImpl;

/**
 * Generator for ML-KEM encapsulated secrets.
 * Creates encapsulated secrets for secure key exchange in post-quantum cryptography.
 */
public class MLKEMGenerator
{
    // the source of randomness
    private final SecureRandom sr;

    /**
     * MLKEMGenerator.
     *
     * @param random the source of randomness the KEM engine is initialized from
     */
    public MLKEMGenerator(SecureRandom random)
    {
        this.sr = random;
    }

    /**
     * generateEncapsulated.
     *
     * @param recipientKey the recipient's ML-KEM public key, from which the parameter set and
     *        engine are taken
     * @return the freshly drawn shared secret together with the ciphertext to send to the
     *         recipient
     */
    public SecretWithEncapsulation generateEncapsulated(AsymmetricKeyParameter recipientKey)
    {
        MLKEMPublicKeyParameters key = (MLKEMPublicKeyParameters)recipientKey;
        MLKEMEngine engine = key.getParameters().getEngine();
        engine.init(sr);

        byte[] randBytes = new byte[32];
        engine.getRandomBytes(randBytes);

        byte[][] kemEncrypt = engine.kemEncrypt(key.getEncoded(), randBytes);
        return new SecretWithEncapsulationImpl(kemEncrypt[0], kemEncrypt[1]);
    }
    /**
     * internalGenerateEncapsulated.
     *
     * @param recipientKey the recipient's ML-KEM public key, from which the parameter set and
     *        engine are taken
     * @param randBytes the 32 bytes of randomness to encrypt with, instead of drawing fresh
     *        ones for reproducible tests
     * @return the shared secret derived from randBytes together with the ciphertext to send to
     *         the recipient
     */
    public SecretWithEncapsulation internalGenerateEncapsulated(AsymmetricKeyParameter recipientKey, byte[] randBytes)
    {
        MLKEMPublicKeyParameters key = (MLKEMPublicKeyParameters)recipientKey;
        MLKEMEngine engine = key.getParameters().getEngine();
        engine.init(sr);

        byte[][] kemEncrypt = engine.kemEncryptInternal(key.getEncoded(), randBytes);
        return new SecretWithEncapsulationImpl(kemEncrypt[0], kemEncrypt[1]);
    }
}
