package net.i2p.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that log/config files get 660 (owner+group rw) permissions
 * when group-readable mode is requested, and never world-readable.
 *
 * @since 0.9.70+
 */
public class SecureFileOutputStreamTest {

    private File _f;

    @Before
    public void setUp() throws IOException {
        _f = File.createTempFile("i2psec", ".tmp");
        _f.deleteOnExit();
    }

    /** POSIX attribute view present? Skip silently otherwise. */
    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * setGroupPerms must yield exactly 660: owner and group rw, owner/group
     * execute and all other/world bits cleared. This is the permission log
     * rotation relies on for group inspection without opening up to the world.
     */
    @Test
    public void testSetGroupPermsYields660() throws IOException {
        Assume.assumeTrue("supports POSIX permissions", isPosix());

        // start from a world-readable state to prove the call narrows it
        Files.setPosixFilePermissions(_f.toPath(), java.util.EnumSet.allOf(PosixFilePermission.class));
        assertTrue(_f.canRead() && _f.canWrite());

        SecureFileOutputStream.setGroupPerms(_f);

        java.util.Set<PosixFilePermission> perms = Files.getPosixFilePermissions(_f.toPath());
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(perms.contains(PosixFilePermission.GROUP_READ));
        assertTrue(perms.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_EXECUTE));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
    }
}
