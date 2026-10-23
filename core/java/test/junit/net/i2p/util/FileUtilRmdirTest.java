package net.i2p.util;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

/**
 * Tests for {@link FileUtil#rmdir(File, boolean)}, the recursive delete helper.
 *
 * <p>This replaces the {@code testRmdir()} helper that used to sit commented out
 * at the bottom of {@code FileUtil}. That helper built a nested tree and printed
 * PASS/FAIL from {@code main()}; it never ran as part of any build, and the
 * {@code failIfNotEmpty} argument was always passed as {@code false}, so the
 * branch that refuses to delete a populated directory went untested.
 *
 * <p>All trees are created under {@code java.io.tmpdir} rather than the working
 * directory, so a failing test cannot leave debris in the source tree.
 *
 * @since 0.9.71+
 */
public class FileUtilRmdirTest {

    /**
     * Build {@code root/a/b/c} and return {@code root}. Every directory in the
     * chain is created, so the test fails loudly if the fixture is malformed
     * rather than silently testing a shallower tree than intended.
     *
     * @param root the temporary directory the nested tree is built under
     * @param name the leaf path below root, intermediate directories included
     * @return root unchanged, so the caller can assert and clean up against it
     */
    private static File makeTree(File root, String name) {
        File leaf = new File(root, name);
        assertTrue("could not create " + leaf, leaf.mkdirs());
        return root;
    }

    private static void assertGone(File f, String what) {
        assertFalse(what + " should have been deleted", f.exists());
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /**
     * The original scenario: a nested tree disappears completely, and the
     * deepest leaf - which is deleted before any of its parents - is gone too.
     *
     * @throws Exception on temp-directory creation or cleanup failure
     */
    @Test
    public void testRecursiveDeleteRemovesNestedTree() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                             "i2p-rmdir-nested-" + System.nanoTime());
        try {
            File leaf = new File(new File(new File(root, "test"), "subdir"), "blah");
            assertTrue("could not create " + leaf, leaf.mkdirs());
            assertTrue(leaf.isDirectory());

            assertTrue("recursive delete should succeed",
                       FileUtil.rmdir(root, false));

            assertGone(root, "tree root");
            assertGone(leaf, "deepest leaf");
            assertGone(new File(root, "test"), "intermediate directory");
        } finally {
            deleteTree(root);
        }
    }

    /**
     * A plain file is deleted directly rather than descended into; this is the
     * {@code !target.isDirectory()} branch.
     *
     * @throws Exception on temp-file creation or cleanup failure
     */
    @Test
    public void testSingleFileIsDeleted() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-rmdir-file-" + System.nanoTime());
        try {
            assertTrue(dir.mkdir());
            File f = new File(dir, "data.bin");
            assertTrue(f.createNewFile());

            assertTrue(FileUtil.rmdir(f, false));
            assertGone(f, "plain file");
            assertTrue("parent directory must be left alone", dir.isDirectory());
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * {@code failIfNotEmpty=true} must refuse a populated directory and leave it
     * fully intact. The original commented helper never passed {@code true},
     * so nothing pinned this behaviour.
     *
     * @throws Exception on temp-directory creation or cleanup failure
     */
    @Test
    public void testFailIfNotEmptyRefusesPopulatedDirectory() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                             "i2p-rmdir-nonempty-" + System.nanoTime());
        try {
            File leaf = new File(new File(root, "a"), "b");
            assertTrue(leaf.mkdirs());

            assertFalse("populated directory must not be deleted when failIfNotEmpty",
                        FileUtil.rmdir(root, true));

            assertTrue("refused delete must leave the directory in place", root.isDirectory());
            assertTrue("refused delete must leave the nested leaf in place", leaf.isDirectory());

            // failIfNotEmpty=false on the same tree does remove it
            assertTrue(FileUtil.rmdir(root, false));
            assertGone(root, "tree root");
        } finally {
            deleteTree(root);
        }
    }

    /**
     * An empty directory is removable even when failIfNotEmpty is set.
     *
     * @throws Exception on temp-directory creation or cleanup failure
     */
    @Test
    public void testEmptyDirectoryIsDeletedWithFailIfNotEmpty() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-rmdir-empty-" + System.nanoTime());
        try {
            assertTrue(dir.mkdir());
            assertTrue(FileUtil.rmdir(dir, true));
            assertGone(dir, "empty directory");
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * A path that does not exist reports success. This keeps repeated cleanup
     * idempotent, which is what lets callers use it without checking first.
     *
     * @throws Exception on temp-directory path setup failure
     */
    @Test
    public void testMissingPathReportsSuccess() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "i2p-rmdir-missing-" + System.nanoTime());
        assertFalse(dir.exists());
        assertTrue(FileUtil.rmdir(dir, false));
        assertTrue(FileUtil.rmdir(dir, true));
    }
}