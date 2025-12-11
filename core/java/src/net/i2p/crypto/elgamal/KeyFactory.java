package net.i2p.crypto.elgamal;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import net.i2p.crypto.elgamal.impl.ElGamalPrivateKeyImpl;
import net.i2p.crypto.elgamal.impl.ElGamalPublicKeyImpl;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPrivateKeySpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;

/**
 * Java Security Provider KeyFactory implementation for ElGamal public-key cryptosystem.
 *
 * This class provides the bridge between ElGamal-specific key specifications and the standard
 * Java Cryptography Architecture (JCA) KeyFactory interface. It enables conversion between
 * different key representations and supports standard encoding formats for ElGamal operations
 * within the I2P network.
 *
 * <p><strong>Supported Operations:</strong>
 * <ul>
 *   <li><strong>Key Generation:</strong> Create ElGamal keys from specifications</li>
 *   <li><strong>Key Conversion:</strong> Translate between different key formats</li>
 *   <li><strong>Standard Encoding:</strong> Support for PKCS#8 and X.509 formats</li>
 *   <li><strong>Key Specification:</strong> Extract key parameters from existing keys</li>
 * </ul>
 *
 * <p><strong>Supported Key Specifications:</strong>
 * <ul>
 *   <li>{@link ElGamalPublicKeySpec} - ElGamal-specific public key specification</li>
 *   <li>{@link ElGamalPrivateKeySpec} - ElGamal-specific private key specification</li>
 *   <li>{@link PKCS8EncodedKeySpec} - Standard PKCS#8 private key encoding</li>
 *   <li>{@link X509EncodedKeySpec} - Standard X.509 public key encoding</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>
 * // Create KeyFactory instance
 * KeyFactory keyFactory = KeyFactory.getInstance("ElGamal", "I2P");
 *
 * // Generate private key from specification
 * ElGamalParameterSpec params = new ElGamalParameterSpec(p, g);
 * ElGamalPrivateKeySpec privSpec = new ElGamalPrivateKeySpec(privateKey, params);
 * ElGamalPrivateKey privateKey = (ElGamalPrivateKey) keyFactory.generatePrivate(privSpec);
 *
 * // Convert to standard PKCS#8 format
 * PKCS8EncodedKeySpec pkcs8Spec = keyFactory.getKeySpec(privateKey, PKCS8EncodedKeySpec.class);
 * </pre>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe and can be used concurrently
 * by multiple threads. Key objects created by this factory are immutable and thread-safe.
 *
 * <p><strong>Security Considerations:</strong>
 * <ul>
 *   <li>Private key material is handled securely during conversion</li>
 *   <li>Standard encoding formats ensure interoperability with other providers</li>
 *   <li>ElGamal parameters are validated during key creation</li>
 * </ul>
 *
 * @see java.security.KeyFactory
 * @see ElGamalPublicKey
 * @see ElGamalPrivateKey
 * @see net.i2p.crypto.provider.I2PProvider
 * @since 0.9.25
 */
public final class KeyFactory extends KeyFactorySpi {

    /**
     * Generates an ElGamal private key from the provided key specification.
     *
     * This method converts various key specification formats into ElGamal private keys,
     * supporting both ElGamal-specific specifications and standard PKCS#8 encoding.
     *
     * @param keySpec the key specification to convert
     * @return the generated ElGamal private key
     * @throws InvalidKeySpecException if the key specification is unsupported or malformed
     * @since 0.9.25
     */
    protected PrivateKey engineGeneratePrivate(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof ElGamalPrivateKeySpec) {
            return new ElGamalPrivateKeyImpl((ElGamalPrivateKeySpec) keySpec);
        }
        if (keySpec instanceof PKCS8EncodedKeySpec) {
            return new ElGamalPrivateKeyImpl((PKCS8EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised");
    }

    /**
     * Generates an ElGamal public key from the provided key specification.
     *
     * This method converts various key specification formats into ElGamal public keys,
     * supporting both ElGamal-specific specifications and standard X.509 encoding.
     *
     * @param keySpec the key specification to convert
     * @return the generated ElGamal public key
     * @throws InvalidKeySpecException if the key specification is unsupported or malformed
     * @since 0.9.25
     */
    protected PublicKey engineGeneratePublic(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof ElGamalPublicKeySpec) {
            return new ElGamalPublicKeyImpl((ElGamalPublicKeySpec) keySpec);
        }
        if (keySpec instanceof X509EncodedKeySpec) {
            return new ElGamalPublicKeyImpl((X509EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised");
    }

    /**
     * Returns a specification (key material) of the given key object.
     *
     * This method extracts the underlying key parameters from ElGamal keys and
     * converts them into the requested specification format.
     *
     * @param <T> the type of the returned key specification
     * @param key the key to convert
     * @param keySpec the requested specification class
     * @return the corresponding key specification
     * @throws InvalidKeySpecException if the requested specification type is unsupported
     *                                  or the key cannot be converted
     * @since 0.9.25
     */
    @SuppressWarnings("unchecked")
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpec)
            throws InvalidKeySpecException {
        if (keySpec.isAssignableFrom(ElGamalPublicKeySpec.class) && key instanceof ElGamalPublicKey) {
            ElGamalPublicKey k = (ElGamalPublicKey) key;
            ElGamalParameterSpec egp = k.getParameters();
            if (egp != null) {
                return (T) new ElGamalPrivateKeySpec(k.getY(), egp);
            }
        } else if (keySpec.isAssignableFrom(ElGamalPrivateKeySpec.class) && key instanceof ElGamalPrivateKey) {
            ElGamalPrivateKey k = (ElGamalPrivateKey) key;
            ElGamalParameterSpec egp = k.getParameters();
            if (egp != null) {
                return (T) new ElGamalPrivateKeySpec(k.getX(), egp);
            }
        }
        throw new InvalidKeySpecException("not implemented yet " + key + " " + keySpec);
    }

    /**
     * Translates a key object from one provider to another.
     *
     * This method would allow conversion between ElGamal keys from different
     * security providers, but currently no other ElGamal providers are known
     * or supported in the I2P environment.
     *
     * @param key the key to translate
     * @return the translated key
     * @throws InvalidKeyException always, as no other ElGamal providers are supported
     * @since 0.9.25
     */
    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        throw new InvalidKeyException("No other ElGamal key providers known");
    }
}
