package net.i2p.router.tasks;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import net.i2p.util.SystemVersion;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for the execute bit put on the install-wrapper scripts an update stages.
 * Pins that both scripts an update leaves behind are made runnable, that a script
 * already runnable is not widened to every account, and that an install holding
 * only one of the two is left alone rather than failing.
 *
 * @since 0.9.72+
 */
public class InstallUpdateWrapperScriptTest {

    /**
     * This test class holds only static checks against a fake install directory,
     * so it carries no state of its own.
     */
    public InstallUpdateWrapperScriptTest() {}

    /**
     * Holds the fake install directory the scripts are staged in.
     */
    @Rule
    public TemporaryFolder _tmp = new TemporaryFolder();

    /**
     * Runnable, and only for its owner: what an install's own umask gives a
     * script, and what must not be widened by setting the bit again.
     */
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                   PosixFilePermission.OWNER_EXECUTE);

    /**
     * Both scripts an update stages at the install root become runnable, so the
     * line the startup notice prints works as written.
     *
     * @throws Exception on a temporary folder or write failure
     */
    @Test
    public void testStagedScriptsBecomeRunnable() throws Exception {
        Assume.assumeFalse("windows has no execute bit to add", SystemVersion.isWindows());
        File base = _tmp.getRoot();
        File sh = _tmp.newFile("install-wrapper.sh");
        File ps1 = _tmp.newFile("install-wrapper.ps1");
        assertFalse("a staged file should not be runnable yet", sh.canExecute());
        assertFalse("a staged file should not be runnable yet", ps1.canExecute());
        InstallUpdate.makeWrapperScriptsExecutable(base);
        assertTrue(sh.canExecute());
        assertTrue(ps1.canExecute());
    }

    /**
     * A script the install already made runnable keeps the permissions it has, so
     * the fix does not widen an install that set its own modes.
     *
     * @throws Exception on a temporary folder, write or attribute failure
     */
    @Test
    public void testRunnableScriptIsNotWidened() throws Exception {
        Assume.assumeFalse("windows has no execute bit to compare", SystemVersion.isWindows());
        File sh = _tmp.newFile("install-wrapper.sh");
        Path path = sh.toPath();
        Files.setPosixFilePermissions(path, OWNER_EXECUTABLE);
        InstallUpdate.makeWrapperScriptsExecutable(_tmp.getRoot());
        assertEquals(OWNER_EXECUTABLE, Files.getPosixFilePermissions(path));
    }

    /**
     * An install holding neither script, or only the Windows one, is passed over:
     * nothing is created and nothing is thrown.
     *
     * @throws Exception on a temporary folder or write failure
     */
    @Test
    public void testMissingScriptsArePassedOver() throws Exception {
        File empty = _tmp.newFolder("empty-install");
        InstallUpdate.makeWrapperScriptsExecutable(empty);
        assertEquals("no script should have been created", 0, empty.list().length);
        File ps1 = _tmp.newFile("install-wrapper.ps1");
        InstallUpdate.makeWrapperScriptsExecutable(_tmp.getRoot());
        assertTrue(ps1.canExecute());
        assertFalse("the unix script was not staged, so it should not appear",
                    new File(_tmp.getRoot(), "install-wrapper.sh").exists());
    }
}
