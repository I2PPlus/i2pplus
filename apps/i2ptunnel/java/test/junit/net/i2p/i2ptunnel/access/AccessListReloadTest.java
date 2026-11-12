package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests that an access list read failure keeps the last good list in force, and
 * that the reload decision is driven by the file's own timestamp rather than the
 * wall clock.
 *
 * @since 0.9.72+
 */
public class AccessListReloadTest {

    /** One valid destination entry for the test list. */
    private static final String B32_ONE = "gbbkv6ordyceofn542c2aa2o24s77mbis3pzio77yegf4glqsmwq.b32.i2p";

    /** A second valid entry, differing from the first in its leading character. */
    private static final String B32_TWO = "hbbkv6ordyceofn542c2aa2o24s77mbis3pzio77yegf4glqsmwq.b32.i2p";

    @Test
    public void testReadsTheEntriesInTheFile() throws Exception {
        File file = list(B32_ONE);
        try {
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            Map<Hash, DestTracker> map = new HashMap<>();
            element.update(map);
            assertEquals(1, map.size());
            assertTrue(map.containsKey(hash(B32_ONE)));
        } finally {
            file.delete();
        }
    }

    /**
     * The defect this guards: the list was cleared and the "already loaded" stamp
     * advanced before the file was opened, so one failed read left the filter with
     * no entries and made the empty result look current.
     *
     * @throws Exception if the temporary list file cannot be created or written
     */
    @Test
    public void testFailedReadKeepsThePreviousEntries() throws Exception {
        File file = list(B32_ONE);
        try {
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            Map<Hash, DestTracker> first = new HashMap<>();
            element.update(first);
            assertEquals(1, first.size());

            // Change the file so a reload is actually attempted, then take the
            // read away.
            write(file, B32_ONE, B32_TWO);
            file.setReadable(false);
            Assume.assumeTrue("running as root, an unreadable file is still readable", !canOpen(file));

            Map<Hash, DestTracker> after = new HashMap<>();
            element.update(after);
            assertTrue("A failed reload dropped the deny list", after.containsKey(hash(B32_ONE)));

            // The read is retried on the next sync, so fixing the file is enough
            // on its own - no timestamp change needed.
            file.setReadable(true);
            Map<Hash, DestTracker> third = new HashMap<>();
            element.update(third);
            assertEquals(2, third.size());
        } finally {
            file.setReadable(true);
            file.delete();
        }
    }

    /**
     * A list that has never been read is a policy that did not load, so the
     * failure is reported to the caller and the tunnel startup path can fail
     * closed instead of running with nothing denied.
     *
     * @throws Exception if the temporary list file cannot be created or written
     */
    @Test
    public void testFirstFailedReadIsReported() throws Exception {
        File file = list(B32_ONE);
        try {
            file.setReadable(false);
            Assume.assumeTrue("running as root, an unreadable file is still readable", !canOpen(file));
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            try {
                element.update(new HashMap<>());
                fail("An access list that has never been read must report the failure");
            } catch (IOException expected) {
                // expected
            }
        } finally {
            file.setReadable(true);
            file.delete();
        }
    }

    /**
     * A file rewritten with an older timestamp than the clock must still be
     * re-read: stamping the load with the wall clock made the file look newer than
     * it was and skipped the change.
     *
     * @throws Exception if the temporary list file cannot be created or written
     */
    @Test
    public void testOlderTimestampStillReloads() throws Exception {
        File file = list(B32_ONE);
        try {
            file.setLastModified(System.currentTimeMillis() + 60 * 60 * 1000L);
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            Map<Hash, DestTracker> first = new HashMap<>();
            element.update(first);
            assertEquals(1, first.size());

            write(file, B32_ONE, B32_TWO);
            file.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L);
            Map<Hash, DestTracker> second = new HashMap<>();
            element.update(second);
            assertTrue("A rewritten file with an older timestamp was skipped",
                       second.containsKey(hash(B32_TWO)));
        } finally {
            file.delete();
        }
    }

    /**
     * An unchanged file is served from the snapshot rather than re-read, which is
     * what keeps the 5 second sync cycle cheap.
     *
     * @throws Exception if the temporary list file cannot be created or written
     */
    @Test
    public void testUnchangedFileReusesTheSnapshot() throws Exception {
        File file = list(B32_ONE);
        try {
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            Map<Hash, DestTracker> first = new HashMap<>();
            element.update(first);
            Hash wanted = hash(B32_ONE);
            DestTracker tracker = first.get(wanted);
            assertNotNull(tracker);

            Map<Hash, DestTracker> second = new HashMap<>();
            element.update(second);
            assertSame("An unchanged file must reuse the tracked destination",
                       tracker, second.get(wanted));
        } finally {
            file.delete();
        }
    }

    /**
     * A list edited on Windows ends its lines with a CR, and the entry parser
     * wants exactly 60 characters, so entries are trimmed and comments and blank
     * lines are skipped rather than logged as invalid.
     *
     * @throws Exception if the temporary list file cannot be created or written
     */
    @Test
    public void testCarriageReturnsAndCommentsAreTolerated() throws Exception {
        File file = File.createTempFile("access-list", ".txt");
        try {
            String text = "# a comment\r\n " + B32_ONE + " \r\n\r\n" + B32_TWO + "\r\n";
            java.nio.file.Files.write(file.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            FileFilterDefinitionElement element = new FileFilterDefinitionElement(file, Threshold.DENY);
            Map<Hash, DestTracker> map = new HashMap<>();
            element.update(map);
            assertEquals(2, map.size());
            assertTrue(map.containsKey(hash(B32_ONE)));
            assertTrue(map.containsKey(hash(B32_TWO)));
        } finally {
            file.delete();
        }
    }

    /**
     * Write a new access list file holding the given entries.
     *
     * @param entries destination entries to write, one per line
     * @return the temporary file, which the caller deletes when done
     * @throws IOException if the file cannot be created or written
     */
    private static File list(String... entries) throws IOException {
        File file = File.createTempFile("access-list", ".txt");
        write(file, entries);
        return file;
    }

    /**
     * Replace the contents of the access list file.
     *
     * @param file file to write
     * @param entries destination entries to write, one per line
     * @throws IOException on a write error
     */
    private static void write(File file, String... entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            sb.append(entry).append('\n');
        }
        java.nio.file.Files.write(file.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Whether the file can still be opened for reading.
     *
     * @param file file to try
     * @return true if opening it succeeded
     */
    private static boolean canOpen(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The hash a destination entry parses to.
     *
     * @param b32 the entry as written in the list file
     * @return the destination hash
     */
    private static Hash hash(String b32) {
        try {
            return FileFilterDefinitionElement.fromBase32(b32);
        } catch (InvalidDefinitionException e) {
            throw new IllegalArgumentException("bad test entry " + b32, e);
        }
    }
}
