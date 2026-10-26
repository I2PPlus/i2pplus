package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Storage#getFileLengths()}, the declared per-file lengths a
 * torrent's UI needs to size a progress bar.
 *
 * <p>The value under test is the length from the metainfo, not the size on
 * disk, so the tests that matter are the ones where the two differ.
 * {@link Storage#check} fills the per-file arrays and pre-allocates a
 * data-directory file to the declared length, so for a download in the data
 * directory the two agree from the first check onward; they part company for a
 * file deleted afterwards, and for one staged in the temp directory, which is
 * written in place and stays short of its total. {@link Storage#remaining2()}
 * counts missing bytes against the declared length, so a row that sizes itself
 * by the bytes on disk shows a bar that cannot reach 100%.
 *
 * @since 0.9.72+
 */
public class StorageFileLengthsTest {

    /** Temp root the data directory lives under. */
    private File _baseDir;
    /** Where the torrent's files live. */
    private File _dataDir;

    /**
     * Create a fresh data directory for one test.
     *
     * @throws IOException if the temp directory cannot be created
     */
    @Before
    public void setUp() throws IOException {
        _baseDir = createTempDir("snark-lengths");
        _dataDir = new File(_baseDir, "data");
        assertTrue(_dataDir.mkdir());
    }

    /**
     * Remove the temp tree a test wrote into.
     */
    @After
    public void tearDown() {
        deleteTree(_baseDir);
    }

    /**
     * A single-file torrent reports its one declared length, and the array is
     * index-aligned with the names and the remaining/preview arrays.
     *
     * @throws Throwable on torrent creation or verification failure
     */
    @Test
    public void singleFileTorrentReportsItsDeclaredLength() throws Throwable {
        File f = new File(_dataDir, "single.pdf");
        writeFile(f, content(5000));
        Storage s = storageFor(newStorage(util(), f, null));
        s.check(0, null);

        long[] lengths = s.getFileLengths();
        assertEquals(1, lengths.length);
        assertEquals(5000L, lengths[0]);

        List<String> names = s.getFileNames();
        assertEquals(1, names.size());
        assertEquals(lengths.length, s.remaining2()[0].length);
        assertEquals(lengths.length, s.remaining2()[1].length);
    }

    /**
     * A multi-file torrent reports every declared length, in the metainfo's
     * order, matching its own length list entry for entry.
     *
     * @throws Throwable on torrent creation or verification failure
     */
    @Test
    public void multiFileTorrentReportsEveryLengthInOrder() throws Throwable {
        writeFile(new File(_dataDir, "a.dat"), content(1000));
        writeFile(new File(_dataDir, "b.dat"), content(2500));
        Storage created = newStorage(util(), _dataDir, null);
        Storage s = storageFor(created);
        s.check(0, null);

        long[] lengths = s.getFileLengths();
        List<String> names = s.getFileNames();
        assertEquals(names.size(), lengths.length);
        assertEquals(2, lengths.length);
        // Storage keeps the metainfo order, so the two arrays describe the
        // same files at the same indices.
        List<Long> declared = created.getMetaInfo().getLengths();
        assertEquals(declared.size(), lengths.length);
        for (int i = 0; i < lengths.length; i++) {
            assertEquals("length " + i + " of " + names.get(i),
                         declared.get(i).longValue(), lengths[i]);
        }
    }

    /**
     * The declared length does not depend on the file being there, which is
     * the case a size label and a progress bar have to be built from.
     *
     * @throws Throwable on torrent creation, deletion, or verification failure
     */
    @Test
    public void declaredLengthOutlivesTheFileOnDisk() throws Throwable {
        File f = new File(_dataDir, "single.pdf");
        writeFile(f, content(5000));
        MetaInfo mi = newStorage(util(), f, null).getMetaInfo();

        Storage s = new Storage(util(), f, mi, null, true);
        s.check(0, null);
        assertEquals(5000L, s.getFileLengths()[0]);

        // Wipe the data after the check, as a user deleting the file does.
        assertTrue(f.delete());
        assertEquals(0L, f.length());
        assertEquals("the declared length does not come from the file",
                     5000L, s.getFileLengths()[0]);
    }

    /**
     * A file short of its total reports the torrent's length, not its own: the
     * case a progress bar measured against the bytes on disk gets wrong.
     *
     * @throws Throwable on torrent creation, truncation, or verification failure
     */
    @Test
    public void declaredLengthIsNotTheSizeOnDisk() throws Throwable {
        File f = new File(_dataDir, "single.pdf");
        writeFile(f, content(5000));
        MetaInfo mi = newStorage(util(), f, null).getMetaInfo();

        Storage s = new Storage(util(), f, mi, null, true);
        s.check(0, null);
        truncate(f, 1200);
        assertEquals(5000L, s.getFileLengths()[0]);
        assertEquals(1200L, f.length());
    }

    /**
     * The per-file arrays, including the declared lengths and the index map,
     * are filled by the check, not by the constructor, so a caller reading them
     * before the first check gets empty arrays rather than a partial view.
     *
     * @throws Throwable on torrent creation or verification failure
     */
    @Test
    public void lengthsAreEmptyUntilTheStorageIsChecked() throws Throwable {
        File f = new File(_dataDir, "single.pdf");
        writeFile(f, content(5000));
        MetaInfo mi = newStorage(util(), f, null).getMetaInfo();

        Storage s = new Storage(util(), f, mi, null, true);
        assertEquals(0, s.getFileLengths().length);
        assertTrue("the index map is filled by the same pass", s.indexMap().isEmpty());
        s.check(0, null);
        assertEquals(1, s.getFileLengths().length);
        assertEquals(1, s.indexMap().size());
    }

    /**
     * The util every Storage constructor needs, on the global context.
     *
     * @return a util with the default (disabled) staging directory
     */
    private static I2PSnarkUtil util() {
        return new I2PSnarkUtil(I2PAppContext.getGlobalContext());
    }

    /**
     * Build the metainfo for the file or directory, by creating a torrent from
     * it. Padding is off so the file count is exactly what was written.
     *
     * @param util the util to build with
     * @param base the file (single-file torrent) or directory (multi-file)
     * @param listener storage listener, may be null
     * @return the storage holding the freshly created metainfo
     * @throws IOException if the torrent cannot be created
     */
    private static Storage newStorage(I2PSnarkUtil util, File base, StorageListener listener)
            throws IOException {
        util.setShouldPadFiles(false);
        return new Storage(util, base, "http://tracker.test", null, null, false, listener,
                           new ArrayList<>());
    }

    /**
     * A Storage that verifies the data on disk against a metainfo.
     *
     * @param created the storage created from the data, to take the metainfo from
     * @return a storage over the same base and metainfo
     * @throws IOException if the storage cannot be opened
     */
    private static Storage storageFor(Storage created) throws IOException {
        MetaInfo mi = created.getMetaInfo();
        // Reparse so the metainfo is the on-the-wire form a download uses.
        return new Storage(util(), created.getBase(), reparse(mi), null, true);
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
     * Cut a file down to a shorter length, as a staged file or a truncated one
     * is: the data-directory file a check leaves behind is already full length.
     *
     * @param f the file to truncate
     * @param len the length to cut it to
     * @throws IOException if the file cannot be opened or resized
     */
    private static void truncate(File f, int len) throws IOException {
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
