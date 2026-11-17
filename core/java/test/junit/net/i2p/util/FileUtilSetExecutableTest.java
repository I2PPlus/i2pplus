package net.i2p.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for adding the execute bit to a file an update wrote. Pins that a
 * non-runnable file is made runnable, that a runnable one is left exactly as it
 * was rather than widened to every account, and that a missing file is reported
 * rather than thrown over.
 *
 * @since 0.9.72+
 */
public class FileUtilSetExecutableTest {

    /**
     * This test class holds only static checks against files in a temporary
     * folder, so it carries no state of its own.
     */
    public FileUtilSetExecutableTest() {}

    /**
     * Holds the files whose permissions the tests read back.
     */
    @Rule
    public TemporaryFolder _tmp = new TemporaryFolder();

    /**
     * Not runnable for anyone: the form a file extracted from an update arrives
     * in, and the one the execute bit is added to.
     */
    private static final Set<PosixFilePermission> READ_WRITE_ONLY =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                   PosixFilePermission.GROUP_READ);

    /**
     * Runnable, and only for its owner: what an install's own umask gives a
     * script, and what must not be widened by setting the bit again.
     */
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                   PosixFilePermission.OWNER_EXECUTE);

    /**
     * A file written by an update, which is not runnable until the execute bit is
     * added, becomes runnable.
     *
     * @throws Exception on a temporary folder or write failure
     */
    @Test
    public void testAddsTheBitToANonRunnableFile() throws Exception {
        Assume.assumeFalse("windows has no execute bit to add", SystemVersion.isWindows());
        File f = _tmp.newFile("install-wrapper.sh");
        Path path = f.toPath();
        Files.setPosixFilePermissions(path, READ_WRITE_ONLY);
        assertFalse("the fresh file should not be runnable yet", f.canExecute());
        assertTrue(FileUtil.setExecutable(f));
        assertTrue(f.canExecute());
    }

    /**
     * A file that is already runnable keeps the permissions it has: setting the
     * bit again would add it for the group and for everyone else.
     *
     * @throws Exception on a temporary folder, write or attribute failure
     */
    @Test
    public void testLeavesARunnableFileAlone() throws Exception {
        Assume.assumeFalse("windows has no execute bit to compare", SystemVersion.isWindows());
        File f = _tmp.newFile("already-runnable.sh");
        Path path = f.toPath();
        Files.setPosixFilePermissions(path, OWNER_EXECUTABLE);
        assertTrue(f.canExecute());
        assertTrue(FileUtil.setExecutable(f));
        assertEquals(OWNER_EXECUTABLE, Files.getPosixFilePermissions(path));
    }

    /**
     * A file that is not there is reported as not runnable, and nothing is
     * created in its place.
     *
     * @throws Exception on a temporary folder failure
     */
    @Test
    public void testMissingFileReportsFalse() throws Exception {
        File missing = new File(_tmp.getRoot(), "install-wrapper.ps1");
        assertFalse(FileUtil.setExecutable(missing));
        assertFalse(missing.exists());
    }
}
