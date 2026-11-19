package i2p.susi.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What a sealed cache file is, and what it refuses to open.
 *
 * <p>The defect: every message the webmail reads from the server was written to the
 * router's config directory gzip-compressed and nothing else, so anyone who could read
 * that directory - a backup, a live-CD, a forensic image - recovered the whole mailbox
 * in clear. Encryption only counts if the reader cannot get around it, so these cases
 * pin the round trip, the bytes actually on disk, and the three ways a blob can be
 * moved or edited that the authentication tag is there to catch.
 *
 * @since 0.9.72+
 */
public class SealedFileBufferTest {

    /** Text that must survive the round trip and must not appear on disk. */
    private static final String MARKER = "SUBJECT-MARKER the operator's mail BODY-MARKER";
    /** Key derived once for the class, PBKDF2 being what it costs. */
    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
    /** A second key, to stand for another account or another password. */
    private static final SecretKey OTHER = otherKey();

    /** Scratch directories for the files under test. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for the sealed cache file format; it holds no state.
     *
     * @since 0.9.72+
     */
    public SealedFileBufferTest() {}

    /**
     * @return a 32-byte key differing from KEY in the last byte
     */
    private static SecretKey otherKey() {
        byte[] raw = new byte[32];
        raw[31] = 1;
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * @param f the file to write
     * @param key the key to seal with
     * @throws IOException if the file cannot be written
     */
    private static void seal(File f, SecretKey key) throws IOException {
        SealedFileBuffer b = new SealedFileBuffer(f, key);
        OutputStream out = b.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        b.writeComplete(true);
    }

    /**
     * @param f the file to read
     * @param key the key the file was sealed with
     * @return the plaintext it holds
     * @throws IOException if the file cannot be opened or does not authenticate
     */
    private static String unseal(File f, SecretKey key) throws IOException {
        SealedFileBuffer b = new SealedFileBuffer(f, key);
        try (InputStream in = b.getInputStream();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            b.readComplete(true);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * A message written sealed reads back exactly as it was written.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testASealedFileRoundTrips() throws IOException {
        File f = folder.newFile("mail-1.full.txt.gz");
        seal(f, KEY);
        assertEquals(MARKER, unseal(f, KEY));
    }

    /**
     * The defect: the bytes on disk hold neither the subject nor the body.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testTheBytesOnDiskHoldNoPlaintext() throws IOException {
        File f = folder.newFile("mail-2.full.txt.gz");
        seal(f, KEY);
        byte[] raw = Files.readAllBytes(f.toPath());
        String onDisk = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse("the subject is readable on disk", onDisk.contains("SUBJECT-MARKER"));
        assertFalse("the body is readable on disk", onDisk.contains("BODY-MARKER"));
        assertFalse("the mail is still plain gzip on disk",
                    raw.length > 2 && raw[0] == 0x1f && raw[1] == (byte) 0x8b);
    }

    /**
     * A reader with no key and a working gunzip gets nothing: the file is not a gzip
     * file any more, which is what the older format was.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testGunzipAloneCannotReadASealedFile() throws IOException {
        File f = folder.newFile("mail-9.full.txt.gz");
        seal(f, KEY);
        try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(f))) {
            byte[] chunk = new byte[64];
            while (in.read(chunk) > 0) {
                // drained
            }
            fail("the file decompressed with no key");
        } catch (ZipException expected) {
            // not gzip
        }
    }

    /**
     * A file written by the older gzip-only buffer is recognised as not sealed, so it
     * can still be read the way it was written, and a sealed file is recognised as one.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testALegacyGzipFileIsRecognisedAsNotSealed() throws IOException {
        File legacy = folder.newFile("mail-3.full.txt.gz");
        GzipFileBuffer gb = new GzipFileBuffer(legacy);
        OutputStream out = gb.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        gb.writeComplete(true);
        assertFalse(SealedFileBuffer.isSealed(legacy));
        File sealed = folder.newFile("mail-4.full.txt.gz");
        seal(sealed, KEY);
        assertTrue(SealedFileBuffer.isSealed(sealed));
        assertFalse(SealedFileBuffer.isSealed(null));
        assertFalse(SealedFileBuffer.isSealed(new File(legacy.getParentFile(), "not-there")));
    }

    /**
     * The key is not interchangeable: a file sealed by one account cannot be opened by
     * another, and the failure is an IOException, not a garbled message.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testTheWrongKeyFailsInsteadOfReturningGarbage() throws IOException {
        File f = folder.newFile("mail-5.full.txt.gz");
        seal(f, KEY);
        try {
            unseal(f, OTHER);
            fail("a different key opened the file");
        } catch (IOException expected) {
            // the tag did not match
        }
    }

    /**
     * The blob is bound to its file name, so renaming a sealed message - into another
     * account's cache, or onto another uidl - is a failure, not a different message.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testARenamedSealedFileDoesNotOpen() throws IOException {
        File f = folder.newFile("mail-6.full.txt.gz");
        seal(f, KEY);
        File moved = new File(f.getParentFile(), "mail-other.full.txt.gz");
        assertTrue(f.renameTo(moved));
        try {
            unseal(moved, KEY);
            fail("a renamed file opened");
        } catch (IOException expected) {
            // the name is part of the authenticated data
        }
    }

    /**
     * A blob written under a temporary name but bound to the name it will end up with,
     * which is how a legacy cache file is re-sealed, opens after the rename.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testABlobBoundToItsFinalNameOpensAfterTheRename() throws IOException {
        File tmp = folder.newFile("mail-8.full.txt.gz.sealing");
        SealedFileBuffer b = new SealedFileBuffer(tmp, KEY, "mail-8.full.txt.gz");
        OutputStream out = b.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        b.writeComplete(true);
        File moved = new File(tmp.getParentFile(), "mail-8.full.txt.gz");
        assertTrue(tmp.renameTo(moved));
        assertEquals(MARKER, unseal(moved, KEY));
    }

    /**
     * A byte changed on disk is caught by the tag rather than served as a different
     * message.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testAnEditedSealedFileDoesNotOpen() throws IOException {
        File f = folder.newFile("mail-7.full.txt.gz");
        seal(f, KEY);
        byte[] raw = Files.readAllBytes(f.toPath());
        // past the magic and the IV, inside the ciphertext
        raw[20] ^= 1;
        Files.write(f.toPath(), raw);
        try {
            unseal(f, KEY);
            fail("an edited file opened");
        } catch (IOException expected) {
            // the tag did not match
        }
    }

    /**
     * The salt is the length the derivation expects, and two calls differ.
     */
    @Test
    public void testANewSaltIsRandomAndTheRightLength() {
        byte[] a = SealedFileBuffer.newSalt();
        byte[] b = SealedFileBuffer.newSalt();
        assertEquals(SealedFileBuffer.SALT_LENGTH, a.length);
        assertFalse(Arrays.equals(a, b));
    }

    /**
     * The same password and salt give the same key, so a mailbox opens the next day;
     * a different salt gives a different one, so the same password in another account
     * is not the same secret.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testTheKeyDependsOnThePasswordAndTheSalt() throws IOException {
        byte[] salt = SealedFileBuffer.newSalt();
        SecretKey first = SealedFileBuffer.deriveKey("hunter2", salt);
        SecretKey again = SealedFileBuffer.deriveKey("hunter2", salt);
        assertArrayEquals(first.getEncoded(), again.getEncoded());
        assertEquals(SealedFileBuffer.KEY_BITS / 8, first.getEncoded().length);
        byte[] otherSalt = SealedFileBuffer.newSalt();
        SecretKey other = SealedFileBuffer.deriveKey("hunter2", otherSalt);
        assertNotEquals(Arrays.toString(first.getEncoded()), Arrays.toString(other.getEncoded()));
    }

    /**
     * An empty password cannot key a cache, and says so rather than deriving a key
     * from nothing.
     * @throws IOException if the fixture file cannot be written or read
     */
    @Test
    public void testAnEmptyPasswordHasNoCacheKey() throws IOException {
        byte[] salt = SealedFileBuffer.newSalt();
        try {
            SealedFileBuffer.deriveKey("", salt);
            fail("an empty password derived a key");
        } catch (IllegalArgumentException expected) {
            // no secret to derive from
        }
    }
}
