package net.i2p.router.crypto.pqc;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.RandomSource;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

/**
 * Wrapper around bouncycastle
 *
 * @since 0.9.69
 */
public final class MLKEM {

    private MLKEM() {}

    /** MLKEM-512 key factory */
    public static final KeyFactory MLKEM512KeyFactory = new MLKEMFactory(EncType.MLKEM512_X25519_INT);
    /** MLKEM-768 key factory */
    public static final KeyFactory MLKEM768KeyFactory = new MLKEMFactory(EncType.MLKEM768_X25519_INT);
    /** MLKEM-1024 key factory */
    public static final KeyFactory MLKEM1024KeyFactory = new MLKEMFactory(EncType.MLKEM1024_X25519_INT);

    private static class MLKEMFactory implements KeyFactory {
        private final EncType t;
        public MLKEMFactory(EncType type) { t = type; }
        public KeyPair getKeys() {
            try {
                return MLKEM.getKeys(t);
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
        }
    }

    /**
     *  Alice side
     *
     *  @param type must be one of the internal types MLKEM*_INT
     *  @return encapkey decapkey
     *  @throws GeneralSecurityException on failure
     */
    public static KeyPair getKeys(EncType type) throws GeneralSecurityException {
        byte[][] keys = generateKeys(type);
        PublicKey pub = new PublicKey(type, keys[0]);
        PrivateKey priv = new PrivateKey(type, keys[1]);
        return new KeyPair(pub, priv);
    }

    /**
     *  Alice side
     *
     *  @param type must be one of the internal types MLKEM*_INT
     *  @return encapkey decapkey
     *  @throws GeneralSecurityException on failure
     */
    public static byte[][] generateKeys(EncType type) throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMKeyPairGenerator kpg = new MLKEMKeyPairGenerator();
        kpg.init(new MLKEMKeyGenerationParameters(RandomSource.getInstance(), param));
        AsymmetricCipherKeyPair pair = kpg.generateKeyPair();
        MLKEMPublicKeyParameters pubkey = (MLKEMPublicKeyParameters) pair.getPublic();
        MLKEMPrivateKeyParameters privkey = (MLKEMPrivateKeyParameters) pair.getPrivate();
        byte[][] keys = new byte[2][];
        keys[0] = pubkey.getEncoded();
        keys[1] = privkey.getEncoded();
        return keys;
    }

    /**
     *  Bob side
     *
     *  @param type the encryption type
     *  @param pub the public key
     *  @return ciphertext sharedkey, non-null
     *  @throws GeneralSecurityException on failure
     */
    public static byte[][] encaps(EncType type, byte[] pub)
                        throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMGenerator gen = new MLKEMGenerator(I2PAppContext.getGlobalContext().random());
        MLKEMPublicKeyParameters ppub = new MLKEMPublicKeyParameters(param, pub);
        SecretWithEncapsulation swe;
        try {
            swe = gen.generateEncapsulated(ppub);
        } catch (IllegalArgumentException iae) {
            throw new GeneralSecurityException(iae);
        }
        byte[][] keys = new byte[2][];
        keys[0] = swe.getEncapsulation();
        keys[1] = swe.getSecret();
        return keys;
    }

    /**
     *  Alice side
     *  Note that this will not fail???
     *
     *  @param type the encryption type
     *  @param ciphertext the ciphertext to decrypt
     *  @param decapkey the decapsulation key
     *  @return sharedkey, 32 bytes, non-null
     *  @throws GeneralSecurityException on failure
     */
    public static byte[] decaps(EncType type, byte[] ciphertext, byte[] decapkey)
                        throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMPrivateKeyParameters priv = new MLKEMPrivateKeyParameters(param, decapkey);
        MLKEMExtractor ext = new MLKEMExtractor(priv);
        // todo check for "implicit rejection" ?
        return ext.extractSecret(ciphertext);
    }

    /**
     *  EncType to params
     * @return the param
     */
    private static MLKEMParameters getParam(EncType type) throws GeneralSecurityException {
        switch(type) {
            case MLKEM512_X25519_INT:
            case MLKEM512_X25519_CT:
                return MLKEMParameters.ml_kem_512;

            case MLKEM768_X25519_INT:
            case MLKEM768_X25519_CT:
                return MLKEMParameters.ml_kem_768;

            case MLKEM1024_X25519_INT:
            case MLKEM1024_X25519_CT:
                return MLKEMParameters.ml_kem_1024;

            default:
                throw new InvalidKeyException("unsupported type: " + type);
        }
    }

}
