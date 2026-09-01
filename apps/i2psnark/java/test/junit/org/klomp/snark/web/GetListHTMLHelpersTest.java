package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the pure decision helpers extracted from I2PSnarkServlet's
 * getListHTML: POST action resolution, padding-directory filtering,
 * file-list wrapping, and the Directory column sort cycle.
 *
 * @since 0.9.71+
 */
public class GetListHTMLHelpersTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("snarkgl").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {return;}
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {deleteRecursively(k);}
        }
        f.delete();
    }

    private File newDir(String name) throws Exception {
        File d = new File(tempDir, name);
        assertTrue(d.mkdirs() || d.isDirectory());
        return d;
    }

    private File newFile(String name) throws Exception {
        File f = new File(tempDir, name);
        assertTrue(f.createNewFile() || f.isFile());
        return f;
    }

    // ---- findPostAction ----

    @Test
    public void testFindPostActionNone() {
        Map<String, String[]> pp = new HashMap<>();
        pp.put("nonce", new String[] {"abc"});
        assertNull(I2PSnarkServlet.findPostAction(pp));
    }

    @Test
    public void testFindPostActionEmptyMap() {
        assertNull(I2PSnarkServlet.findPostAction(new HashMap<String, String[]>()));
    }

    @Test
    public void testFindPostActionUnknownOnly() {
        Map<String, String[]> pp = new HashMap<>();
        pp.put("bogusCommand", new String[] {"x"});
        assertNull(I2PSnarkServlet.findPostAction(pp));
    }

    @Test
    public void testFindPostActionPrecedenceSavepriFirst() {
        Map<String, String[]> pp = new HashMap<>();
        for (String key : new String[] {"stop", "savepri", "addComment", "recheck"}) {
            pp.put(key, new String[] {"x"});
        }
        assertEquals("savepri", I2PSnarkServlet.findPostAction(pp));
    }

    @Test
    public void testFindPostActionPrecedenceOverLaterKeys() {
        // addComment beats everything except the four keys before it
        Map<String, String[]> pp = new HashMap<>();
        pp.put("editTorrent", new String[] {"x"});
        pp.put("addComment", new String[] {"x"});
        assertEquals("addComment", I2PSnarkServlet.findPostAction(pp));
    }

    @Test
    public void testFindPostActionEachKeyFound() {
        String[] keys = {"savepri", "addComment", "deleteComments", "setCommentsEnabled",
                         "stop", "start", "recheck", "editTorrent"};
        for (String key : keys) {
            Map<String, String[]> pp = new HashMap<>();
            pp.put("nonce", new String[] {"n"});
            pp.put(key, new String[] {"x"});
            assertEquals(key, I2PSnarkServlet.findPostAction(pp));
        }
    }

    // ---- isPaddingDir ----

    @Test
    public void testIsPaddingDirDotPad() throws Exception {
        assertTrue(I2PSnarkServlet.isPaddingDir(newDir(".pad")));
    }

    @Test
    public void testIsPaddingDirUnderscorePad() throws Exception {
        assertTrue(I2PSnarkServlet.isPaddingDir(newDir("_pad")));
    }

    @Test
    public void testIsPaddingDirRegularDir() throws Exception {
        assertFalse(I2PSnarkServlet.isPaddingDir(newDir("regular")));
    }

    @Test
    public void testIsPaddingDirFileNotDirectory() throws Exception {
        // a plain file named .pad must not be hidden
        assertFalse(I2PSnarkServlet.isPaddingDir(newFile(".pad")));
    }

    // ---- wrapFileList ----

    @Test
    public void testWrapFileListWrapsAllWithNullStorage() throws Exception {
        File[] ls = {newFile("a.txt"), newDir("sub")};
        List<Sorters.FileAndIndex> rv = I2PSnarkServlet.wrapFileList(ls, null, null, null, false);
        assertNotNull(rv);
        assertEquals(2, rv.size());
        assertSame(ls[0], rv.get(0).file);
        assertSame(ls[1], rv.get(1).file);
        // no storage: not tracked by the torrent
        assertEquals(-1, rv.get(0).index);
        assertEquals(-1, rv.get(0).remaining);
        assertTrue(rv.get(1).isDirectory);
        assertFalse(rv.get(0).isDirectory);
    }

    @Test
    public void testWrapFileListSkipsPaddingDirsWhenAsked() throws Exception {
        File[] ls = {newFile("keep.txt"), newDir(".pad"), newDir("_pad"), newFile("kept too")};
        List<Sorters.FileAndIndex> rv = I2PSnarkServlet.wrapFileList(ls, null, null, null, true);
        assertEquals(2, rv.size());
        assertSame(ls[0], rv.get(0).file);
        assertSame(ls[3], rv.get(1).file);
    }

    @Test
    public void testWrapFileListKeepsPaddingDirsWhenNotSkipping() throws Exception {
        File[] ls = {newDir(".pad"), newDir("_pad")};
        List<Sorters.FileAndIndex> rv = I2PSnarkServlet.wrapFileList(ls, null, null, null, false);
        assertEquals(2, rv.size());
    }

    @Test
    public void testWrapFileListPreservesOrderAndNeverNull() throws Exception {
        File[] ls = {};
        List<Sorters.FileAndIndex> rv = I2PSnarkServlet.wrapFileList(ls, null, null, null, true);
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
        List<Sorters.FileAndIndex> acc = new ArrayList<>();
        File[] many = {newFile("c"), newFile("a"), newFile("b")};
        acc.addAll(I2PSnarkServlet.wrapFileList(many, null, null, null, true));
        assertEquals("c", acc.get(0).file.getName());
        assertEquals("a", acc.get(1).file.getName());
        assertEquals("b", acc.get(2).file.getName());
    }

    // ---- Directory column sort cycle ----

    @Test
    public void testNextNameTypeSortCycle() {
        assertEquals("-1", I2PSnarkServlet.nextNameTypeSort(null));
        assertEquals("-1", I2PSnarkServlet.nextNameTypeSort("0"));
        assertEquals("-1", I2PSnarkServlet.nextNameTypeSort("1"));
        assertEquals("12", I2PSnarkServlet.nextNameTypeSort("-1"));
        assertEquals("-12", I2PSnarkServlet.nextNameTypeSort("12"));
        assertEquals("", I2PSnarkServlet.nextNameTypeSort("-12"));
        // any other value restarts unsorted
        assertEquals("", I2PSnarkServlet.nextNameTypeSort("5"));
        assertEquals("", I2PSnarkServlet.nextNameTypeSort(""));
        assertEquals("", I2PSnarkServlet.nextNameTypeSort("garbage"));
    }

    @Test
    public void testIsTypeSortNext() {
        assertTrue(I2PSnarkServlet.isTypeSortNext("-1"));
        assertTrue(I2PSnarkServlet.isTypeSortNext("12"));
        assertFalse(I2PSnarkServlet.isTypeSortNext(null));
        assertFalse(I2PSnarkServlet.isTypeSortNext("0"));
        assertFalse(I2PSnarkServlet.isTypeSortNext("1"));
        assertFalse(I2PSnarkServlet.isTypeSortNext("-12"));
        assertFalse(I2PSnarkServlet.isTypeSortNext(""));
    }

    @Test
    public void testSortCycleConsistency() {
        // wherever the tooltip advertises a type sort, the next key must be one
        Map<String, String> expect = new HashMap<>();
        expect.put("-1", "12");
        expect.put("12", "-12");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            assertTrue(I2PSnarkServlet.isTypeSortNext(e.getKey()));
            assertEquals(e.getValue(), I2PSnarkServlet.nextNameTypeSort(e.getKey()));
        }
    }

    // ---- threshold-gated streaming ----

    @Test
    public void testShouldStreamTorrentRowBoundaries() {
        assertFalse(I2PSnarkServlet.shouldStreamTorrentRows(0));
        assertFalse(I2PSnarkServlet.shouldStreamTorrentRows(1));
        assertFalse(I2PSnarkServlet.shouldStreamTorrentRows(31));
        assertTrue(I2PSnarkServlet.shouldStreamTorrentRows(32));
        assertTrue(I2PSnarkServlet.shouldStreamTorrentRows(33));
        assertTrue(I2PSnarkServlet.shouldStreamTorrentRows(Integer.MAX_VALUE));
    }

    @Test
    public void testShouldStreamFileRowBoundaries() {
        assertFalse(I2PSnarkServlet.shouldStreamFileRows(0));
        assertFalse(I2PSnarkServlet.shouldStreamFileRows(63));
        assertTrue(I2PSnarkServlet.shouldStreamFileRows(64));
        assertTrue(I2PSnarkServlet.shouldStreamFileRows(Integer.MAX_VALUE));
    }

    @Test
    public void testTorrentGateLowerThanFileGate() {
        // heavier rows must stream at least as early as lighter ones
        assertTrue(I2PSnarkServlet.shouldStreamTorrentRows(32)
                   && !I2PSnarkServlet.shouldStreamFileRows(32));
    }

    @Test
    public void testDrainToWritesFlushesAndClears() {
        StringWriter sink = new StringWriter();
        PrintWriter out = new PrintWriter(sink);
        StringBuilder buf = new StringBuilder();

        buf.append("chunk-one");
        assertTrue(I2PSnarkServlet.drainTo(out, buf));
        assertEquals("chunk-one", sink.toString());
        assertEquals(0, buf.length());
        assertFalse(out.checkError());

        // second drain appends in order; buffer stays empty
        buf.append("two");
        assertTrue(I2PSnarkServlet.drainTo(out, buf));
        assertEquals("chunk-onetwo", sink.toString());
        assertEquals(0, buf.length());
    }

    @Test
    public void testDrainToEmptyBufferIsNoop() {
        StringWriter sink = new StringWriter();
        PrintWriter out = new PrintWriter(sink);
        assertTrue(I2PSnarkServlet.drainTo(out, new StringBuilder()));
        assertEquals("", sink.toString());
    }

    /** Writer that fails on every write/flush, standing in for a gone client. */
    private static class FailingWriter extends java.io.Writer {
        @Override public void write(char[] cbuf, int off, int len) throws java.io.IOException {throw new java.io.IOException("client gone");}
        @Override public void flush() throws java.io.IOException {throw new java.io.IOException("client gone");}
        @Override public void close() {}
    }

    @Test
    public void testDrainToDetectsDisconnectedClient() {
        PrintWriter out = new PrintWriter(new FailingWriter());
        StringBuilder buf = new StringBuilder("payload");
        // false signals the disconnect; buffer is still cleared so no
        // stale chunk leaks into a later drain attempt
        assertFalse(I2PSnarkServlet.drainTo(out, buf));
        assertEquals(0, buf.length());
    }
}
