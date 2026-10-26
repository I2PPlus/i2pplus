package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Storage;

/**
 * Tests for the single-file torrent listing: the URL a lone file's row links
 * to, the total a row sizes itself by, and the shape test that decides a
 * torrent has one file.
 *
 * <p>A single-file torrent's root is the file, not a directory, so the
 * listing's base URL ends with the file's own name. Joining the name to that
 * base asks the servlet for the file twice, and it 404s, so the row links
 * under the base's parent instead. That is the trap these tests pin.
 *
 * @since 0.9.72+
 */
public class SingleFileListingTest {

    /** The torrent's file name, as it appears in the URL and on disk. */
    private static final String NAME = "Java-FX-For-Dummies.pdf";

    /** Temp root for the torrent data. */
    private File baseDir;
    /** Where the torrent's file lives. */
    private File dataDir;

    /**
     * Create a fresh data directory for one test.
     *
     * @throws IOException if the temp directory cannot be created
     */
    @Before
    public void setUp() throws IOException {
        baseDir = createTempDir("snark-singlefile");
        dataDir = new File(baseDir, "data");
        assertTrue(dataDir.mkdir());
    }

    /**
     * Remove the temp tree a test wrote into.
     */
    @After
    public void tearDown() {
        deleteTree(baseDir);
    }

    /**
     * The link base drops the torrent's own name, leaving the directory the
     * file is served from.
     */
    @Test
    public void linkBaseIsTheDirectoryAboveTheTorrentUrl() {
        assertEquals("/i2psnark/", I2PSnarkServlet.singleFileLinkBase("/i2psnark/" + NAME + "/"));
        assertEquals("/i2psnark/", I2PSnarkServlet.singleFileLinkBase("/i2psnark/" + NAME));
        assertEquals("/i2psnark/sub/",
                     I2PSnarkServlet.singleFileLinkBase("/i2psnark/sub/" + NAME + "/"));
    }

    /**
     * A base with no name to drop still answers a usable prefix, so the row
     * never links to a relative path.
     */
    @Test
    public void linkBaseAlwaysEndsWithASlash() {
        assertEquals("/", I2PSnarkServlet.singleFileLinkBase("/"));
        assertEquals("/", I2PSnarkServlet.singleFileLinkBase(NAME + "/"));
        assertTrue(I2PSnarkServlet.singleFileLinkBase("/i2psnark/" + NAME).endsWith("/"));
    }

    /**
     * The row URL is the URL the servlet serves the file at: the torrent's own
     * URL, base minus its trailing slash.
     */
    @Test
    public void rowUrlIsTheTorrentUrlItself() {
        String base = "/i2psnark/" + NAME + "/";
        String linkBase = I2PSnarkServlet.singleFileLinkBase(base);
        String rowUrl = BasicServlet.addPaths(linkBase, NAME);
        assertEquals(base.substring(0, base.length() - 1), rowUrl);
    }

    /**
     * The naive join, appending the name to the listing's base, is the bug: it
     * names the file twice, and that is not the URL the file is served at.
     */
    @Test
    public void appendingTheNameToTheBaseNamesItTwice() {
        String base = "/i2psnark/" + NAME + "/";
        String naive = BasicServlet.addPaths(base, NAME);
        assertEquals("/i2psnark/" + NAME + "/" + NAME, naive);
        assertNotEquals(base.substring(0, base.length() - 1), naive);
    }

    /**
     * A single-file torrent is recognised from its metainfo, so the listing
     * decision does not depend on the file being on disk.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void singleFileTorrentIsRecognisedFromTheMetainfo() throws Throwable {
        Storage s = singleFileStorage(5000);
        assertTrue(I2PSnarkServlet.isSingleFileStorage(s));
        assertNull("the metainfo is the authority: no files list for one file",
                   s.getMetaInfo().getFiles());
    }

    /**
     * A directory torrent is not single-file, so its listing keeps walking the
     * tree instead of showing the root directory as a row.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void directoryTorrentIsNotSingleFile() throws Throwable {
        writeFile(new File(dataDir, "a.dat"), content(1000));
        writeFile(new File(dataDir, "b.dat"), content(1000));
        Storage s = storageFrom(dataDir);
        assertFalse(I2PSnarkServlet.isSingleFileStorage(s));
        assertEquals(2, s.getFileLengths().length);
    }

    /**
     * The row sizes itself by the declared length, which is what the remaining
     * byte count is measured against.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void rowTotalIsTheDeclaredLength() throws Throwable {
        Storage s = singleFileStorage(5000);
        Sorters.FileAndIndex fai = entry(s, s.getBase());
        assertEquals(5000L, fai.declaredLength);
        assertEquals(5000L, I2PSnarkServlet.rowTotalBytes(fai));
    }

    /**
     * An entry storage does not know has no declared total, so its row shows
     * no progress; the torrent's own file, deleted after the check, keeps the
     * declared total and so shows real progress.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void missingFileStillHasATotal() throws Throwable {
        Storage s = singleFileStorage(5000);
        File missing = new File(dataDir, "not-here.pdf");
        assertFalse(missing.exists());
        // Not in storage, so no declared length is known for it.
        Sorters.FileAndIndex unknown = entry(s, missing);
        assertEquals(-1, unknown.index);
        assertEquals(-1L, unknown.declaredLength);
        assertEquals(0L, I2PSnarkServlet.rowTotalBytes(unknown));
        assertTrue("a file with no total has no progress to show",
                   I2PSnarkServlet.rowIsComplete(unknown.remaining,
                                                 I2PSnarkServlet.rowTotalBytes(unknown)));

        // The torrent's own file, wiped: storage knows the length, disk does not.
        assertTrue(s.getBase().delete());
        Sorters.FileAndIndex wiped = entry(s, s.getBase());
        assertEquals(5000L, wiped.declaredLength);
        assertEquals(0L, wiped.file.length());
        assertEquals(5000L, I2PSnarkServlet.rowTotalBytes(wiped));
        assertFalse("a wiped file is not finished",
                    I2PSnarkServlet.rowIsComplete(5000, I2PSnarkServlet.rowTotalBytes(wiped)));
    }

    /**
     * With no declared length, the size on disk is the total, so a 0-byte file
     * keeps reading as finished and an unstored entry keeps its own size.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void rowTotalFallsBackToTheSizeOnDisk() throws IOException {
        File f = new File(dataDir, "small.txt");
        writeFile(f, content(37));
        Sorters.FileAndIndex fai = new Sorters.FileAndIndex(f, null, null, null, null, null);
        assertEquals(-1L, fai.declaredLength);
        assertEquals(37L, I2PSnarkServlet.rowTotalBytes(fai));
    }

    /**
     * A directory has no total. {@link File#length()} answers with the size of
     * the directory's own entry, which is not a size worth showing anyone.
     */
    @Test
    public void aDirectoryHasNoTotal() {
        File dir = new File(dataDir, "adir");
        assertTrue(dir.mkdir());
        Sorters.FileAndIndex fai = new Sorters.FileAndIndex(dir, null, null, null, null, null);
        assertTrue(fai.isDirectory);
        assertEquals(0L, I2PSnarkServlet.rowTotalBytes(fai));
    }

    /**
     * The complete/progress decision: nothing left to fetch is complete, and a
     * row with no positive total cannot show progress.
     */
    @Test
    public void rowIsCompleteWhenNothingIsLeftOrNothingHasProgress() {
        assertTrue(I2PSnarkServlet.rowIsComplete(0, 5000));
        assertFalse(I2PSnarkServlet.rowIsComplete(1, 5000));
        assertFalse(I2PSnarkServlet.rowIsComplete(4999, 5000));
        assertTrue("no total, so no bar to fill", I2PSnarkServlet.rowIsComplete(10, 0));
        assertTrue("no declared length, same", I2PSnarkServlet.rowIsComplete(10, -1));
        assertTrue("an empty file is finished", I2PSnarkServlet.rowIsComplete(0, 0));
    }

    /**
     * A directory listing that exists wins: a single-file download staged in
     * the temp directory is a real directory, and its rows are the staged
     * files, not the torrent's data-directory path.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void aDirectoryListingWinsOverTheSingleFileForm() throws Throwable {
        Storage s = singleFileStorage(5000);
        File[] staged = {new File(dataDir, "staged.pdf")};
        assertFalse(I2PSnarkServlet.listsSingleFile(staged, s, "/"));
    }

    /**
     * Only the torrent root is the file. A path below it is not, and a magnet
     * link with no storage has no file to list.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void onlyTheTorrentRootListsTheSingleFile() throws Throwable {
        Storage s = singleFileStorage(5000);
        assertTrue(I2PSnarkServlet.listsSingleFile(null, s, "/"));
        assertFalse("a path below the root is not the file",
                    I2PSnarkServlet.listsSingleFile(null, s, "/inner"));
        assertFalse("no storage, nothing to list",
                    I2PSnarkServlet.listsSingleFile(null, null, "/"));
    }

    /**
     * A directory torrent never lists its root as a file, so the file table
     * keeps walking the tree.
     *
     * @throws Throwable on torrent creation failure
     */
    @Test
    public void directoryTorrentNeverListsItsRootAsAFile() throws Throwable {
        File sub = new File(dataDir, "tree");
        assertTrue(sub.mkdir());
        writeFile(new File(sub, "a.dat"), content(1000));
        writeFile(new File(sub, "b.dat"), content(1000));
        Storage dir = storageFrom(sub);
        assertFalse(I2PSnarkServlet.listsSingleFile(null, dir, "/"));
    }

    /**
     * The four-argument context links rows under the directory being listed,
     * which is what a directory listing needs.
     */
    @Test
    public void contextDefaultsTheLinkBaseToTheBase() {
        I2PSnarkServlet.FileRowContext ctx =
                new I2PSnarkServlet.FileRowContext("/i2psnark/dir/", null, false, true);
        assertEquals("/i2psnark/dir/", ctx.decodedBase);
        assertEquals(ctx.decodedBase, ctx.linkBase);
    }

    /**
     * The five-argument context keeps both: the listing's base for the parent
     * link, and the link base for the rows.
     */
    @Test
    public void contextCarriesBothBases() {
        I2PSnarkServlet.FileRowContext ctx = new I2PSnarkServlet.FileRowContext(
                "/i2psnark/" + NAME + "/", "/i2psnark/", null, false, true);
        assertEquals("/i2psnark/" + NAME + "/", ctx.decodedBase);
        assertEquals("/i2psnark/", ctx.linkBase);
    }

    /**
     * A completed single-file torrent over a temp file of the given size.
     *
     * @param size the file's size in bytes
     * @return a storage whose base is the file
     * @throws Throwable on torrent creation failure
     */
    private Storage singleFileStorage(int size) throws Throwable {
        File f = new File(dataDir, NAME);
        writeFile(f, content(size));
        return storageFrom(f);
    }

    /**
     * Create a torrent from a file or directory, then open a storage over it.
     *
     * @param base the single file, or the directory to make a directory torrent from
     * @return a storage verifying the data on disk
     * @throws Throwable on torrent creation failure
     */
    private Storage storageFrom(File base) throws Throwable {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        util.setShouldPadFiles(false);
        Storage created = new Storage(util, base, "http://tracker.test", null, null, false,
                                      null, new ArrayList<>());
        MetaInfo mi = created.getMetaInfo();
        Storage s = new Storage(util, base, reparse(mi), null, true);
        s.check(0, null);
        return s;
    }

    /**
     * Round-trip a metainfo through its bencoded form, as a downloaded torrent is.
     *
     * @param mi the metainfo to reparse
     * @return the equivalent metainfo read back from bytes
     * @throws Throwable if the metainfo cannot be re-encoded or re-read
     */
    private static MetaInfo reparse(MetaInfo mi) throws Throwable {
        return new MetaInfo(new ByteArrayInputStream(mi.getTorrentData()));
    }

    /**
     * A file-listing entry for a file, resolved the way getListHTML resolves it.
     *
     * @param s the torrent's storage
     * @param f the file to describe
     * @return the entry, with its index resolved from the storage's index map
     */
    private static Sorters.FileAndIndex entry(Storage s, File f) {
        long[][] arrays = s.remaining2();
        Map<String, Integer> indexMap = s.indexMap();
        return new Sorters.FileAndIndex(f, s, arrays[0], arrays[1], indexMap, s.getFileLengths());
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
