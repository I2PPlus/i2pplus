package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for how BandwidthGraph picks its persistence file. The shared name in the
 * tmp dir belongs to whichever router claims it first, and a router running as a
 * different user can read that file but never replace it — the sticky bit on a
 * world-writable tmp dir protects it — so the next name in the sequence has to be
 * taken instead. Also pins the one-line failure message that replaced the old
 * stack trace.
 *
 * @since 0.9.72+
 */
public class BandwidthGraphFileTest {

    /** Scratch dir standing in for the JVM tmp dir. */
    private File _dir;

    /**
     * Test instance; each test builds its own scratch dir, so the instance carries
     * no state between them.
     */
    public BandwidthGraphFileTest() {
    }

    /**
     * Create the scratch dir.
     */
    @Before
    public void setUp() {
        _dir = new File(System.getProperty("java.io.tmpdir"), "bwgraph-file-" + UUID.randomUUID());
        assertTrue(_dir.mkdirs());
    }

    /**
     * Make every scratch file writable again, then remove the dir.
     */
    @After
    public void tearDown() {
        File[] files = _dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.setWritable(true);
                f.delete();
            }
        }
        _dir.delete();
    }

    /**
     * Read a scratch file back as text.
     *
     * @param f the file to read
     * @return its contents, UTF-8
     * @throws IOException on read failure
     */
    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Claim one candidate name with a file we cannot rewrite, standing in for a
     * file owned by another user. Skips the test where permissions cannot be
     * taken away, i.e. when running as root.
     *
     * @param n which candidate name to claim, index-aligned with
     *          {@link BandwidthGraph#fileName(int)}
     * @return the file created, never null
     * @throws IOException on scratch file creation failure
     */
    private File claimUnwritable(int n) throws IOException {
        File f = new File(_dir, BandwidthGraph.fileName(n));
        assertTrue(f.createNewFile());
        assertTrue(f.setWritable(false));
        Assume.assumeFalse("running as root, cannot test permissions", f.canWrite());
        return f;
    }

    /**
     * The sequence is the shared name, then -1, -2 and so on.
     */
    @Test
    public void testFileNameSequence() {
        assertEquals("i2p-snark-bandwidth.dat", BandwidthGraph.fileName(0));
        assertEquals("i2p-snark-bandwidth.dat", BandwidthGraph.fileName(-1));
        assertEquals("i2p-snark-bandwidth-1.dat", BandwidthGraph.fileName(1));
        assertEquals("i2p-snark-bandwidth-12.dat", BandwidthGraph.fileName(12));
    }

    /**
     * A free tmp dir gives the shared name to the first router that starts.
     */
    @Test
    public void testTakesSharedNameWhenAbsent() {
        assertEquals(new File(_dir, "i2p-snark-bandwidth.dat"), BandwidthGraph.resolveFile(_dir));
    }

    /**
     * A file we may write is kept, not skipped, so a restart reloads and rewrites
     * its own history instead of opening a fresh file every time.
     *
     * @throws IOException on scratch file creation failure
     */
    @Test
    public void testKeepsSharedNameWhenWritable() throws IOException {
        File shared = new File(_dir, BandwidthGraph.fileName(0));
        assertTrue(shared.createNewFile());
        assertEquals(shared, BandwidthGraph.resolveFile(_dir));
    }

    /**
     * A shared name held by another user is skipped for the next one in the
     * sequence, which is the case that used to fail every five minutes.
     *
     * @throws IOException on scratch file creation failure
     */
    @Test
    public void testIncrementsPastUnwritableSharedName() throws IOException {
        claimUnwritable(0);
        assertEquals(new File(_dir, "i2p-snark-bandwidth-1.dat"), BandwidthGraph.resolveFile(_dir));
    }

    /**
     * Every claimed name is skipped in turn, so several routers running as
     * several users on one machine each get a file of their own.
     *
     * @throws IOException on scratch file creation failure
     */
    @Test
    public void testSkipsEveryClaimedName() throws IOException {
        claimUnwritable(0);
        claimUnwritable(1);
        claimUnwritable(2);
        assertEquals(new File(_dir, "i2p-snark-bandwidth-3.dat"), BandwidthGraph.resolveFile(_dir));
    }

    /**
     * Once the sequence is exhausted the shared name comes back, so the flush
     * reports one clear failure against a known file rather than nothing at all.
     *
     * @throws IOException on scratch file creation failure
     */
    @Test
    public void testFallsBackToSharedNameWhenSequenceExhausted() throws IOException {
        for (int i = 0; i < BandwidthGraph.MAX_FILE_ATTEMPTS; i++) {
            claimUnwritable(i);
        }
        assertEquals(new File(_dir, BandwidthGraph.fileName(0)), BandwidthGraph.resolveFile(_dir));
    }

    /**
     * A write replaces the target's contents and leaves no temp file behind.
     *
     * @throws IOException on write or read failure
     */
    @Test
    public void testWriteAtomicallyReplacesContents() throws IOException {
        File target = new File(_dir, BandwidthGraph.fileName(0));
        BandwidthGraph.writeAtomically(target, "1762930000,12,34");
        assertEquals("1762930000,12,34", read(target));
        BandwidthGraph.writeAtomically(target, "1762930060,5,6");
        assertEquals("1762930060,5,6", read(target));
        assertEquals("only the target is left", 1, _dir.list().length);
    }

    /**
     * A stale .tmp from another router's interrupted flush is not ours to replace,
     * so writing to a name that would have used it used to fail every five
     * minutes. It has to succeed anyway, and still clean up after itself.
     *
     * @throws IOException on scratch file creation, write or read failure
     */
    @Test
    public void testWriteAtomicallySurvivesStaleTmpFromAnotherUser() throws IOException {
        File stale = new File(_dir, BandwidthGraph.fileName(0) + ".tmp");
        assertTrue(stale.createNewFile());
        assertTrue(stale.setWritable(false));
        Assume.assumeFalse("running as root, cannot test permissions", stale.canWrite());
        File target = new File(_dir, BandwidthGraph.fileName(0));
        BandwidthGraph.writeAtomically(target, "1762930000,12,34");
        assertEquals("1762930000,12,34", read(target));
        // the stale file is still there, untouched, and our own temp file is gone
        assertEquals("the stale temp plus the target", 2, _dir.list().length);
    }

    /**
     * An access-denied flush names the reason in words and then the file, on one
     * line — the stack trace of a denied write says nothing beyond the path.
     */
    @Test
    public void testAccessDeniedMessage() {
        File f = new File(_dir, "i2p-snark-bandwidth-1.dat");
        String msg = BandwidthGraph.failMessage(f, new AccessDeniedException(f.getAbsolutePath()));
        assertEquals("Bandwidth graph flush failed -> Access denied * File: " + f.getAbsolutePath(), msg);
        assertFalse(msg.contains("\n"));
    }

    /**
     * Any other failure keeps the exception's own words, and one with no message
     * falls back to the exception's name rather than printing nothing.
     */
    @Test
    public void testOtherFailureMessages() {
        File f = new File(_dir, "i2p-snark-bandwidth.dat");
        String msg = BandwidthGraph.failMessage(f, new IOException("No space left on device"));
        assertTrue(msg.contains("No space left on device"));
        msg = BandwidthGraph.failMessage(f, new IOException());
        assertTrue(msg.contains("IOException"));
    }
}
