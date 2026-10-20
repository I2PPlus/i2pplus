package net.i2p.crypto.eddsa;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.junit.Test;

/**
 * X.509 / PKCS#8 encoding round-trips for EdDSA keys, via the direct
 * ctors and via KeyFactory. Locks in behavior for the hand-rolled ASN.1
 * in EdDSAPublicKey / EdDSAPrivateKey.
 */
public class EdDSAKeyEncodingTest {

    private static final byte[] SEED = Utils.hexToBytes(
            "c5aa8df43f9f837bedb74427106a7b23ff553bf2b8e659c7d470f1fc52bb626a");

    @Test
    public void testPublicKeyX509RoundTrip() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPublicKey orig = new EdDSAPublicKey(new EdDSAPublicKeySpec(
                new EdDSAPrivateKeySpec(SEED, spec).getA(), spec));

        byte[] enc = orig.getEncoded();
        assertThat("getEncoded must not be null", enc, notNullValue());
        assertThat("public encoding is 44 bytes (12 header + 32 key)", enc.length, is(44));

        // direct ctor
        EdDSAPublicKey viaCtor = new EdDSAPublicKey(new X509EncodedKeySpec(enc));
        assertThat(viaCtor.getA(), equalTo(orig.getA()));
        assertThat(viaCtor.getEncoded(), equalTo(enc));

        // KeyFactory via the I2P provider registration
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EdDSA", new net.i2p.crypto.provider.I2PProvider());
        PublicKey viaKf = kf.generatePublic(new X509EncodedKeySpec(enc));
        assertThat(((EdDSAPublicKey) viaKf).getA(), equalTo(orig.getA()));
    }

    @Test
    public void testPrivateKeyPKCS8RoundTrip() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey orig = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(SEED, spec));

        byte[] enc = orig.getEncoded();
        assertThat("seeded key must produce a non-empty PKCS#8 encoding", enc.length > 0, is(true));

        // direct ctor
        EdDSAPrivateKey viaCtor = new EdDSAPrivateKey(new PKCS8EncodedKeySpec(enc));
        assertThat(viaCtor.getA(), equalTo(orig.getA()));
        assertThat("seed must survive the round-trip", viaCtor.getSeed(), equalTo(SEED));

        // KeyFactory via the I2P provider registration
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EdDSA", new net.i2p.crypto.provider.I2PProvider());
        PrivateKey viaKf = kf.generatePrivate(new PKCS8EncodedKeySpec(enc));
        assertThat(((EdDSAPrivateKey) viaKf).getA(), equalTo(orig.getA()));
    }

    @Test
    public void testPrivateAndPublicAgree() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKeySpec privSpec = new EdDSAPrivateKeySpec(SEED, spec);
        EdDSAPrivateKey priv = new EdDSAPrivateKey(privSpec);
        EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(privSpec.getA(), spec));
        assertThat(priv.getA(), equalTo(pub.getA()));
    }

    /**
     * A private key built from H (no seed) cannot produce a PKCS#8 encoding;
     * pin the current behavior: empty array, not null.
     */
    @Test
    public void testUnseededPrivateKeyEncodesEmpty() throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        byte[] h = new byte[64];
        h[0] = 1;
        EdDSAPrivateKey noSeed = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(spec, h));
        try {
            byte[] enc = noSeed.getEncoded();
            assertThat("unseeded encoding must be empty, not null", enc.length, is(0));
        } catch (Exception e) {
            fail("getEncoded must not throw on unseeded key, got: " + e);
        }
    }

    /**
     * Garbage in the X.509 wrapper must be rejected, not silently accepted.
     */
    @Test(expected = java.security.spec.InvalidKeySpecException.class)
    public void testGarbageX509Rejected() throws Exception {
        new EdDSAPublicKey(new X509EncodedKeySpec(new byte[7]));
    }
}
