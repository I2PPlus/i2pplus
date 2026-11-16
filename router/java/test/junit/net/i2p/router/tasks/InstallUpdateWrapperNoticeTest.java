package net.i2p.router.tasks;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import net.i2p.util.SystemVersion;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the startup notice about a wrapper release an update staged but
 * could not install. Pins the version comparison, the cases where nothing is
 * reported, and reading the staged release out of the staged version.txt.
 *
 * @since 0.9.72+
 */
public class InstallUpdateWrapperNoticeTest {

    /**
     * This test class holds only static checks against the staged wrapper
     * release, so it carries no state of its own.
     */
    public InstallUpdateWrapperNoticeTest() {}

    /**
     * Holds the fake install directories the staged-version tests read from.
     */
    @Rule
    public TemporaryFolder _tmp = new TemporaryFolder();

    /**
     * Write a staged version.txt the way the updater writes it.
     *
     * @param name the file name to create under the temporary folder
     * @param contents the exact contents to write
     * @return the file written
     * @throws Exception on a temporary folder or write failure
     */
    private File write(String name, String contents) throws Exception {
        File f = _tmp.newFile(name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(contents);
        }
        return f;
    }

    /**
     * The same release compares equal in both directions, and a higher field
     * wins from the leftmost difference.
     */
    @Test
    public void testCompareVersions() {
        assertEquals(0, InstallUpdate.compareVersions("3.7.4", "3.7.4"));
        assertTrue(InstallUpdate.compareVersions("3.7.4", "3.7.3") > 0);
        assertTrue(InstallUpdate.compareVersions("3.7.3", "3.7.4") < 0);
        assertTrue(InstallUpdate.compareVersions("4.0.0", "3.9.9") > 0);
    }

    /**
     * Fields are compared as numbers, not as text, so 3.10 is newer than 3.9 and
     * not merely longer.
     */
    @Test
    public void testCompareVersionsIsNumericNotLexical() {
        assertTrue(InstallUpdate.compareVersions("3.10", "3.9") > 0);
        assertTrue(InstallUpdate.compareVersions("3.9", "3.10") < 0);
    }

    /**
     * A field missing on one side is zero, so a two field release equals its
     * three field spelling.
     */
    @Test
    public void testCompareVersionsPadsMissingFields() {
        assertEquals(0, InstallUpdate.compareVersions("3.7", "3.7.0"));
        assertEquals(0, InstallUpdate.compareVersions("3.7.0", "3.7"));
        assertTrue(InstallUpdate.compareVersions("3.7.1", "3.7") > 0);
    }

    /**
     * A field that is not a plain number compares as zero, which is what makes a
     * suffixed version such as a dated build read as older than the release it
     * names rather than throwing.
     */
    @Test
    public void testCompareVersionsUnparsableField() {
        assertTrue(InstallUpdate.compareVersions("3.7.4_20261023", "3.7.4") < 0);
        assertEquals(0, InstallUpdate.compareVersions("3.7.x", "3.7"));
    }

    /**
     * A staged release equal to, or older than, the running wrapper is not an
     * update waiting to be installed.
     */
    @Test
    public void testNothingReportedWhenStagedIsNotNewer() {
        assertNull(InstallUpdate.wrapperUpdateNotice("3.7.3", "3.7.3"));
        assertNull(InstallUpdate.wrapperUpdateNotice("3.7.3", "3.7.4"));
    }

    /**
     * Nothing to report when there is no staged release, and nothing when the
     * router is not running under a wrapper at all, as under runplain.sh.
     */
    @Test
    public void testNothingReportedWithoutBothVersions() {
        assertNull(InstallUpdate.wrapperUpdateNotice(null, "3.7.3"));
        assertNull(InstallUpdate.wrapperUpdateNotice("3.7.4", null));
        assertNull(InstallUpdate.wrapperUpdateNotice(null, null));
    }

    /**
     * The notice names the script for this platform and both versions, so the
     * reader sees what is installed and what to run without opening a shell.
     */
    @Test
    public void testNoticeNamesTheScriptAndBothVersions() {
        String msg = InstallUpdate.wrapperUpdateNotice("3.7.4", "3.7.3");
        assertNotNull(msg);
        assertTrue(msg, msg.contains("install-wrapper."));
        assertTrue(msg, msg.contains("Staged: 3.7.4"));
        assertTrue(msg, msg.contains("Running: 3.7.3"));
    }

    /**
     * An update extracts its files without the exec bit, so the unix notice has
     * to name an interpreter rather than a path the reader cannot run.
     */
    @Test
    public void testUnixNoticeIsRunnableAsExtracted() {
        String msg = InstallUpdate.wrapperUpdateNotice("3.7.4", "3.7.3");
        assertNotNull(msg);
        if (SystemVersion.isWindows())
            assertTrue(msg, msg.contains(".\\install-wrapper.ps1"));
        else
            assertTrue(msg, msg.contains("sh ./install-wrapper.sh"));
    }

    /**
     * The staged version.txt is read as the properties file the updater writes.
     * @throws Exception on a temporary folder or a failed write
     */
    @Test
    public void testStagedVersionRead() throws Exception {
        File f = write("version.txt", "WRAPPER_VERSION=3.7.4\n");
        assertEquals("3.7.4", InstallUpdate.stagedWrapperVersion(f));
    }

    /**
     * Surrounding blanks in the file do not become part of the version, since
     * the comparison parses the field.
     * @throws Exception on a temporary folder or a failed write
     */
    @Test
    public void testStagedVersionTrimmed() throws Exception {
        File f = write("spaces.txt", "WRAPPER_VERSION =  3.7.4  \n");
        assertEquals("3.7.4", InstallUpdate.stagedWrapperVersion(f));
    }

    /**
     * No staged directory, and a staged file that names no wrapper release, both
     * report nothing rather than an empty version.
     * @throws Exception on a temporary folder or a failed write
     */
    @Test
    public void testStagedVersionMissingOrUnrelated() throws Exception {
        assertNull(InstallUpdate.stagedWrapperVersion(new File(_tmp.getRoot(), "absent.txt")));
        File other = write("other.txt", "SOMETHING_ELSE=1\n");
        assertNull(InstallUpdate.stagedWrapperVersion(other));
    }
}
