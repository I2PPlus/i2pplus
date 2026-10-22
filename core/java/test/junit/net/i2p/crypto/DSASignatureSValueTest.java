package net.i2p.crypto;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * The s value of a DSA signature before it is inverted mod q (A-2). q is prime
 * and s is read out of 20 signature bytes, so s is below 2q and the only values
 * with no inverse are 0 and q itself. Both are attacker-supplied, and the
 * native implementation behind modInverse() can treat a non-coprime operand as a
 * divide-by-zero that ends the JVM rather than throwing the ArithmeticException
 * the verify path already handles.
 */
public class DSASignatureSValueTest {

    private static final byte[] DATA = "dsa s value range".getBytes();

    /**
     * s = 0 has no inverse mod q, so verification cannot proceed; the signature
     * is rejected and nothing else happens.
     */
    @Test
    public void testZeroSIsRejected() {
        assertFalse("a signature with s = 0 was accepted", verifyWithS(BigInteger.ZERO));
    }

    /**
     * s = q is the other multiple of q reachable from 20 bytes, and the one a
     * range check on the byte length alone would miss.
     */
    @Test
    public void testSEqualToQIsRejected() {
        assertFalse("a signature with s = q was accepted", verifyWithS(CryptoConstants.dsaq));
    }

    /**
     * A signature that is actually valid still verifies - the pre-check must
     * reject the two unusable values and nothing else.
     */
    @Test
    public void testARealSignatureStillVerifies() {
        Object[] pair = I2PAppContext.getGlobalContext().keyGenerator().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey) pair[0];
        SigningPrivateKey priv = (SigningPrivateKey) pair[1];
        DSAEngine engine = DSAEngine.getInstance();
        Signature sig = engine.sign(DATA, priv);
        assertTrue("a valid DSA signature stopped verifying",
                   engine.verifySignature(sig, DATA, pub));
    }

    /**
     * Verify a signature carrying the given s, with an arbitrary r and key -
     * neither is ever reached once s is ruled out.
     *
     * @param s the s value to place in the signature
     * @return what verifySignature() returned
     */
    private static boolean verifyWithS(BigInteger s) {
        byte[] sig = new byte[40];
        for (int i = 0; i < 20; i++) {
            sig[i] = (byte) (i + 1);
        }
        byte[] sbytes = s.toByteArray();
        // BigInteger is signed, so a high bit costs a leading zero byte; keep the
        // low 20 bytes, which is what the verify path reads back as unsigned.
        int off = sbytes.length - 20;
        System.arraycopy(sbytes, Math.max(off, 0), sig, 20, Math.min(sbytes.length, 20));
        byte[] y = new byte[128];
        for (int i = 0; i < y.length; i++) {
            y[i] = (byte) (i - 128);
        }
        Signature signature = new Signature(SigType.DSA_SHA1, sig);
        SigningPublicKey key = new SigningPublicKey(SigType.DSA_SHA1, y);
        return DSAEngine.getInstance().verifySignature(signature, DATA, key);
    }
}
