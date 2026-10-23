package net.i2p.crypto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * The signing path of TrustedUpdate. DSAEngine.sign() reports failure by
 * returning null - a null key, or an I/O error part-way through hashing the
 * stream - and sign() used to write signature.getData() without checking, so a
 * method documented to "return null on error" threw NullPointerException instead.
 *
 * @since 0.9.72+
 */
public class TrustedUpdateSignTest {

    private static I2PAppContext ctx;

    @BeforeClass
    public static void setUpClass() {
        ctx = I2PAppContext.getGlobalContext();
    }

    /**
     * A small payload file to sign.
     *
     * @param contents the exact text to write into the file, as UTF-8
     * @return a temp file holding contents, marked deleteOnExit
     * @throws IOException if the temp file cannot be created or written
     */
    private static File tempFile(String contents) throws IOException {
        File f = File.createTempFile("tu-sign-", ".dat");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    /**
     * The file the signature is written to, next to the payload.
     *
     * @param in the payload file the signed copy is named after
     * @return in's absolute path with .signed appended, marked deleteOnExit
     */
    private static File outFileFor(File in) {
        File out = new File(in.getAbsolutePath() + ".signed");
        out.deleteOnExit();
        return out;
    }

    /**
     * A fresh DSA-SHA1 keypair, public key first, as generateSigningKeypair() returns it.
     *
     * @return a two-element array: the SigningPublicKey, then the SigningPrivateKey
     */
    private static Object[] keypair() {
        return KeyGenerator.getInstance().generateSigningKeypair();
    }

    /**
     * The crash: a null private key makes DSAEngine.sign() return null, and the
     * write that followed dereferenced it. Must return null, not throw.
     *
     * @throws IOException if the payload temp file cannot be written
     */
    @Test
    public void testNullKeyReturnsNullNotNpe() throws IOException {
        File in = tempFile("payload");
        File out = outFileFor(in);
        assertNull("a null signing key must return null, not throw NullPointerException",
                   new TrustedUpdate(ctx).sign(in.getAbsolutePath(), out.getAbsolutePath(), (SigningPrivateKey) null, "1.0"));
    }

    /** A missing input file is an error result, not an exception. */
    @Test
    public void testMissingInputFileReturnsNull() {
        Object[] keys = keypair();
        assertNull(new TrustedUpdate(ctx).sign("/nonexistent/i2p-update-payload",
                                               "/tmp/opencode/tu-missing-out.signed",
                                               (SigningPrivateKey) keys[1], "1.0"));
    }

    /**
     * A real keypair signs, and the result verifies against the matching public key.
     *
     * @throws IOException if the payload temp file cannot be written
     */
    @Test
    public void testSignThenVerifyRoundTrip() throws IOException {
        File in = tempFile("the update payload");
        File out = outFileFor(in);
        Object[] keys = keypair();

        Signature sig = new TrustedUpdate(ctx).sign(in.getAbsolutePath(), out.getAbsolutePath(),
                                                    (SigningPrivateKey) keys[1], "1.0");
        assertNotNull("a valid key must produce a signature", sig);
        assertTrue("the signed file must verify against the signer's public key",
                   new TrustedUpdate(ctx).verify(out, (SigningPublicKey) keys[0]));
    }

    /**
     * Appending to the signed file breaks verification.
     *
     * @throws IOException if the payload temp file or the append to the signed file fails
     */
    @Test
    public void testTamperedSignedFileFailsVerify() throws IOException {
        File in = tempFile("the update payload");
        File out = outFileFor(in);
        Object[] keys = keypair();

        assertNotNull(new TrustedUpdate(ctx).sign(in.getAbsolutePath(), out.getAbsolutePath(),
                                                  (SigningPrivateKey) keys[1], "1.0"));
        try (FileOutputStream fo = new FileOutputStream(out, true)) {
            fo.write("appended".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(new TrustedUpdate(ctx).verify(out, (SigningPublicKey) keys[0]));
    }

    /**
     * An over-long version is truncated as documented, and still round-trips.
     *
     * @throws IOException if the payload temp file cannot be written
     */
    @Test
    public void testLongVersionIsTruncatedAndStillVerifies() throws IOException {
        File in = tempFile("payload");
        File out = outFileFor(in);
        Object[] keys = keypair();

        Signature sig = new TrustedUpdate(ctx).sign(in.getAbsolutePath(), out.getAbsolutePath(),
                                                    (SigningPrivateKey) keys[1],
                                                    "1.0.0.0-this-version-is-far-too-long");
        assertNotNull(sig);
        assertTrue(new TrustedUpdate(ctx).verify(out, (SigningPublicKey) keys[0]));
    }

    /**
     * A failed sign must not leave a half-written signed file behind.
     *
     * @throws IOException if the payload temp file cannot be written
     */
    @Test
    public void testFailedSignWritesNoFile() throws IOException {
        File in = tempFile("payload");
        File out = outFileFor(in);
        assertNull(new TrustedUpdate(ctx).sign(in.getAbsolutePath(), out.getAbsolutePath(), (SigningPrivateKey) null, "1.0"));
        assertFalse("no signed file may be created when signing failed", out.exists());
    }
}
