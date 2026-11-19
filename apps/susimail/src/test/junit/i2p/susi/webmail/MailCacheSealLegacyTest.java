package i2p.susi.webmail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
 * Moving an existing mailbox out of plaintext.
 *
 * <p>The defect: the cache files an earlier release wrote are plain gzip, and an
 * upgrade that only seals what it writes from then on leaves everything already on disk
 * readable. These cases pin the pass that re-seals them: it has to be idempotent, so an
 * interrupted one resumes rather than sealing a file twice, and it has to leave the
 * message readable afterwards, byte for byte.
 *
 * @since 0.9.72+
 */
public class MailCacheSealLegacyTest {

    /** The message body written into the fixture cache files. */
    private static final String MARKER = "Subject: SUBJECT-MARKER\r\n\r\nthe body BODY-MARKER\r\n";
    /** Key the fixtures are sealed with. */
    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
    /** Log the re-seal pass reports through. */
    private static final Log LOG = I2PAppContext.getGlobalContext().logManager()
                                 .getLog(PersistentMailCache.class);

    /** Scratch cache directories. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for the cache re-seal pass; it holds no state.
     *
     * @since 0.9.72+
     */
    public MailCacheSealLegacyTest() {}

    /**
     * The salt is created once and stays: a mailbox that changed its salt between
     * logins would open with a key that cannot read its own cache.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testTheSaltIsWrittenOnceAndReadBack() throws IOException {
        File dir = folder.newFolder("account-1");
        byte[] first = PersistentMailCache.readOrCreateSalt(dir);
        assertEquals(SealedFileBuffer.SALT_LENGTH, first.length);
        assertTrue(new File(dir, "salt").canRead());
        byte[] again = PersistentMailCache.readOrCreateSalt(dir);
        assertArrayEquals(first, again);
    }

    /**
     * A different account gets a different salt, so the same password in two accounts
     * is not the same key.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testEachAccountGetsItsOwnSalt() throws IOException {
        byte[] a = PersistentMailCache.readOrCreateSalt(folder.newFolder("account-2"));
        byte[] b = PersistentMailCache.readOrCreateSalt(folder.newFolder("account-3"));
        assertNotEquals(Arrays.toString(a), Arrays.toString(b));
    }

    /**
     * A salt file that was truncated or edited is replaced rather than used, because a
     * short salt would derive a key nothing can read the cache with.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testACorruptSaltIsReplaced() throws IOException {
        File dir = folder.newFolder("account-4");
        PersistentMailCache.readOrCreateSalt(dir);
        File salt = new File(dir, "salt");
        Files.write(salt.toPath(), "not base64 at all".getBytes(StandardCharsets.US_ASCII));
        byte[] replacement = PersistentMailCache.readOrCreateSalt(dir);
        assertEquals(SealedFileBuffer.SALT_LENGTH, replacement.length);
        assertArrayEquals(replacement, PersistentMailCache.readOrCreateSalt(dir));
    }

    /**
     * The defect: a plain gzip cache file is left readable after the upgrade unless
     * something re-seals it. After the pass it is sealed, holds no plaintext on disk,
     * and still yields the same message.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testALegacyCacheFileIsReSealed() throws IOException {
        File dir = folder.newFolder("cur");
        File sub = new File(dir, "sA");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-abc.full.txt.gz");
        writeLegacy(f);
        assertFalse(SealedFileBuffer.isSealed(f));
        assertEquals(1, PersistentMailCache.sealLegacy(dir, KEY, LOG));
        assertTrue(SealedFileBuffer.isSealed(f));
        assertEquals(MARKER, unseal(f));
        String onDisk = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("the re-sealed file still shows the subject",
                    onDisk.contains("SUBJECT-MARKER"));
    }

    /**
     * A second pass seals nothing, which is what makes an interrupted pass resume
     * instead of sealing a file twice and double-compressing it.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testSealingTwiceDoesNotSealTwice() throws IOException {
        File dir = folder.newFolder("cur2");
        File sub = new File(dir, "sB");
        assertTrue(sub.mkdir());
        File f = new File(sub, "mail-def.full.txt.gz");
        writeLegacy(f);
        assertEquals(1, PersistentMailCache.sealLegacy(dir, KEY, LOG));
        assertEquals(0, PersistentMailCache.sealLegacy(dir, KEY, LOG));
        assertEquals(MARKER, unseal(f));
    }

    /**
     * Only cache files are touched: the salt and a stray file beside them stay as
     * they are.
     * @throws IOException if a fixture directory or file cannot be created
     */
    @Test
    public void testOnlyCacheFilesAreSealed() throws IOException {
        File dir = folder.newFolder("cur3");
        File sub = new File(dir, "sC");
        assertTrue(sub.mkdir());
        File stray = new File(sub, "notes.txt");
        Files.write(stray.toPath(), MARKER.getBytes(StandardCharsets.UTF_8));
        PersistentMailCache.readOrCreateSalt(dir);
        assertEquals(0, PersistentMailCache.sealLegacy(dir, KEY, LOG));
        assertTrue(new File(dir, "salt").canRead());
        assertEquals(MARKER, new String(Files.readAllBytes(stray.toPath()),
                                        StandardCharsets.UTF_8));
    }

    /**
     * The defect end to end: a message saved to the cache is written sealed, reads
     * back through the same cache, and still reads back through a second one, which is
     * what a later login builds.
     *
     * @throws IOException if the fixture cache directory cannot be created
     */
    @Test
    public void testTheCacheWritesSealedFilesAndReadsThemBack() throws IOException {
        File cfg = folder.newFolder("config-dir");
        String saved = System.getProperty("i2p.dir.config");
        System.setProperty("i2p.dir.config", cfg.getAbsolutePath());
        try {
            I2PAppContext ctx = new I2PAppContext();
            byte[] raw = MARKER.getBytes(StandardCharsets.UTF_8);
            PersistentMailCache pmc = new PersistentMailCache(ctx, "mail.example", 123,
                                                      "user", "pass", WebMail.DIR_FOLDER);
            Mail mail = new Mail("uidl-1");
            mail.setBody(new ReadBuffer(raw, 0, raw.length));
            assertTrue(pmc.saveMail(mail));
            File f = pmc.getFullFile("uidl-1");
            assertTrue("the cache file was written in the clear", SealedFileBuffer.isSealed(f));
            Mail again = new Mail("uidl-1");
            again.setBody(pmc.getFullBuffer("uidl-1"));
            assertTrue("the sealed message did not read back", again.hasBody());
            PersistentMailCache next = new PersistentMailCache(ctx, "mail.example", 123,
                                                     "user", "pass", WebMail.DIR_FOLDER);
            Mail later = new Mail("uidl-1");
            assertTrue(next.getMail(later, false));
            assertTrue("a second cache could not open the message", later.hasBody());
        } finally {
            if (saved != null)
                System.setProperty("i2p.dir.config", saved);
        }
    }

    /**
     * @param f the file to write as an earlier release would have
     * @throws IOException if it cannot be written
     */
    private static void writeLegacy(File f) throws IOException {
        GzipFileBuffer gb = new GzipFileBuffer(f);
        OutputStream out = gb.getOutputStream();
        out.write(MARKER.getBytes(StandardCharsets.UTF_8));
        out.close();
        gb.writeComplete(true);
    }

    /**
     * @param f the sealed file to read
     * @return the message it holds
     * @throws IOException if it cannot be opened or does not authenticate
     */
    private static String unseal(File f) throws IOException {
        Buffer b = new SealedFileBuffer(f, KEY);
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
