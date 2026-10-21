package net.i2p.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

/**
 * Round trip plus the small-subgroup / out-of-range y rejection added to
 * decrypt(): y must lie strictly between 1 and p-1 before any DH work,
 * closing the static-key parity oracle of y = p-1.
 */
public class ElGamalEngineTest {

    private final I2PAppContext ctx = I2PAppContext.getGlobalContext();

    private static final int HALF = 257;
    private static final int KEY_BYTES = 256;

    /** Big-endian, zero-padded (or trimmed) to exactly len bytes. */
    private static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[len];
        if (b.length > len)
            System.arraycopy(b, b.length - len, out, 0, len);
        else
            System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    private Object[] makeKeys() {
        // 2047 bits keeps a < p-1 without needing a p-1 constant
        BigInteger a = new BigInteger(2047, ctx.random());
        BigInteger y = CryptoConstants.elgg.modPow(a, CryptoConstants.elgp);
        PrivateKey priv = new PrivateKey(EncType.ELGAMAL_2048, toFixed(a, KEY_BYTES));
        PublicKey pub = new PublicKey(EncType.ELGAMAL_2048, toFixed(y, KEY_BYTES));
        return new Object[]{pub, priv};
    }

    private byte[] encryptTo(Object[] keys, String text) {
        return ctx.elGamalEngine().encrypt(DataHelper.getUTF8(text), (PublicKey) keys[0]);
    }

    @Test
    public void testRoundTrip() {
        Object[] keys = makeKeys();
        byte[] data = DataHelper.getUTF8("hello elgamal round trip");
        byte[] enc = ctx.elGamalEngine().encrypt(data, (PublicKey) keys[0]);
        byte[] dec = ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]);
        assertArrayEquals(data, dec);
    }

    /** y = 0 is rejected. */
    @Test
    public void testRejectYZero() {
        Object[] keys = makeKeys();
        byte[] enc = encryptTo(keys, "x");
        Arrays.fill(enc, 0, HALF, (byte) 0);
        assertNull(ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]));
    }

    /** y = 1 is rejected. */
    @Test
    public void testRejectYOne() {
        Object[] keys = makeKeys();
        byte[] enc = encryptTo(keys, "x");
        Arrays.fill(enc, 0, HALF, (byte) 0);
        enc[HALF - 1] = 1;
        assertNull(ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]));
    }

    /** y = p-1 (order 2, the parity oracle) is rejected. */
    @Test
    public void testRejectYPm1() {
        Object[] keys = makeKeys();
        byte[] enc = encryptTo(keys, "x");
        // elgp is 256 bits, so p-1 serializes to 257 bytes with a leading zero
        byte[] pm1 = CryptoConstants.elgp.subtract(BigInteger.ONE).toByteArray();
        assertEquals(HALF, pm1.length);
        System.arraycopy(pm1, 0, enc, 0, HALF);
        assertNull(ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]));
    }

    /** y = p exactly (out of range) is rejected. */
    @Test
    public void testRejectYGeP() {
        Object[] keys = makeKeys();
        byte[] enc = encryptTo(keys, "x");
        byte[] p = CryptoConstants.elgp.toByteArray();
        assertEquals(HALF, p.length);
        System.arraycopy(p, 0, enc, 0, HALF);
        assertNull(ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]));
    }

    /** A random tamper of the y half still decrypts to null, no exception. */
    @Test
    public void testTamperedYStillNull() {
        Object[] keys = makeKeys();
        byte[] enc = encryptTo(keys, "tamper me");
        enc[128] ^= (byte) 0xFF;
        assertNull(ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]));
    }

    /**
     * encrypt() permits an empty payload, so the smallest well-formed masked
     * value is 1 + 32 bytes and decrypt() must return a zero-length array
     * rather than null. Pins payloadLen == 0 as the boundary, which is the
     * smallest value the guard in decrypt() accepts.
     *
     * @since 0.9.72+
     */
    @Test
    public void testEmptyPayloadDecryptsToZeroLength() {
        Object[] keys = makeKeys();
        byte[] enc = ctx.elGamalEngine().encrypt(new byte[0], (PublicKey) keys[0]);
        byte[] dec = ctx.elGamalEngine().decrypt(enc, (PrivateKey) keys[1]);
        assertNotNull(dec);
        assertEquals(0, dec.length);
    }
}
