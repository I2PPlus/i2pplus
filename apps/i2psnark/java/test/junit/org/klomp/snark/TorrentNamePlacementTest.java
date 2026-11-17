package org.klomp.snark;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that a torrent's own name cannot move its data outside the download
 * directory. The name is written by whoever made the torrent, and the "preserve
 * original file names" option uses it as given, so ".." is a direction rather
 * than a name and lands the download in the parent of the download directory -
 * on a router, the config directory it reads at every start.
 *
 * @since 0.9.72+
 */
public class TorrentNamePlacementTest {

    /** A real directory to resolve against, so the canonical checks mean something. */
    private File _dataDir;

    @Before
    public void setUp() throws IOException {
        _dataDir = createTempDir("snark-names-");
    }

    @After
    public void tearDown() {
        deleteTree(_dataDir);
    }

    @Test
    public void reservedNamesAreDirectionsNotNames() {
        assertFalse(Snark.isSingleComponentName(null));
        assertFalse(Snark.isSingleComponentName(""));
        assertFalse(Snark.isSingleComponentName(" "));
        assertFalse(Snark.isSingleComponentName("."));
        assertFalse(Snark.isSingleComponentName(".."));
    }

    @Test
    public void aSeparatorMakesItAPathNotAName() {
        assertFalse(Snark.isSingleComponentName("a/b"));
        assertFalse(Snark.isSingleComponentName("a\\b"));
        assertFalse(Snark.isSingleComponentName("../x"));
        assertFalse(Snark.isSingleComponentName("/etc"));
    }

    @Test
    public void ordinaryNamesAreLeftAlone() {
        // preserve mode is the point: only the four reserved forms and a
        // separator are refused, everything else keeps the author's spelling
        assertTrue(Snark.isSingleComponentName("movie.mkv"));
        assertTrue(Snark.isSingleComponentName("..hidden"));
        assertTrue(Snark.isSingleComponentName("a..b"));
        assertTrue(Snark.isSingleComponentName("con"));
        assertTrue(Snark.isSingleComponentName("trailing "));
    }

    @Test
    public void theDotDotTorrentLandsOutsideTheDataDir() throws IOException {
        // the finding's shape: a torrent named ".." is stored one level up, and
        // the name the filter substitutes for it is not
        assertFalse(Snark.isDirectChild(new File(_dataDir, ".."), _dataDir));
        assertTrue(Snark.isDirectChild(new File(_dataDir, Storage.filterName("..")), _dataDir));
    }

    @Test
    public void aNameWithASeparatorEscapesTheDataDir() throws IOException {
        assertFalse(Snark.isDirectChild(new File(_dataDir, "sub/x"), _dataDir));
        assertFalse(Snark.isDirectChild(new File(_dataDir, "../../x"), _dataDir));
        assertTrue(Snark.isDirectChild(new File(_dataDir, "x"), _dataDir));
    }

    /**
     * Make a fresh temporary directory.
     *
     * @param prefix the name prefix
     * @return the new directory
     * @throws IOException if it could not be created
     */
    private static File createTempDir(String prefix) throws IOException {
        File f = File.createTempFile(prefix, "");
        assertTrue(f.delete());
        assertTrue(f.mkdir());
        return f;
    }

    /**
     * Remove a directory and everything under it.
     *
     * @param f the file or directory to remove, may be null
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
