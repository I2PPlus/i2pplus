package net.i2p.crypto.eddsa;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;

import java.math.BigInteger;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.junit.Test;

/**
 * EdDSABlinding behavior pins: blind public/private consistency,
 * reduce range, and the current (throwing) unblind landmine.
 */
public class EdDSABlindingTest {

    private static final byte[] SEED = Utils.hexToBytes(
            "a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3");
    private static final byte[] ALPHA_SEED = Utils.hexToBytes(
            "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1");

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
     * Landmine pin: unblind() is routed from Blinding.unblind but has never
     * been implemented. If R5 implements it, update this test to a real
     * blind/unblind round-trip instead of deleting it.
     */
    @Test
    public void testUnblindCurrentlyThrows() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(SEED, spec));
        EdDSAPrivateKey alpha = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(ALPHA_SEED, spec));
        EdDSAPrivateKey blinded = EdDSABlinding.blind(key, alpha);
        try {
            EdDSABlinding.unblind(blinded, alpha);
            fail("unblind currently must throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }
}
