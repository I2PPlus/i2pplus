package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the summary a storage check pass reports for files that lost their
 * data, {@link Storage#resetSummary} and {@link Storage#repairSummary}.
 *
 * <p>A check pass used to log one error per file. A torrent with hundreds of
 * files whose staging directory a restart cleared, as a tmpfs temp dir does
 * not survive a reboot, then buried the console in "repairing corruption"
 * lines for what is a normal not-downloaded-yet state. The pass now reports
 * once, at warning level, and the download simply resumes, so the tests that
 * matter are that the pass produces exactly one message, that it explains the
 * staging state, and that the empty files are extended back to their declared
 * length.
 *
 * @since 0.9.72+
 */
public class StorageLengthSummaryTest {

    /** Temp root the data directory lives under. */
    private File _baseDir;
    /** Where the torrent's files live. */
    private File _dataDir;
    /** Messages the storage check pass reported to its listener. */
    private List<String> _messages;

    /**
     * One instance per test method: JUnit builds a fresh test object and runs
     * setUp() before each one, which is what keeps each test's temp tree and
     * message log separate from the others.
     *
     * @since 0.9.72+
     */
    public StorageLengthSummaryTest() {}

    /**
     * Create a fresh data directory and message log for one test.
     *
     * @throws IOException if the temp directory cannot be created
     */
    @Before
    public void setUp() throws IOException {
        _baseDir = createTempDir("snark-summary");
        _dataDir = new File(_baseDir, "data");
        assertTrue(_dataDir.mkdir());
        _messages = new ArrayList<>();
    }

    /**
     * Remove the temp tree a test wrote into.
     */
    @After
    public void tearDown() {
        deleteTree(_baseDir);
    }

    /**
     * A wiped staging dir leaves the finished file in the data dir and the rest
     * as zero-length placeholders. The pass reports that once, not per file.
     *
     * @throws Throwable on torrent creation, truncation, or verification failure
     */
    @Test
    public void oneMessageForManyEmptyFiles() throws Throwable {
        Storage s = checkedStorage();
        truncate(new File(_dataDir, "b.dat"), 0);
        truncate(new File(_dataDir, "c.dat"), 0);
        _messages.clear();
        s.recheck();

        assertEquals("one line for the pass, not one per file", 1, _messages.size());
        String msg = _messages.get(0);
        assertTrue(msg, msg.startsWith("Resuming \"" + _dataDir.getName() + '"'));
        assertTrue(msg, msg.contains("downloaded again"));
        assertTrue(msg, msg.contains(DataHelper.formatSize2(6500L)));
        // The download resumes by writing into files extended back to length.
        assertEquals(2500L, new File(_dataDir, "b.dat").length());
        assertEquals(4000L, new File(_dataDir, "c.dat").length());
        assertFalse(s.complete());
    }

    /**
     * A new download has every file empty, which is not a finding worth a line.
     *
     * @throws Throwable on torrent creation, truncation, or verification failure
     */
    @Test
    public void freshDownloadReportsNothing() throws Throwable {
        Storage s = checkedStorage();
        truncate(new File(_dataDir, "a.dat"), 0);
        truncate(new File(_dataDir, "b.dat"), 0);
        truncate(new File(_dataDir, "c.dat"), 0);
        _messages.clear();
        s.recheck();

        assertTrue(_messages.isEmpty());
    }

    /**
     * A vanished file is recreated empty and reported in the same single line,
     * labelled so a reader can tell it from a file that was never written.
     *
     * @throws Throwable on torrent creation, deletion, or verification failure
     */
    @Test
    public void deletedFileReportedInSameLine() throws Throwable {
        Storage s = checkedStorage();
        File gone = new File(_dataDir, "c.dat");
        assertTrue(gone.delete());
        _messages.clear();
        s.recheck();

        assertEquals(1, _messages.size());
        assertTrue(_messages.get(0), _messages.get(0).contains("Deleted: 1"));
    }

    /**
     * The line names the staging dir's state, so a reader can tell a temp dir a
     * restart wiped from a plain partial download.
     *
     * @throws IOException if the staging dir or a marker file cannot be created
     */
    @Test
    public void stagingStateExplainsAWipedTempDir() throws IOException {
        assertTrue(Storage.resetSummary("t", 1, 0, 1024L, null).contains("Staging: disabled"));
        File staging = new File(_baseDir, "staging");
        assertTrue(Storage.resetSummary("t", 1, 0, 1024L, staging).contains("tmpfs"));
        assertTrue(staging.mkdir());
        assertTrue(Storage.resetSummary("t", 1, 0, 1024L, staging).contains("(empty)"));
        assertTrue(new File(staging, "x").createNewFile());
        String full = Storage.resetSummary("t", 1, 0, 1024L, staging);
        assertFalse(full, full.contains("(empty)"));
        assertTrue(full, full.contains(staging.getAbsolutePath()));
    }

    /**
     * A partial write is a real finding, reported once, with the bytes resized.
     */
    @Test
    public void repairSummaryCountsTheResizedFiles() {
        String one = Storage.repairSummary("t", 1, 512L);
        assertTrue(one, one.startsWith("Repairing \"t\" -> "));
        assertTrue(one, one.contains("1 file with a partial length"));
        assertTrue(one, one.contains(DataHelper.formatSize2(512L)));
        assertTrue(Storage.repairSummary("t", 4, 512L).contains("4 files"));
    }

    /**
     * A three-file torrent, fully downloaded and verified, checked through a
     * listener that records the messages the check pass reports.
     *
     * @return a storage whose files are all at their declared lengths
     * @throws Throwable on torrent creation or verification failure
     */
    private Storage checkedStorage() throws Throwable {
        writeFile(new File(_dataDir, "a.dat"), content(1000));
        writeFile(new File(_dataDir, "b.dat"), content(2500));
        writeFile(new File(_dataDir, "c.dat"), content(4000));
        I2PSnarkUtil util = util();
        Storage created = newStorage(util, _dataDir, null);
        Storage s = new Storage(util, _dataDir, reparse(created.getMetaInfo()), new Recorder(), true);
        s.check(0, null);
        assertTrue(s.complete());
        return s;
    }

    /**
     * Storage listener keeping only the status messages, which is what the
     * check pass reports; the progress callbacks are of no interest here.
     */
    private class Recorder implements StorageListener {
        public void storageCreateFile(Storage storage, String name, long length) {}
        public void storageAllocated(Storage storage, long length) {}
        public void storageChecked(Storage storage, int num, boolean checked) {}
        public void storageAllChecked(Storage storage) {}
        public void storageCompleted(Storage storage) {}
        public void setWantedPieces(Storage storage) {}

        /**
         * Record one status message for the test to assert on.
         *
         * @param message the message the check pass reported
         */
        public void addMessage(String message) {
            _messages.add(message);
        }
    }

    /**
     * The util every Storage constructor needs, on the global context, with
     * padding off (so the file count is exactly what was written) and staging
     * left disabled.
     *
     * @return a util for building and checking a torrent
     */
    private static I2PSnarkUtil util() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        util.setShouldPadFiles(false);
        return util;
    }

    /**
     * Build the metainfo for a directory by creating a torrent from it.
     *
     * @param util the util to build with
     * @param base the directory holding the torrent's files
     * @param listener storage listener, may be null
     * @return the storage holding the freshly created metainfo
     * @throws IOException if the torrent cannot be created
     */
    private static Storage newStorage(I2PSnarkUtil util, File base, StorageListener listener)
            throws IOException {
        return new Storage(util, base, "http://tracker.test", null, null, false, listener,
                           new ArrayList<>());
    }

    /**
     * Round-trip a metainfo through its bencoded form.
     *
     * @param mi the metainfo to reparse
     * @return the equivalent metainfo read back from bytes
     * @throws IOException if the metainfo cannot be re-encoded or re-read
     */
    private static MetaInfo reparse(MetaInfo mi) throws IOException {
        try {
            return new MetaInfo(new ByteArrayInputStream(mi.getTorrentData()));
        } catch (Throwable t) {
            if (t instanceof IOException) {throw (IOException) t;}
            throw new IOException(t);
        }
    }

    /**
     * Write a file with the given content.
     *
     * @param f the file to create
     * @param content the bytes to write
     * @throws IOException if the write fails
     */
    private static void writeFile(File f, byte[] content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content);
        }
    }

    /**
     * Resize a file, as a staged file that never got its data, or a wiped
     * staging dir leaving a zero-length placeholder, leaves it.
     *
     * @param f the file to resize
     * @param len the length to set it to
     * @throws IOException if the file cannot be opened or resized
     */
    private static void truncate(File f, long len) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            raf.setLength(len);
        }
    }

    /**
     * Deterministic file content.
     *
     * @param size the number of bytes to produce
     * @return a byte array of that size
     */
    private static byte[] content(int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return content;
    }

    /**
     * A fresh temp directory, not a temp file.
     *
     * @param prefix the name prefix
     * @return the created directory
     * @throws IOException if it cannot be created
     */
    private static File createTempDir(String prefix) throws IOException {
        File f = File.createTempFile(prefix, "");
        assertTrue(f.delete());
        assertTrue(f.mkdir());
        return f;
    }

    /**
     * Delete a directory tree.
     *
     * @param f the root to delete, ignored when null
     */
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {return;}
        if (f.isDirectory()) {
            File[] ls = f.listFiles();
            if (ls != null) {
                for (File l : ls) {
                    deleteTree(l);
                }
            }
        }
        f.delete();
    }
}
