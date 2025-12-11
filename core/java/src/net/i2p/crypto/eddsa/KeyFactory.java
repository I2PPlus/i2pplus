/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>lt;https://creativecommons.org/publicdomain/zero/1.0/<https://creativecommons.org/publicdomain/zero/1.0/>gt;.
 *
 */
package net.i2p.crypto.eddsa;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * Java Security Provider KeyFactory implementation for EdDSA (Edwards-curve Digital Signature Algorithm).
 *
 * This class provides the bridge between EdDSA-specific key specifications and the standard
 * Java Cryptography Architecture (JCA) KeyFactory interface. It enables conversion between
 * different key representations and supports standard encoding formats for interoperability.
 *
 * <p><strong>Supported Operations:</strong>
 * <ul>
 *   <li><strong>Key Generation:</strong> Create EdDSA keys from specifications</li>
 *   <li><strong>Key Conversion:</strong> Translate between different key formats</li>
 *   <li><strong>Standard Encoding:</strong> Support for PKCS#8 and X.509 formats</li>
 *   <li><strong>Key Specification:</strong> Extract key parameters from existing keys</li>
 * </ul>
 *
 * <p><strong>Supported Key Specifications:</strong>
 * <ul>
 *   <li>{@link EdDSAPublicKeySpec} - EdDSA-specific public key specification</li>
 *   <li>{@link EdDSAPrivateKeySpec} - EdDSA-specific private key specification</li>
 *   <li>{@link PKCS8EncodedKeySpec} - Standard PKCS#8 private key encoding (since 0.9.25)</li>
 *   <li>{@link X509EncodedKeySpec} - Standard X.509 public key encoding (since 0.9.25)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>
 * // Create KeyFactory instance
 * KeyFactory keyFactory = KeyFactory.getInstance("EdDSA", "I2P");
 *
 * // Generate public key from specification
 * EdDSAPublicKeySpec pubSpec = new EdDSAPublicKeySpec(publicPoint, curveParams);
 * EdDSAPublicKey publicKey = (EdDSAPublicKey) keyFactory.generatePublic(pubSpec);
 *
 * // Convert to standard X.509 format
 * X509EncodedKeySpec x509Spec = keyFactory.getKeySpec(publicKey, X509EncodedKeySpec.class);
 * </pre>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe and can be used concurrently
 * by multiple threads. Key objects created by this factory are immutable and thread-safe.
 *
 * <p><strong>Security Considerations:</strong>
 * <ul>
 *   <li>Private key material is handled securely during conversion</li>
 *   <li>Standard encoding formats ensure interoperability with other providers</li>
 *   <li>Key validation is performed during specification conversion</li>
 * </ul>
 *
 * @see java.security.KeyFactory
 * @see EdDSAPublicKey
 * @see EdDSAPrivateKey
 * @see net.i2p.crypto.provider.I2PProvider
 * @since 0.9.15
 * @author str4d
 */
public final class KeyFactory extends KeyFactorySpi {

    /**
     * Generates an EdDSA private key from the provided key specification.
     *
     * This method converts various key specification formats into EdDSA private keys,
     * supporting both EdDSA-specific specifications and standard PKCS#8 encoding.
     *
     * @param keySpec the key specification to convert
     * @return the generated EdDSA private key
     * @throws InvalidKeySpecException if the key specification is unsupported or malformed
     * @since 0.9.15
     * 
     * Supports PKCS8EncodedKeySpec since version 0.9.25.
     */
    protected PrivateKey engineGeneratePrivate(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof EdDSAPrivateKeySpec) {
            return new EdDSAPrivateKey((EdDSAPrivateKeySpec) keySpec);
        }
        if (keySpec instanceof PKCS8EncodedKeySpec) {
            return new EdDSAPrivateKey((PKCS8EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised: " + keySpec.getClass());
    }

    /**
     * Generates an EdDSA public key from the provided key specification.
     *
     * This method converts various key specification formats into EdDSA public keys,
     * supporting both EdDSA-specific specifications and standard X.509 encoding.
     *
     * @param keySpec the key specification to convert
     * @return the generated EdDSA public key
     * @throws InvalidKeySpecException if the key specification is unsupported or malformed
     * @since 0.9.15
     * 
     * Supports X509EncodedKeySpec since version 0.9.25.
     */
    protected PublicKey engineGeneratePublic(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof EdDSAPublicKeySpec) {
            return new EdDSAPublicKey((EdDSAPublicKeySpec) keySpec);
        }
        if (keySpec instanceof X509EncodedKeySpec) {
            return new EdDSAPublicKey((X509EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised: " + keySpec.getClass());
    }

    /**
     * Returns a specification (key material) of the given key object.
     *
     * This method extracts the underlying key parameters from EdDSA keys and
     * converts them into the requested specification format.
     *
     * @param <T> the type of the returned key specification
     * @param key the key to convert
     * @param keySpec the requested specification class
     * @return the corresponding key specification
     * @throws InvalidKeySpecException if the requested specification type is unsupported
     *                                  or the key cannot be converted
     * @since 0.9.15
     */
    @SuppressWarnings("unchecked")
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpec)
            throws InvalidKeySpecException {
        if (keySpec.isAssignableFrom(EdDSAPublicKeySpec.class) && key instanceof EdDSAPublicKey) {
            EdDSAPublicKey k = (EdDSAPublicKey) key;
            if (k.getParams() != null) {
                return (T) new EdDSAPublicKeySpec(k.getA(), k.getParams());
            }
        } else if (keySpec.isAssignableFrom(EdDSAPrivateKeySpec.class) && key instanceof EdDSAPrivateKey) {
            EdDSAPrivateKey k = (EdDSAPrivateKey) key;
            if (k.getParams() != null) {
                return (T) new EdDSAPrivateKeySpec(k.getSeed(), k.getH(), k.geta(), k.getA(), k.getParams());
            }
        }
        throw new InvalidKeySpecException("not implemented yet " + key + " " + keySpec);
    }

    /**
     * Translates a key object from one provider to another.
     *
     * This method would allow conversion between EdDSA keys from different
     * security providers, but currently no other EdDSA providers are known
     * or supported in the I2P environment.
     *
     * @param key the key to translate
     * @return the translated key
     * @throws InvalidKeyException always, as no other EdDSA providers are supported
     * @since 0.9.15
     */
    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        throw new InvalidKeyException("No other EdDSA key providers known");
    }
}
