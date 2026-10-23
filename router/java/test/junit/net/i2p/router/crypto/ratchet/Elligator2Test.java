package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PublicKey;

import org.junit.Test;

/**
 *  Tests for Elligator2 encoding/decoding of X25519 public keys.
 */
public class Elligator2Test {

    private static final I2PAppContext ctx = I2PAppContext.getGlobalContext();

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
     * Encode a key, retrying if encode returns null (happens when
     * Legendre symbol is -1, ~50% of keys).
     *
     * @param e2 the Elligator2 encoder to use
     * @param pub the X25519 public key to encode
     * @return the 32-byte representative, or null if all 20 attempts failed
     */
    private byte[] encodeWithRetry(Elligator2 e2, PublicKey pub) {
        for (int i = 0; i < 20; i++) {
            byte[] enc = e2.encode(pub);
            if (enc != null) return enc;
        }
        return null;
    }

    @Test
    public void testDecodeEncodeRoundTrip() throws Exception {
        KeyGenerator kg = ctx.keyGenerator();
        Elligator2 e2 = new Elligator2(ctx);

        for (int i = 0; i < 10; i++) {
            KeyPair kp = kg.generatePKIKeys(EncType.ECIES_X25519);
            PublicKey pub = kp.getPublic();
            byte[] encoded = encodeWithRetry(e2, pub);
            if (encoded == null) continue;
            assertEquals(32, encoded.length);
            PublicKey decoded = Elligator2.decode(encoded);
            assertNotNull(decoded);
            assertKeysEqual(pub, decoded);
            return;
        }
        fail("Could not encode any of 10 generated keys");
    }

    @Test
    public void testDecodeAlternative() throws Exception {
        KeyGenerator kg = ctx.keyGenerator();
        Elligator2 e2 = new Elligator2(ctx);

        for (int i = 0; i < 10; i++) {
            KeyPair kp = kg.generatePKIKeys(EncType.ECIES_X25519);
            PublicKey pub = kp.getPublic();
            byte[] encoded = encodeWithRetry(e2, pub);
            if (encoded == null) continue;
            java.util.concurrent.atomic.AtomicBoolean alt = new java.util.concurrent.atomic.AtomicBoolean();
            PublicKey decoded = Elligator2.decode(alt, encoded);
            assertNotNull(decoded);
            assertKeysEqual(pub, decoded);
            return;
        }
        fail("Could not encode any of 10 generated keys");
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
