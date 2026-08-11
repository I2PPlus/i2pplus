package net.i2p.crypto.eddsa;

import net.i2p.crypto.eddsa.spec.EdDSAGenParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.util.RandomSource;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Key pair generator for EdDSA (Edwards-curve Digital Signature Algorithm) keys.
 *
 * This implementation generates EdDSA key pairs with a default key size of 256 bits
 * using the Ed25519 curve, which provides strong security with excellent performance
 * characteristics. EdDSA is the recommended signature algorithm for new I2P applications.
 *
 * <p>Generated keys are suitable for:</p>
 * <ul>
 *   <li>Digital signatures and verification</li>
 *   <li>I2P destination keys</li>
 *   <li>Router identity and communication</li>
 * </ul>
 *
 * @author str4d
 *
 * @see <a href="https://tools.ietf.org/html/rfc8032">RFC 8032 - EdDSA</a>
 * @since 0.9.15
 */
public class KeyPairGenerator extends KeyPairGeneratorSpi {
    /**
     * DEFAULT_KEYSIZE.
     */
    protected static final int DEFAULT_KEYSIZE = 256;
    /**
     * EdDSA parameter specification, set by initialize().
     */
    protected EdDSAParameterSpec edParams;
    /**
     * The random number source, set by initialize().
     */
    protected SecureRandom random;
    /**
     * Whether the generator has been initialized.
     */
    protected boolean initialized;

    private static final Map<Integer, AlgorithmParameterSpec> edParameters;

    static {
        edParameters = new HashMap<>();

        edParameters.put(Integer.valueOf(256), new EdDSAGenParameterSpec(EdDSANamedCurveTable.ED_25519));
    }

    /**
     * Initialize with the given key size.
     */
    @Override
    public void initialize(int keysize, SecureRandom random) {
        AlgorithmParameterSpec edParams = edParameters.get(Integer.valueOf(keysize));
        if (edParams == null) throw new InvalidParameterException("Unknown key type.");
        try {
            initialize(edParams, random);
        } catch (InvalidAlgorithmParameterException e) {
            throw new InvalidParameterException("key type not configurable.");
        }
    }

    /**
     * Initialize with the given parameter spec.
     */
    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
        if (params instanceof EdDSAParameterSpec) {
            edParams = (EdDSAParameterSpec) params;
        } else if (params instanceof EdDSAGenParameterSpec) {
            edParams = createNamedCurveSpec(((EdDSAGenParameterSpec) params).getName());
        } else throw new InvalidAlgorithmParameterException("parameter object not a EdDSAParameterSpec");

        this.random = random;
        initialized = true;
    }

    /**
     *  @return the generated key pair
     */
    @Override
    public KeyPair generateKeyPair() {
        if (!initialized) initialize(DEFAULT_KEYSIZE, RandomSource.getInstance());

        byte[] seed = new byte[edParams.getCurve().getField().getb() / 8];
        random.nextBytes(seed);

        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(seed, edParams);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(privKey.getA(), edParams);

        return new KeyPair(new EdDSAPublicKey(pubKey), new EdDSAPrivateKey(privKey));
    }

    /**
     * Create an EdDSANamedCurveSpec from the provided curve name. The current
     * implementation fetches the pre-created curve spec from a table.
     *
     * @param curveName the EdDSA named curve.
     * @return the specification for the named curve.
     * @throws InvalidAlgorithmParameterException if the named curve is unknown.
     */
    protected EdDSANamedCurveSpec createNamedCurveSpec(String curveName) throws InvalidAlgorithmParameterException {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(curveName);
        if (spec == null) {
            throw new InvalidAlgorithmParameterException("Unknown curve name: " + curveName);
        }
        return spec;
    }
}
