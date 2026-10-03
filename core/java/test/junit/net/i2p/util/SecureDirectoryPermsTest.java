package net.i2p.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for log-directory and log-file permission handling.
 *
 * <p>{@code SecureDirectory} forces a newly created directory to 700, which
 * silently defeated group-readable logs: {@code FileLogWriter} applied 660 to the
 * log <i>file</i> but created the parent at 700, and a group cannot read a file it
 * cannot traverse. {@code rotateFile()} also read the group-readable setting only
 * <i>after</i> creating the directory, so the setting could never have reached the
 * parent even if the parent had been widened.
 *
 * <p>Skipped on non-POSIX filesystems, where neither mode can be set.
 *
 * @since 0.9.71+
 */
public class SecureDirectoryPermsTest {

    private static boolean posix() {
        return SecureFileOutputStream.canSetPerms() && SecureDirectory.isNotWindows;
    }

    private static Set<java.nio.file.attribute.PosixFilePermission> perms(File f) throws Exception {
        return Files.getPosixFilePermissions(f.toPath());
    }

    /** A private log directory stays owner-only: the default must not change. */
    @Test
    public void testDefaultDirectoryIsOwnerOnly() throws Exception {
        if (!posix()) return;
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-secdir-test-" + System.nanoTime());
        try {
            assertTrue(new SecureDirectory(dir.getAbsolutePath()).mkdir());
            Set<java.nio.file.attribute.PosixFilePermission> p = perms(dir);
            assertTrue("owner must retain rwx, got " + p,
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            assertFalse("group must not gain access by default, got " + p,
                        p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ) ||
                        p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) ||
                        p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE));
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * The regression: a group-accessible log directory must be created 770,
     * including the group execute bit that makes the files inside reachable.
     */
    @Test
    public void testGroupPermsDirectoryIs770() throws Exception {
        if (!posix()) return;
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-secdir-grp-" + System.nanoTime());
        try {
            assertTrue(new SecureDirectory(dir.getAbsolutePath(), true).mkdir());
            Set<java.nio.file.attribute.PosixFilePermission> p = perms(dir);
            assertTrue("group needs rwx (770), got " + p,
                       p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE));
            assertTrue("owner must keep rwx, got " + p,
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE) &&
                       p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            assertFalse("others must stay excluded, got " + p,
                        p.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ) ||
                        p.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE) ||
                        p.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
        } finally {
            deleteTree(dir);
        }
    }

    /** mkdirs() must apply the same mode as mkdir(). */
    @Test
    public void testGroupPermsMkdirsIs770() throws Exception {
        if (!posix()) return;
        File base = new File(System.getProperty("java.io.tmpdir"),
                             "i2p-secdirs-test-" + System.nanoTime());
        try {
            assertTrue(new SecureDirectory(base.getAbsolutePath(), true).mkdirs());
            Set<java.nio.file.attribute.PosixFilePermission> p = perms(base);
            assertTrue("group needs execute, got " + p,
                       p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE));
        } finally {
            deleteTree(base);
        }
    }

    /** The parent/child constructor overloads must honour the flag too. */
    @Test
    public void testGroupPermsChildOverloads() throws Exception {
        if (!posix()) return;
        File base = new File(System.getProperty("java.io.tmpdir"),
                             "i2p-secdir-child-" + System.nanoTime());
        File kid = new File(base, "sub");
        try {
            assertTrue(new SecureDirectory(base.getAbsolutePath(), "sub", true).mkdirs());
            assertTrue("group needs execute, got " + perms(kid),
                       perms(kid).contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE));
        } finally {
            deleteTree(base);
        }
    }

    /**
     * A 770 parent is exactly what makes a 660 log file reachable by the group,
     * which is the pairing the writer depends on.
     */
    @Test
    public void testGroupTraversableMatchesGroupExecute() throws Exception {
        if (!posix()) return;
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-secdir-trav-" + System.nanoTime());
        try {
            assertTrue(new SecureDirectory(dir.getAbsolutePath(), true).mkdir());
            assertTrue("a 770 directory must read as group-traversable",
                       FileLogWriter.isGroupTraversable(dir));

            File priv = new File(System.getProperty("java.io.tmpdir"),
                                 "i2p-secdir-priv-" + System.nanoTime());
            assertTrue(new SecureDirectory(priv.getAbsolutePath()).mkdir());
            assertFalse("a 700 directory must not read as group-traversable",
                        FileLogWriter.isGroupTraversable(priv));
            deleteTree(priv);
        } finally {
            deleteTree(dir);
        }
    }

    /** A group-readable file inside a 770 directory is genuinely group-openable. */
    @Test
    public void testGroupReadableFileInsideGroupDirectory() throws Exception {
        if (!posix()) return;
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-secdir-combo-" + System.nanoTime());
        try {
            assertTrue(new SecureDirectory(dir.getAbsolutePath(), true).mkdir());
            File log = new File(dir, "log-router-0.txt");
            assertTrue(log.createNewFile());
            SecureFileOutputStream.setGroupPerms(log);
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), perms(log));
            assertTrue(FileLogWriter.isGroupTraversable(log.getParentFile()));
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
