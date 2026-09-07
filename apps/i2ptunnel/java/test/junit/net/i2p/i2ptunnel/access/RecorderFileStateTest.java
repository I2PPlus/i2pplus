package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests the recorder-file read cache used by {@link AccessFilter#record()}:
 * the {@code (lastModified, length)} signature detection and the b32-set parse.
 * Files are exercised on the real filesystem with temp files, matching the
 * production path exactly (no router context required).
 *
 * @since 0.9.71+
 */
public class RecorderFileStateTest {

    private static File newRecorderFile(String... lines) throws Exception {
        File f = File.createTempFile("recorder", ".txt");
        f.deleteOnExit();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        }
        return f;
    }

    @Test
    public void readParsesEachLineAsSetMember() throws Exception {
        File f = newRecorderFile("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=");
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        assertTrue(state.valid);
        assertEquals(2, state.breached.size());
        assertTrue(state.breached.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        assertTrue(state.breached.contains("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="));
        assertEquals(f.lastModified(), state.modified);
        assertEquals(f.length(), state.length);
    }

    @Test
    public void readOfMissingFileIsInvalidAndEmpty() throws Exception {
        File missing = new File("/tmp/definitely-not-present-" + System.nanoTime());
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(missing);
        assertFalse(state.valid);
        assertTrue(state.breached.isEmpty());
    }

    @Test
    public void unchangedFileReusesSet() throws Exception {
        File f = newRecorderFile("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        assertTrue("read then unchanged must still be fresh",
                   AccessFilter.recorderFileUnchanged(state, f));
    }

    @Test
    public void changedMtimeInvalidates() throws Exception {
        File f = newRecorderFile("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        long sig = f.lastModified();
        f.setLastModified(sig + 10_000);
        assertFalse("size same but mtime changed must invalidate",
                    AccessFilter.recorderFileUnchanged(state, f));
    }

    @Test
    public void changedLengthInvalidates() throws Exception {
        File f = newRecorderFile("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        // Write a second line; same mtime granularity may match but length must differ.
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=\n");
        }
        assertNotEquals(state.length, f.length());
        assertFalse("changed length must invalidate",
                    AccessFilter.recorderFileUnchanged(state, f));
    }

    @Test
    public void missingFileStaysMissingWithoutReRead() throws Exception {
        File missing = new File("/tmp/definitely-not-present-" + System.nanoTime() + "b");
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(missing);
        assertFalse(state.valid);
        assertTrue("missing->missing must stay cached as unchanged",
                   AccessFilter.recorderFileUnchanged(state, missing));
    }

    @Test
    public void missingToPresentInvalidates() throws Exception {
        File f = new File("/tmp/recorder-appears-" + System.nanoTime());
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        assertFalse(state.valid);
        newRecorderFile().delete();
        f.deleteOnExit();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n");
        }
        assertFalse("file appearing must invalidate the cached missing state",
                    AccessFilter.recorderFileUnchanged(state, f));
    }

    @Test
    public void noCacheOnFirstUse() throws Exception {
        File f = newRecorderFile();
        assertFalse("null cache must force a read", AccessFilter.recorderFileUnchanged(null, f));
    }

    @Test
    public void isEmptySetDetectsPresence() throws Exception {
        File f = newRecorderFile();
        AccessFilter.RecorderFileState state = AccessFilter.readRecorderFile(f);
        assertTrue(state.valid);
        assertTrue(state.breached.isEmpty());
    }
}