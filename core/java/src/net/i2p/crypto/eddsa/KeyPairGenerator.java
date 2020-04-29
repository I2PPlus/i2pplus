/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.HashMap;
import java.util.Map;

import net.i2p.crypto.eddsa.spec.EdDSAGenParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.util.RandomSource;

/**
 *  Default keysize is 256 (Ed25519)
 *
 *  @since 0.9.15
 */
public class KeyPairGenerator extends KeyPairGeneratorSpi {
    protected static final int DEFAULT_KEYSIZE = 256;
    protected EdDSAParameterSpec edParams;
    protected SecureRandom random;
    protected boolean initialized;

    private static final Map<Integer, AlgorithmParameterSpec> edParameters;

    static {
        edParameters = new HashMap<Integer, AlgorithmParameterSpec>();

        edParameters.put(Integer.valueOf(256), new EdDSAGenParameterSpec(EdDSANamedCurveTable.ED_25519));
    }

    public void initialize(int keysize, SecureRandom random) {
        AlgorithmParameterSpec edParams = edParameters.get(Integer.valueOf(keysize));
        if (edParams == null)
            throw new InvalidParameterException("unknown key type.");
        try {
            initialize(edParams, random);
        } catch (InvalidAlgorithmParameterException e) {
            throw new InvalidParameterException("key type not configurable.");
        }
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
        if (params instanceof EdDSAParameterSpec) {
            edParams = (EdDSAParameterSpec) params;
        } else if (params instanceof EdDSAGenParameterSpec) {
            edParams = createNamedCurveSpec(((EdDSAGenParameterSpec) params).getName());
        } else
            throw new InvalidAlgorithmParameterException("parameter object not a EdDSAParameterSpec");

        this.random = random;
        initialized = true;
    }

    public KeyPair generateKeyPair() {
        if (!initialized)
            initialize(DEFAULT_KEYSIZE, RandomSource.getInstance());

        byte[] seed = new byte[edParams.getCurve().getField().getb()/8];
        random.nextBytes(seed);

        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(seed, edParams);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(privKey.getA(), edParams);

        return new KeyPair(new EdDSAPublicKey(pubKey), new EdDSAPrivateKey(privKey));
    }

    /**
     * Create an EdDSANamedCurveSpec from the provided curve name. The current
     * implementation fetches the pre-created curve spec from a table.
     * @param curveName the EdDSA named curve.
     * @return the specification for the named curve.
     * @throws InvalidAlgorithmParameterException if the named curve is unknown.
     */
    protected EdDSANamedCurveSpec createNamedCurveSpec(String curveName) throws InvalidAlgorithmParameterException {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(curveName);
        if (spec == null) {
            throw new InvalidAlgorithmParameterException("unknown curve name: " + curveName);
        }
        return spec;
    }
}
