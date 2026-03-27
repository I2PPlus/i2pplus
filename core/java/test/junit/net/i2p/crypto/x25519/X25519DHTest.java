package net.i2p.crypto.x25519;

import static org.junit.Assert.*;

import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

import org.junit.Test;

public class X25519DHTest {

    private static final EncType TYPE = EncType.ECIES_X25519;

    private static byte[] hex(String hex) {
        return DataHelper.fromHexString(hex);
    }

    private static PrivateKey priv(String hex) {
        byte[] data = hex(hex);
        if (data.length == 33) data = java.util.Arrays.copyOfRange(data, 1, 33);
        assertEquals(32, data.length);
        return new PrivateKey(TYPE, data);
    }

    private static PublicKey pub(String hex) {
        byte[] data = hex(hex);
        if (data.length == 33) data = java.util.Arrays.copyOfRange(data, 1, 33);
        assertEquals(32, data.length);
        // RFC 7748: mask high bit
        data[31] &= 0x7f;
        return new PublicKey(TYPE, data);
    }

    /**
     * RFC 7748 Section 6.1, Alice-Bob test vector 1.
     */
    @Test
    public void testRFC7748Vector1() {
        PrivateKey aPriv = priv("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        PublicKey bPub = pub("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        SessionKey sk = X25519DH.dh(aPriv, bPub);
        String expected = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552";
        assertEquals(expected, DataHelper.toHexString(sk.getData()));
    }

    /**
     * RFC 7748 Section 6.1, Alice-Bob test vector 2.
     * Public key has high bit set; must be masked.
     */
    @Test
    public void testRFC7748Vector2() {
        PrivateKey aPriv = priv("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d");
        // Use pub() helper which strips leading zero byte from BigInteger.toByteArray()
        PublicKey bPub = pub("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493");
        SessionKey sk = X25519DH.dh(aPriv, bPub);
        String expected = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957";
        assertEquals(expected, DataHelper.toHexString(sk.getData()));
    }

    /**
     * RFC 7748 Section 6.1, single iteration base point test.
     */
    @Test
    public void testRFC7748SingleIteration() {
        byte[] aPrivBytes = new byte[32];
        aPrivBytes[0] = 0x09;
        PrivateKey aPriv = new PrivateKey(TYPE, aPrivBytes);
        byte[] bPubBytes = new byte[32];
        bPubBytes[0] = 0x09;
        PublicKey bPub = new PublicKey(TYPE, bPubBytes);
        SessionKey sk = X25519DH.dh(aPriv, bPub);
        // After one iteration:
        // 422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079
        String expected = "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079";
        assertEquals(expected, DataHelper.toHexString(sk.getData()));
    }

    /**
     * RFC 7748 Section 6.1, 1000 iteration test.
     */
    @Test
    public void testRFC7748ThousandIterations() {
        byte[] aPrivBytes = new byte[32];
        aPrivBytes[0] = 0x09;
        PrivateKey aPriv = new PrivateKey(TYPE, aPrivBytes);
        byte[] bPubBytes = new byte[32];
        bPubBytes[0] = 0x09;
        PublicKey bPub = new PublicKey(TYPE, bPubBytes);
        SessionKey sk = X25519DH.dh(aPriv, bPub);

        for (int i = 1; i < 1000; i++) {
            aPriv.getData()[31] &= 0x7f;
            bPub = new PublicKey(TYPE, aPriv.getData());
            aPriv = new PrivateKey(TYPE, sk.getData());
            sk = X25519DH.dh(aPriv, bPub);
        }
        // After 1,000 iterations:
        // 684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51
        String expected = "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51";
        assertEquals(expected, DataHelper.toHexString(sk.getData()));
    }

    @Test
    public void testSymmetricDH() {
        // Generate deterministic keys for Alice and Bob
        byte[] aPriv = new byte[32];
        byte[] bPriv = new byte[32];
        for (int i = 0; i < 32; i++) {
            aPriv[i] = (byte) (i + 1);
            bPriv[i] = (byte) (i + 100);
        }
        // Clamp private keys
        aPriv[0] &= 0xf8;
        aPriv[31] &= 0x7f;
        aPriv[31] |= 0x40;
        bPriv[0] &= 0xf8;
        bPriv[31] &= 0x7f;
        bPriv[31] |= 0x40;

        PrivateKey alicePriv = new PrivateKey(TYPE, aPriv);
        PrivateKey bobPriv = new PrivateKey(TYPE, bPriv);

        // Derive public keys by DH with base point (null publicKey)
        byte[] alicePubBytes = new byte[32];
        com.southernstorm.noise.crypto.x25519.Curve25519.eval(alicePubBytes, 0, aPriv, null);
        byte[] bobPubBytes = new byte[32];
        com.southernstorm.noise.crypto.x25519.Curve25519.eval(bobPubBytes, 0, bPriv, null);

        // Mask high bit on public keys
        alicePubBytes[31] &= 0x7f;
        bobPubBytes[31] &= 0x7f;

        PublicKey alicePub = new PublicKey(TYPE, alicePubBytes);
        PublicKey bobPub = new PublicKey(TYPE, bobPubBytes);

        SessionKey shared1 = X25519DH.dh(alicePriv, bobPub);
        SessionKey shared2 = X25519DH.dh(bobPriv, alicePub);

        assertArrayEquals(shared1.getData(), shared2.getData());
    }

    @Test
    public void testDhValid32ByteKeys() {
        byte[] privData = new byte[32];
        byte[] pubData = new byte[32];
        for (int i = 0; i < 32; i++) {
            privData[i] = (byte) (i * 7 + 3);
            pubData[i] = (byte) (i * 11 + 5);
        }
        privData[0] &= 0xf8;
        privData[31] &= 0x7f;
        privData[31] |= 0x40;
        pubData[31] &= 0x7f;

        PrivateKey priv = new PrivateKey(TYPE, privData);
        PublicKey pub = new PublicKey(TYPE, pubData);
        SessionKey sk = X25519DH.dh(priv, pub);
        assertNotNull(sk);
        assertEquals(32, sk.getData().length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWrongKeyTypePriv() {
        PrivateKey priv = new PrivateKey(EncType.ECIES_X25519, new byte[32]);
        // Use ElGamal type for the public key
        PublicKey pub = new PublicKey(EncType.ELGAMAL_2048, new byte[256]);
        X25519DH.dh(priv, pub);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWrongKeyTypePub() {
        // Use ElGamal type for the private key
        PrivateKey priv = new PrivateKey(EncType.ELGAMAL_2048, new byte[256]);
        PublicKey pub = new PublicKey(EncType.ECIES_X25519, new byte[32]);
        X25519DH.dh(priv, pub);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHighBitSetInPubKey() {
        byte[] privData = new byte[32];
        byte[] pubData = new byte[32];
        privData[0] = 0x09;
        pubData[0] = 0x09;
        // Set high bit in public key byte 31
        pubData[31] = (byte) 0x80;
        PrivateKey priv = new PrivateKey(TYPE, privData);
        PublicKey pub = new PublicKey(TYPE, pubData);
        X25519DH.dh(priv, pub);
    }

    @Test
    public void testHighBitClearedInPubKey() {
        byte[] privData = new byte[32];
        byte[] pubData = new byte[32];
        privData[0] = 0x09;
        pubData[0] = 0x09;
        // Explicitly clear high bit
        pubData[31] &= 0x7f;
        PrivateKey priv = new PrivateKey(TYPE, privData);
        PublicKey pub = new PublicKey(TYPE, pubData);
        SessionKey sk = X25519DH.dh(priv, pub);
        assertNotNull(sk);
        assertEquals(32, sk.getData().length);
    }
}
