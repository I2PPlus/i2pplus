package net.i2p.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests for {@link NativeLoader}: which load failures are worth retrying, and
 * that a failure which is not a file lock is reported without waiting.
 */
public class NativeLoaderTest {

    /**
     * A harness instance per test method; the tests share no state.
     */
    public NativeLoaderTest() {}

    /** Milliseconds before the first retry, used as the no-retry deadline. */
    private static final long FIRST_BACKOFF_MS = 200;

    /** The exact wording a locked temp file produces on Windows. */
    private static final String WINDOWS_LOCK =
            "C:\\Users\\admin\\AppData\\Local\\Temp\\i2p-HByFpvYV.tmp\\jcpuid.dll: " +
            "The process cannot access the file because it is being used by another process";

    /**
     * The Windows sharing violation, and the other spellings of it, are retried.
     */
    @Test
    public void testRecognizesFileLocks() {
        assertTrue(NativeLoader.isFileLockError(WINDOWS_LOCK));
        assertTrue(NativeLoader.isFileLockError("Sharing violation while loading mylib.dll"));
        assertTrue(NativeLoader.isFileLockError("mylib.dll: Cannot access the file"));
        // matched case-insensitively, the JVM does not normalize the wording
        assertTrue(NativeLoader.isFileLockError("BEING USED BY ANOTHER PROCESS"));
    }

    /**
     * Failures a retry cannot fix are not retried, so the loader moves on to the
     * next library in the list instead of burning seconds at startup.
     */
    @Test
    public void testIgnoresRealLibraryErrors() {
        assertFalse(NativeLoader.isFileLockError(null));
        assertFalse(NativeLoader.isFileLockError(""));
        assertFalse(NativeLoader.isFileLockError("Can't load library: /opt/i2p/lib/jcpuid.dll"));
        assertFalse(NativeLoader.isFileLockError("mylib.dll: %1 is not a valid Win32 application"));
        assertFalse(NativeLoader.isFileLockError("mylib.so: wrong ELF class: ELFCLASS32"));
        assertFalse(NativeLoader.isFileLockError("mylib.so: file too short"));
    }

    /**
     * A missing library is not a lock, so it is reported on the first attempt
     * rather than after the backoff.
     */
    @Test
    public void testMissingLibraryFailsImmediately() {
        File missing = new File("/definitely-not-here/jcpuid.dll");
        long start = System.currentTimeMillis();
        try {
            NativeLoader.load(missing);
            fail("expected UnsatisfiedLinkError for a missing library");
        } catch (UnsatisfiedLinkError e) {
            assertNotNull(e.getMessage());
            assertFalse(NativeLoader.isFileLockError(e.getMessage()));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("no retry for a non-lock failure, took " + elapsed + "ms",
                       elapsed < FIRST_BACKOFF_MS);
        }
    }

    /**
     * A file that is not a library at all is reported, or on a lenient JVM
     * loaded, but never retried.
     *
     * @throws IOException if the scratch file cannot be written
     */
    @Test
    public void testNotALibraryIsNotRetried() throws IOException {
        File junk = File.createTempFile("not-a-library", ".dll");
        try {
            try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(junk),
                                                   StandardCharsets.UTF_8)) {
                w.write("this is not a shared object");
            }
            long start = System.currentTimeMillis();
            try {
                NativeLoader.load(junk);
            } catch (UnsatisfiedLinkError e) {
                assertFalse(NativeLoader.isFileLockError(e.getMessage()));
            }
            assertTrue("no retry for a file that is not a library",
                       System.currentTimeMillis() - start < FIRST_BACKOFF_MS);
        } finally {
            junk.delete();
        }
    }
}
