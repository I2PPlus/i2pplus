package net.i2p.crypto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * A DSA signature with s == 0 mod q has no inverse, so modInverse() throws
 * ArithmeticException. Such a signature is trivially forgeable, so the path is
 * reachable at will by anything that can deliver a signature - it must return
 * false rather than propagate, and it must stay cheap when hit repeatedly.
 *
 * @since 0.9.72+
 */
public class DSAEngineZeroSSignatureTest {

    private static final I2PAppContext ctx = I2PAppContext.getGlobalContext();

    /**
     * A fresh Signature whose s half (the second 20 bytes) is zeroed.
     *
     * @param sig the signature to copy and tamper with
     * @return a new signature of the same type with the s half zeroed
     */
    private static Signature zeroS(Signature sig) {
        return tamper(sig, true);
    }

    /**
     * A fresh Signature whose r half (the first 20 bytes) is zeroed.
     *
     * @param sig the signature to copy and tamper with
     * @return a new signature of the same type with the r half zeroed
     */
    private static Signature zeroR(Signature sig) {
        return tamper(sig, false);
    }

    /**
     * SimpleDataStructure.setData() refuses to overwrite, so the tampered copy
     * has to be a new Signature over the same type.
     *
     * @param sig the signature to copy and tamper with
     * @param zeroSecondHalf true to zero the s half (the second 20 bytes),
     *                       false to zero the r half (the first 20 bytes)
     * @return a new Signature of the same type with the selected half zeroed
     */
    private static Signature tamper(Signature sig, boolean zeroSecondHalf) {
        byte[] data = sig.getData();
        byte[] bad = Arrays.copyOf(data, data.length);
        int half = bad.length / 2;
        if (zeroSecondHalf)
            Arrays.fill(bad, half, bad.length, (byte) 0);
        else
            Arrays.fill(bad, 0, half, (byte) 0);
        return new Signature(sig.getType(), bad);
    }

    private static Object[] newKeys() {
        return KeyGenerator.getInstance().generateSigningKeypair();
    }

    /** s == 0 must be rejected, not thrown. */
    @Test
    public void testZeroSReturnsFalse() {
        byte[] message = new byte[256];
        ctx.random().nextBytes(message);
        Object[] keys = newKeys();
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];

        Signature sig = DSAEngine.getInstance().sign(message, priv);
        assertTrue("the untampered signature must verify first",
                   DSAEngine.getInstance().verifySignature(sig, message, pub));

        assertFalse("s == 0 must fail verification, not propagate ArithmeticException",
                    DSAEngine.getInstance().verifySignature(zeroS(sig), message, pub));
    }

    /**
     * Repeated zero-s signatures must all return false. This is the path the
     * throttled warning sits on, so it also proves the throttle never throws.
     */
    @Test
    public void testRepeatedZeroSStaysSafe() {
        byte[] message = new byte[64];
        ctx.random().nextBytes(message);
        Object[] keys = newKeys();
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];

        Signature sig = DSAEngine.getInstance().sign(message, priv);
        Signature bad = zeroS(sig);
        for (int i = 0; i < 200; i++)
            assertFalse("iteration " + i,
                        DSAEngine.getInstance().verifySignature(bad, message, pub));
    }

    /** A valid signature still verifies after the guard was touched. */
    @Test
    public void testValidSignatureStillVerifies() {
        byte[] message = new byte[128];
        ctx.random().nextBytes(message);
        Object[] keys = newKeys();
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        Signature sig = DSAEngine.getInstance().sign(message, priv);
        assertTrue(DSAEngine.getInstance().verifySignature(sig, message, pub));
    }

    /** Zeroing r instead of s must also be rejected without throwing. */
    @Test
    public void testZeroRReturnsFalse() {
        byte[] message = new byte[64];
        ctx.random().nextBytes(message);
        Object[] keys = newKeys();
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];

        Signature sig = DSAEngine.getInstance().sign(message, priv);
        assertTrue(DSAEngine.getInstance().verifySignature(sig, message, pub));
        assertFalse(DSAEngine.getInstance().verifySignature(zeroR(sig), message, pub));
    }
}
