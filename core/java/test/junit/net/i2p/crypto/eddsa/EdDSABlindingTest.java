package net.i2p.crypto.eddsa;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import java.math.BigInteger;
import java.security.MessageDigest;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.junit.Test;

/**
 * EdDSABlinding behavior pins: blind public/private consistency,
 * reduce range, and the blind/unblind round-trip.
 */
public class EdDSABlindingTest {

    private static final byte[] SEED = Utils.hexToBytes(
            "a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3");
    private static final byte[] ALPHA_SEED = Utils.hexToBytes(
            "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1");
    private static final byte[] MSG = "blinding round trip".getBytes();

    @Test
    public void testBlindPublicMatchesBlindPrivatePublic() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(SEED, spec));
        EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(key.getA(), spec));
        EdDSAPrivateKey alpha = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(ALPHA_SEED, spec));

        EdDSAPublicKey blindedPub = EdDSABlinding.blind(pub, alpha);
        EdDSAPrivateKey blindedPriv = EdDSABlinding.blind(key, alpha);

        // A' = A + alpha*B must be the same point from both paths
        assertThat(blindedPriv.getA(), equalTo(blindedPub.getA()));
    }

    @Test
    public void testReduceInRange() throws Exception {
        byte[] big = new byte[64];
        for (int i = 0; i < 64; i++)
            big[i] = (byte) 0xff;
        byte[] red = EdDSABlinding.reduce(big);
        assertThat("reduced scalar is 32 bytes", red.length, is(32));
        // little-endian
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++)
            be[i] = red[31 - i];
        BigInteger v = new BigInteger(1, be);
        assertThat("reduced scalar < group order", v.compareTo(EdDSABlinding.ORDER), is(lessThan(0)));
    }

    /**
     * blind then unblind must recover a key with the same public point.
     * The recovered scalar is the original reduced mod l, which is
     * cryptographically equivalent (same point, same signatures).
     *
     * @throws Exception on hash algorithm or key setup failure, or if the cross-check
     *                   signature cannot be signed or verified
     */
    @Test
    public void testBlindUnblindRoundTrip() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(SEED, spec));
        EdDSAPrivateKey alpha = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(ALPHA_SEED, spec));

        EdDSAPrivateKey blinded = EdDSABlinding.blind(key, alpha);
        assertThat("blinding must change the public point", blinded.getA(), is(not(equalTo(key.getA()))));

        EdDSAPrivateKey unblinded = EdDSABlinding.unblind(blinded, alpha);
        assertThat("unblind(blind(k)) must recover the public point", unblinded.getA(), equalTo(key.getA()));

        // Cross-check: a signature by the original verifies under the
        // unblinded key's public point.
        EdDSAEngine sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        sgr.initSign(key);
        byte[] sig = sgr.signOneShot(MSG);
        sgr.initVerify(new EdDSAPublicKey(new EdDSAPublicKeySpec(unblinded.getA(), spec)));
        assertThat("signature must verify under the recovered key", sgr.verifyOneShot(MSG, sig), is(true));
    }
}
