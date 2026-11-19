package org.klomp.snark;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins how a torrent's browsable path is built.
 *
 * <p>The path was assembled by escaping the storage name as HTML and then replacing
 * four characters by hand - '[', ']', '|' and space - plus three accented letters by
 * HTML entities. That is not a URL encoder: '#', '%', '&amp;', '?' and any non-ASCII
 * name other than those three letters went into the href raw, and an '&amp;' in a file
 * name reached it as '&amp;amp;', which the browser resolves to a different path than
 * the file on disk. The desktop notification was handed that same HTML fragment as its
 * click target.
 *
 * @since 0.9.72+
 */
public class TorrentURLTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public TorrentURLTest() {}

    /** A name carrying most of the characters a URL has to survive. */
    private static final String NAME = "My Torrent [v2] #1|50%.txt";

    /** The same name with a letter outside ASCII in it. */
    private static final String ACCENTED = "Fichier \u00e9t\u00e9.txt";

    /** The webapp path the manager under test was built with. */
    private static final String CONTEXT = "/i2psnark";

    /** The manager whose paths are checked. */
    private SnarkManager mgr;

    /** The context it runs in, standalone so nothing reaches the network. */
    private I2PAppContext ctx;

    /** The scratch directory the config and metadata are written to. */
    private File baseDir;

    /**
     * A manager deployed at /i2psnark in a scratch directory.
     */
    @Before
    public void setUp() {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-snark-url-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, CONTEXT, "test-torrent-url");
    }

    /**
     * Remove the scratch directory.
     */
    @After
    public void tearDown() {
        if (baseDir != null) {
            try { net.i2p.util.FileUtil.rmdir(baseDir, false); } catch (Exception ignore) {}
        }
    }

    /**
     * A torrent with the given storage name.
     *
     * @param baseName the name of the file or directory on disk
     * @param multiFile whether it holds more than one file
     * @param complete whether every byte of it is here
     * @return a torrent the manager can be asked about
     */
    private static Snark snark(String baseName, boolean multiFile, boolean complete) {
        MetaInfo meta = mock(MetaInfo.class);
        when(meta.getFiles()).thenReturn(multiFile ? Collections.<List<String>>emptyList() : null);
        Storage storage = mock(Storage.class);
        when(storage.getBaseName()).thenReturn(baseName);
        when(storage.complete()).thenReturn(complete);
        Snark snark = mock(Snark.class);
        when(snark.getMetaInfo()).thenReturn(meta);
        when(snark.getStorage()).thenReturn(storage);
        return snark;
    }

    /**
     * The path for one torrent, with the webapp prefix taken off so the assertions
     * below are about the encoded name alone.
     *
     * @param snark the torrent to ask about
     * @return the encoded path below the webapp root
     */
    private String encoded(Snark snark) {
        String path = mgr.getTorrentURL(snark);
        assertTrue("the path does not start at the webapp root: " + path,
                   path.startsWith(CONTEXT + '/'));
        return path.substring(CONTEXT.length() + 1);
    }

    /**
     * The defect: a name with a space, brackets, '#', '|' or '%' was put in the href
     * mostly as it came, so the link did not address the file.
     */
    @Test
    public void testASpecialCharacterNameIsEncoded() {
        String enc = encoded(snark(NAME, false, true));
        for (char c : new char[] {' ', '[', ']', '|', '#', '&', '?'}) {
            assertFalse("the encoded path still holds a raw " + c + ": " + enc,
                        enc.indexOf(c) >= 0);
        }
        // '%' is the escape character itself, so it appears in the encoded form -
        // what must not appear is one that does not start an escape
        for (int i = enc.indexOf('%'); i >= 0; i = enc.indexOf('%', i + 1)) {
            boolean escaped = i + 2 < enc.length()
                              && Character.digit(enc.charAt(i + 1), 16) >= 0
                              && Character.digit(enc.charAt(i + 2), 16) >= 0;
            assertTrue("a bare % at " + i + " in " + enc, escaped);
        }
    }

    /**
     * What encoding has to guarantee: the browser's decode gives the file's real
     * name back, not something near it.
     *
     * @throws Exception if the decode reports an unsupported encoding
     */
    @Test
    public void testTheEncodedNameDecodesToTheFileOnDisk() throws Exception {
        String enc = encoded(snark(NAME, false, true));
        assertEquals(NAME, URLDecoder.decode(enc, "UTF-8"));
        String accented = encoded(snark(ACCENTED, false, true));
        assertEquals(ACCENTED, URLDecoder.decode(accented, "UTF-8"));
    }

    /**
     * A multi-file torrent is a directory, so the path has to end in a slash for the
     * listing to be requested rather than a file of that name.
     *
     * @throws Exception if the decode reports an unsupported encoding
     */
    @Test
    public void testAMultiFileTorrentPathEndsInASlash() throws Exception {
        String enc = encoded(snark(NAME, true, true));
        assertTrue("no trailing slash on a directory path: " + enc, enc.endsWith("/"));
        assertEquals(NAME + '/', URLDecoder.decode(enc, "UTF-8"));
    }

    /**
     * A torrent that is still downloading shows its partial directory the same way.
     *
     * @throws Exception if the decode reports an unsupported encoding
     */
    @Test
    public void testAnIncompleteTorrentPathEndsInASlash() throws Exception {
        String enc = encoded(snark(NAME, false, false));
        assertTrue("no trailing slash on a partial path: " + enc, enc.endsWith("/"));
        assertEquals(NAME + '/', URLDecoder.decode(enc, "UTF-8"));
    }

    /**
     * A complete single-file torrent links to the file itself, with no slash after it.
     */
    @Test
    public void testACompleteSingleFileTorrentPathHasNoSlash() {
        String enc = encoded(snark(NAME, false, true));
        assertFalse("a slash after a file path: " + enc, enc.endsWith("/"));
    }

    /**
     * A magnet, or a torrent whose metainfo or storage is gone, has no file to link
     * to, and the path is the webapp root rather than a guess at a name.
     */
    @Test
    public void testATorrentWithoutMetaInfoIsTheWebappRoot() {
        Snark snark = mock(Snark.class);
        when(snark.getMetaInfo()).thenReturn(null);
        when(snark.getStorage()).thenReturn(null);
        when(snark.getBaseName()).thenReturn(NAME);
        assertEquals(CONTEXT + '/', mgr.getTorrentURL(snark));
    }

    /**
     * Deployed at the root, the context path is already "/" and adding another slash
     * makes the link protocol-relative - the browser reads it as a host name, not a
     * path.
     */
    @Test
    public void testTheRootContextDoesNotDoubleTheSlash() {
        SnarkManager root = new SnarkManager(ctx, "/", "test-torrent-url-root");
        String path = root.getTorrentURL(snark(NAME, false, true));
        assertTrue("the path does not start at the root: " + path, path.startsWith("/"));
        assertFalse("a protocol-relative path: " + path, path.startsWith("//"));
    }
}
