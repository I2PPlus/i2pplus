package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PublicKey;

import org.junit.Test;

/**
 * Tests for Elligator2 encoding/decoding of X25519 public keys.
 */
public class Elligator2Test {

    private static final I2PAppContext ctx = I2PAppContext.getGlobalContext();

    /**
     * Keys to try before giving up. About half of all X25519 keys are
     * Elligator-representable, so a test needing one encodable key fails with
     * probability one in two to the power of this count: ten keys left a
     * one-in-a-thousand flake in the suite, forty makes it unmeasurable, and a
     * key generation costs microseconds.
     */
    private static final int KEYS = 40;

    /**
     * Compare two X25519 public keys, ignoring the two high bits of byte 31
     * which are randomized during Elligator2 encoding.
     *
     * @param expected the reference key the caller wants to see
     * @param actual the decoded key produced by the code under test
     */
    private void assertKeysEqual(PublicKey expected, PublicKey actual) {
        byte[] eb = expected.getData().clone();
        byte[] ab = actual.getData().clone();
        eb[31] &= 0x3f;
        ab[31] &= 0x3f;
        assertArrayEquals(eb, ab);
    }

    /**
     * Encode one key.
     * <p>
     * Whether this succeeds is fixed by the key: encode() returns null when the
     * Legendre symbol of -u*x*(x+A) is -1, a property of x alone, and the random
     * bit it draws only picks which root to return. Retrying the same key
     * therefore cannot turn a failure into a success, so the loop that used to
     * call encode() 20 times per key was 20 identical calls. The key is the
     * retry dimension, and that is the loop in the tests.
     *
     * @param e2 the Elligator2 encoder to use
     * @param pub the X25519 public key to encode
     * @return the 32-byte representative, or null when this key is not
     *         representable (about half of all keys)
     */
    private byte[] encodeKey(Elligator2 e2, PublicKey pub) {
        return e2.encode(pub);
    }

    @Test
    public void testDecodeEncodeRoundTrip() throws Exception {
        KeyGenerator kg = ctx.keyGenerator();
        Elligator2 e2 = new Elligator2(ctx);

        for (int i = 0; i < KEYS; i++) {
            KeyPair kp = kg.generatePKIKeys(EncType.ECIES_X25519);
            PublicKey pub = kp.getPublic();
            byte[] encoded = encodeKey(e2, pub);
            if (encoded == null) continue;
            assertEquals(32, encoded.length);
            PublicKey decoded = Elligator2.decode(encoded);
            assertNotNull(decoded);
            assertKeysEqual(pub, decoded);
            return;
        }
        fail("Could not encode any of " + KEYS + " generated keys");
    }

    @Test
    public void testDecodeAlternative() throws Exception {
        KeyGenerator kg = ctx.keyGenerator();
        Elligator2 e2 = new Elligator2(ctx);

        for (int i = 0; i < KEYS; i++) {
            KeyPair kp = kg.generatePKIKeys(EncType.ECIES_X25519);
            PublicKey pub = kp.getPublic();
            byte[] encoded = encodeKey(e2, pub);
            if (encoded == null) continue;
            java.util.concurrent.atomic.AtomicBoolean alt = new java.util.concurrent.atomic.AtomicBoolean();
            PublicKey decoded = Elligator2.decode(alt, encoded);
            assertNotNull(decoded);
            assertKeysEqual(pub, decoded);
            return;
        }
        fail("Could not encode any of " + KEYS + " generated keys");
    }

    @Test
    public void testMultipleKeysEncodeDecode() throws Exception {
        KeyGenerator kg = ctx.keyGenerator();
        Elligator2 e2 = new Elligator2(ctx);

        int encoded = 0;
        for (int i = 0; i < 50 && encoded < 5; i++) {
            KeyPair kp = kg.generatePKIKeys(EncType.ECIES_X25519);
            PublicKey pub = kp.getPublic();
            byte[] enc = e2.encode(pub);
            if (enc == null) continue;
            assertEquals(32, enc.length);
            PublicKey decoded = Elligator2.decode(enc);
            assertKeysEqual(pub, decoded);
            encoded++;
        }
        assertTrue("Should have encoded at least 5 keys", encoded >= 5);
    }
}
