package i2p.susi.webmail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import i2p.susi.util.Buffer;
import i2p.susi.util.GzipFileBuffer;
import i2p.susi.util.ReadBuffer;
import i2p.susi.util.SealedFileBuffer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Carrying a mailbox across a change of the mailbox password.
 *
 * <p>The defect: the disk cache is sealed with a key derived from the password the
 * user types at login, so changing it on the server leaves every cached message
 * sealed under a key nothing holds any more. The folder loads empty, and where the
 * server deletes mail after fetching it there is nothing to re-fetch - the message
 * is simply gone from view. These cases pin the pass that re-seals the files with
 * the new key while the old one is still in memory, and in particular that it never
 * destroys a file it could not open.
 *
 * @since 0.9.72+
 */
public class MailCacheRekeyTest {

    /** The message body written into the fixture cache files. */
    private static final String MARKER = "Subject: SUBJECT-MARKER\r\n\r\nthe body BODY-MARKER\r\n";
    /** Key the fixtures start out sealed with. */
    private static final SecretKey OLD = new SecretKeySpec(new byte[32], "AES");
    /** Key the pass is asked to re-seal them under. */
    private static final SecretKey NEW = new SecretKeySpec(filled((byte) 0x5a), "AES");
    /** A key that was never used for these fixtures. */
    private static final SecretKey OTHER = new SecretKeySpec(filled((byte) 0xa5), "AES");
    /** Log the re-seal pass reports through. */
    private static final Log LOG = I2PAppContext.getGlobalContext().logManager()
                                 .getLog(PersistentMailCache.class);

    /** Scratch cache directories. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for the cache re-key pass; it holds no state.
     *
     * @since 0.9.72+
     */
    public MailCacheRekeyTest() {}

    /**
     * A 32-byte key value.
     *
     * @param b the byte to fill it with
     * @return a fresh array
     */
    private static byte[] filled(byte b) {
        byte[] rv = new byte[32];
        Arrays.fill(rv, b);
        return rv;
    }

    /**
     * The defect: a file sealed with the old password's key does not open with the new
     * one, which is what empties the mailbox at login. After the pass it opens with the
     * new key, no longer opens with the old one, and still holds the same message.
     *
     * @throws IOException if a fixture file cannot be written
     */
    @Test
    public void testACacheFileIsReSealedWithTheNewKey() throws IOException {
        File dir = folder.newFolder("acct-1");
        File sub = new File(dir, "cur");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-abc.full.txt.gz");
        seal(f, OLD);
        try {
            unseal(f, NEW);
            fail("the new key opened a file written with the old one");
        } catch (IOException expected) {
            // the tag failure this whole pass exists for
        }
        assertEquals(1, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertEquals(MARKER, unseal(f, NEW));
        try {
            unseal(f, OLD);
            fail("the old key still opens a file re-sealed with the new one");
        } catch (IOException expected) {
            // and it is really gone from the old key
        }
    }

    /**
     * A second pass re-seals nothing, which is what makes an interrupted one resume
     * rather than double-encrypt a file.
     *
     * @throws IOException if a fixture file cannot be written
     */
    @Test
    public void testRekeyingTwiceDoesNotSealTwice() throws IOException {
        File dir = folder.newFolder("acct-2");
        File sub = new File(dir, "cur");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-def.full.txt.gz");
        seal(f, OLD);
        assertEquals(1, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertEquals(0, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertEquals(MARKER, unseal(f, NEW));
    }

    /**
     * The pass must never destroy what it cannot open: a file sealed under some other
     * key is left byte for byte as it was, and is not counted. It may be the only copy
     * of that message.
     *
     * @throws IOException if a fixture file cannot be written
     */
    @Test
    public void testAFileItCannotOpenIsLeftAlone() throws IOException {
        File dir = folder.newFolder("acct-3");
        File sub = new File(dir, "cur");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-ghi.full.txt.gz");
        seal(f, OTHER);
        byte[] before = Files.readAllBytes(f.toPath());
        assertEquals(0, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertArrayEquals(before, Files.readAllBytes(f.toPath()));
        assertEquals(MARKER, unseal(f, OTHER));
        assertFalse("a half-written copy was left beside it",
                    new File(sub, "mail-ghi.full.txt.gz.sealing").exists());
    }

    /**
     * A plain gzip file an earlier release wrote is the legacy pass's job, not this
     * one's: it is left unsealed and uncounted.
     *
     * @throws IOException if a fixture file cannot be written
     */
    @Test
    public void testAPlainFileIsLeftToTheLegacyPass() throws IOException {
        File dir = folder.newFolder("acct-4");
        File sub = new File(dir, "cur");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-jkl.full.txt.gz");
        GzipFileBuffer gb = new GzipFileBuffer(f);
        OutputStream out = gb.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        gb.writeComplete(true);
        assertEquals(0, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertFalse(SealedFileBuffer.isSealed(f));
    }

    /**
     * Only cache files are touched: the salt and a stray file beside them stay as
     * they are, and every folder of the account is reached, not just the one whose
     * login triggered the pass.
     *
     * @throws IOException if a fixture file cannot be written
     */
    @Test
    public void testEveryFolderIsRekeyedAndNothingElse() throws IOException {
        File dir = folder.newFolder("acct-5");
        File cur = new File(dir, "cur");
        File trash = new File(dir, "Trash");
        assertTrue(cur.mkdir());
        assertTrue(trash.mkdir());
        File stray = new File(cur, "notes.txt");
        Files.write(stray.toPath(), MARKER.getBytes(StandardCharsets.UTF_8));
        PersistentMailCache.readOrCreateSalt(dir);
        File a = new File(cur, "mail-mno.full.txt.gz");
        File b = new File(trash, "mail-pqr.hdr.txt.gz");
        seal(a, OLD);
        seal(b, OLD);
        assertEquals(2, PersistentMailCache.rekey(dir, OLD, NEW, LOG));
        assertEquals(MARKER, unseal(a, NEW));
        assertEquals(MARKER, unseal(b, NEW));
        assertEquals(MARKER, new String(Files.readAllBytes(stray.toPath()),
                                        StandardCharsets.UTF_8));
        assertTrue(new File(dir, "salt").canRead());
    }

    /**
     * The defect end to end: a message cached under one password is still there after
     * the password changes, with no re-fetch and no server to re-fetch it from. The
     * previous key is handed over once, so the other four folders of the same login do
     * not walk the account again.
     *
     * @throws IOException if the fixture cache directory cannot be created
     */
    @Test
    public void testTheCacheSurvivesThePasswordChanging() throws IOException {
        File cfg = folder.newFolder("config-dir-2");
        String saved = System.getProperty("i2p.dir.config");
        System.setProperty("i2p.dir.config", cfg.getAbsolutePath());
        try {
            I2PAppContext ctx = new I2PAppContext();
            byte[] raw = MARKER.getBytes(StandardCharsets.UTF_8);
            PersistentMailCache before = new PersistentMailCache(ctx, "rekey.example", 995,
                                                        "user", "old-password", WebMail.DIR_FOLDER);
            Mail mail = new Mail("uidl-rekey");
            mail.setBody(new ReadBuffer(raw, 0, raw.length));
            assertTrue(before.saveMail(mail));
            File f = before.getFullFile("uidl-rekey");
            assertTrue(SealedFileBuffer.isSealed(f));
            PersistentMailCache after = new PersistentMailCache(ctx, "rekey.example", 995,
                                                       "user", "new-password", WebMail.DIR_FOLDER);
            Mail later = new Mail("uidl-rekey");
            assertTrue(after.getMail(later, false));
            assertTrue("the cache could not be opened with the new password", later.hasBody());
            assertNull(PersistentMailCache.takePreviousKey(
                       PersistentMailCache.accountName("rekey.example", 995, "user")));
        } finally {
            if (saved != null)
                System.setProperty("i2p.dir.config", saved);
        }
    }

    /**
     * @param f the file to write sealed
     * @param key the key to seal it with
     * @throws IOException if it cannot be written
     */
    private static void seal(File f, SecretKey key) throws IOException {
        Buffer b = new SealedFileBuffer(f, key);
        OutputStream out = b.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        b.writeComplete(true);
    }

    /**
     * @param f the sealed file to read
     * @param key the key to open it with
     * @return the message it holds
     * @throws IOException if it cannot be opened or does not authenticate
     */
    private static String unseal(File f, SecretKey key) throws IOException {
        Buffer b = new SealedFileBuffer(f, key);
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
}
