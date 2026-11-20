package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import net.i2p.crypto.EncType;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

/**
 * Tests {@link RatchetSKM#nextKeySecret}, the guard around the Diffie-Hellman
 * that turns a peer's next-session key into a shared secret. A NEXTKEY block
 * carries 32 raw bytes and nothing upstream checks that they are a curve point,
 * so the guard has to absorb what the math refuses without absorbing the good
 * keys beside them.
 *
 * <p>The helper is static and takes two keys, so no session, tag set or router
 * context is needed to pin it.
 *
 * @since 0.9.72+
 */
public class RatchetNextKeySecretTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public RatchetNextKeySecretTest() {}

    /** A private key with the clamping a Curve25519 scalar carries. */
    private static final byte[] PRIV_BYTES = clamp(fill((byte) 0x5a));

    /**
     * A private key value, clamped the way an X25519 private key is.
     *
     * @param b the bytes to fill with
     * @return a fresh 32-byte array
     */
    private static byte[] fill(byte b) {
        byte[] rv = new byte[32];
        java.util.Arrays.fill(rv, b);
        return rv;
    }

    /**
     * Clears the low three bits and the top bit, as RFC 7748 5 requires of a scalar.
     *
     * @param b the bytes to clamp, modified in place
     * @return the same array
     */
    private static byte[] clamp(byte[] b) {
        b[0] &= (byte) 0xf8;
        b[31] &= 0x7f;
        return b;
    }

    /**
     * Our half of the exchange.
     *
     * @return a private key the ratchet would hold
     */
    private static PrivateKey priv() {
        return new PrivateKey(EncType.ECIES_X25519, PRIV_BYTES);
    }

    /**
     * A peer's next-session key made of one repeated byte.
     *
     * @param b the byte to fill the 32 bytes with
     * @return a public key as the ratchet receives one
     */
    private static PublicKey pub(byte b) {
        return new PublicKey(EncType.ECIES_X25519, fill(b));
    }

    /**
     * A usable key still yields the same secret the unguarded call yields, so the
     * guard costs the happy path nothing.
     */
    @Test
    public void testGoodKeyYieldsTheSameSecret() {
        PublicKey good = pub((byte) 0x11);
        PrivateKey secret = RatchetSKM.nextKeySecret(priv(), good);
        assertNotNull(secret);
        assertArrayEquals(ECIESAEADEngine.doDH(priv(), good).getData(), secret.getData());
    }

    /**
     * The all-zero point: the math throws at it, and the guard turns that into null.
     */
    @Test
    public void testZeroPointReturnsNull() {
        assertNull(RatchetSKM.nextKeySecret(priv(), pub((byte) 0)));
    }

    /**
     * The point 1, the cheapest one a peer can put in a NEXTKEY block.
     */
    @Test
    public void testOnePointReturnsNull() {
        byte[] data = fill((byte) 0);
        data[0] = 1;
        assertNull(RatchetSKM.nextKeySecret(priv(), new PublicKey(EncType.ECIES_X25519, data)));
    }

    /**
     * The field prime itself, which is zero in the field and refused the same way.
     */
    @Test
    public void testFieldPrimePointReturnsNull() {
        byte[] data = fill((byte) 0xff);
        data[0] = (byte) 0xed;
        data[31] = 0x7f;
        assertNull(RatchetSKM.nextKeySecret(priv(), new PublicKey(EncType.ECIES_X25519, data)));
    }

    /**
     * A value with the top bit set is not a low-order point - the math reduces it
     * into the field - so the guard must not refuse it. Rejecting those would drop
     * real peers, whose keys are random 32 bytes and carry the top bit half the time.
     */
    @Test
    public void testHighBitValueStillYieldsASecret() {
        byte[] data = fill((byte) 0xff);
        assertNotNull(RatchetSKM.nextKeySecret(priv(), new PublicKey(EncType.ECIES_X25519, data)));
    }

    /**
     * Every accepted secret is a full 32-byte shared secret, so a caller that hands
     * it to the key derivation never sees a short or empty value.
     */
    @Test
    public void testAcceptedSecretIsFullLength() {
        for (byte b = 2; b < 0x7f; b = (byte) (b + 7)) {
            PrivateKey secret = RatchetSKM.nextKeySecret(priv(), pub(b));
            if (secret != null)
                assertEquals(32, secret.getData().length);
        }
    }
}
