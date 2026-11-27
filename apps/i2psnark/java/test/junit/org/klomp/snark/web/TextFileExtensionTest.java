package org.klomp.snark.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * The downloads the file list offers to the browser as text, and the case the
 * name is read in.
 *
 * <p>Most of the decision is the MIME type the console detects, and the rest is
 * a table of plain-text extensions written in lower case - the ones the detector
 * does not know on its own. A name is matched against that table after a fold,
 * and a fold that follows the JVM's default locale makes the answer depend on
 * the machine: under a Turkish default locale an upper-case I folds to a dotless
 * ı, so a torrent carrying SETUP.INI stops matching its own extension and the
 * file is offered as a download instead of shown.
 *
 * @since 0.9.72+
 */
public class TextFileExtensionTest {

    /** The decision is a static one over the name and its type. */
    public TextFileExtensionTest() {}

    /**
     * A type the console detects as text is text whatever the name is, and an
     * empty type - nothing detected - is decided by the extension alone, so a
     * name that only looks like a type is not text.
     */
    @Test
    public void testDetectedTypesAreText() {
        assertTrue(I2PSnarkServlet.isTextFile("notes", "text/plain"));
        assertTrue(I2PSnarkServlet.isTextFile("data", "application/json"));
        assertTrue(I2PSnarkServlet.isTextFile("feed", "application/xml"));
        assertTrue(I2PSnarkServlet.isTextFile("app", "application/javascript"));
        assertFalse(I2PSnarkServlet.isTextFile("text/plain", ""));
        assertFalse(I2PSnarkServlet.isTextFile("movie.mkv", ""));
    }

    /**
     * The extensions the detector does not know are still text, in whatever case
     * the torrent's author wrote them in.
     */
    @Test
    public void testPlainTextExtensionsAreTextInAnyCase() {
        assertTrue(I2PSnarkServlet.isTextFile("README.asc", ""));
        assertTrue(I2PSnarkServlet.isTextFile("AUTOEXEC.BAT", ""));
        assertTrue(I2PSnarkServlet.isTextFile("config.INI", ""));
        assertTrue(I2PSnarkServlet.isTextFile("checksums.MD5", ""));
        assertTrue(I2PSnarkServlet.isTextFile("run.SH", ""));
        assertTrue(I2PSnarkServlet.isTextFile("shortcut.URL", ""));
    }

    /**
     * A name that is only similar to one of the extensions is not text - the
     * match is the end of the name, not a part of it, so a directory called
     * reports.ini/ stays a directory listing.
     */
    @Test
    public void testOnlyTheEndOfTheNameIsMatched() {
        assertFalse(I2PSnarkServlet.isTextFile("ini", ""));
        assertFalse(I2PSnarkServlet.isTextFile("report.inif", ""));
        assertFalse(I2PSnarkServlet.isTextFile("disk.img", ""));
        assertFalse(I2PSnarkServlet.isTextFile("archive.zip", "application/zip"));
    }

    /**
     * Under a Turkish default locale the table still has to match: ".INI" folds
     * to a dotless ı otherwise, and the file is offered as a download.
     */
    @Test
    public void testExtensionsMatchUnderTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(I2PSnarkServlet.isTextFile("SETUP.INI", ""));
            assertTrue(I2PSnarkServlet.isTextFile("config.INI", ""));
            assertTrue(I2PSnarkServlet.isTextFile("AUTOEXEC.BAT", ""));
            assertFalse(I2PSnarkServlet.isTextFile("disk.img", ""));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
