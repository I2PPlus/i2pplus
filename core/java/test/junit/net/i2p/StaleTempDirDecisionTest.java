package net.i2p;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Which leftover scratch directories the start-up cleanup may delete.
 * <p>
 * The cleanup runs on the first {@code getTempDir()} and sweeps the temp
 * parent, which by default is the system temp dir - a directory every local
 * user may write to. Deleting every entry that is merely named like one of
 * ours let any of them put a link there pointing at our own config directory,
 * and the recursive delete followed links, so the next start emptied the
 * router's own configuration. An entry is deleted only when it is a real
 * directory, owned by the same user that owns our config directory, and
 * untouched for long enough that no JVM running here can be using it.
 *
 * @since 0.9.72+
 */
public class StaleTempDirDecisionTest {

    /** A day, the unit the ages below are counted in. */
    private static final long DAY = 24 * 60 * 60 * 1000L;

    /** Runs the decision; it holds no state. */
    public StaleTempDirDecisionTest() {}

    /** A directory we made a moment ago is in use and stays. */
    @Test
    public void aFreshDirectoryIsStillInUse() {
        File root = tempDir("fresh");
        try {
            assertFalse(I2PAppContext.isStaleOwnTempDir(root, root, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(root);
        }
    }

    /**
     * A directory of ours that has gone untouched for well over the keep-alive
     * interval is a leftover from a session that ended without cleaning up.
     */
    @Test
    public void anOldDirectoryOfOursIsStale() {
        File root = tempDir("old");
        try {
            backdateAge(root, 3 * DAY);
            assertTrue(I2PAppContext.isStaleOwnTempDir(root, root, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(root);
        }
    }

    /**
     * The reported attack: a link somebody else made, named like one of ours,
     * pointing at our own config directory. It is not a directory we created,
     * and deleting what is underneath it would empty our configuration.
     */
    @Test
    public void aLinkIsNeverOursToDelete() {
        File parent = tempDir("link");
        File target = new File(parent, "config");
        try {
            assertTrue(target.mkdir());
            File link = new File(parent, "i2p-AAAA.tmp");
            makeLink(link, target);
            backdateAge(target, 3 * DAY);
            assertFalse(I2PAppContext.isStaleOwnTempDir(link, target, System.currentTimeMillis() - DAY));
            assertTrue("the target of the link must still be there", target.isDirectory());
        } finally {
            deleteTree(parent);
        }
    }

    /** A plain file named like one of ours is not a directory to delete. */
    @Test
    public void aPlainFileIsNotADirectory() {
        File parent = tempDir("file");
        try {
            File f = new File(parent, "i2p-AAAA.tmp");
            try {
                assertTrue(f.createNewFile());
            } catch (IOException e) {
                throw new AssertionError("could not create " + f, e);
            }
            backdateAge(f, 3 * DAY);
            assertFalse(I2PAppContext.isStaleOwnTempDir(f, parent, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(parent);
        }
    }

    /**
     * An owner this JVM cannot read is an owner it cannot prove is ours, so the
     * cleanup does nothing rather than delete what it cannot account for.
     */
    @Test
    public void anUnreadableOwnerIsNotOurs() {
        File root = tempDir("owner");
        try {
            backdateAge(root, 3 * DAY);
            File missing = new File(root, "not-here-at-all");
            assertFalse(I2PAppContext.isStaleOwnTempDir(root, missing, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(root);
        }
    }

    /** A file written inside a leftover directory keeps it in use. */
    @Test
    public void aFreshFileInsideKeepsItInUse() {
        File root = tempDir("inner");
        try {
            File f = new File(root, "data");
            try {
                assertTrue(f.createNewFile());
            } catch (IOException e) {
                throw new AssertionError("could not create " + f, e);
            }
            backdateAge(root, 3 * DAY);
            assertFalse(I2PAppContext.isUnmodifiedSince(root, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(root);
        }
    }

    /**
     * The keep-alive scanner touches files at any depth, so a file written deep
     * inside a leftover tree says the same thing about it.
     */
    @Test
    public void aFreshFileDeepInsideKeepsItInUse() {
        File root = tempDir("deep");
        File sub = new File(root, "sub");
        try {
            assertTrue(sub.mkdirs());
            File f = new File(sub, "data");
            try {
                assertTrue(f.createNewFile());
            } catch (IOException e) {
                throw new AssertionError("could not create " + f, e);
            }
            backdateAge(root, 3 * DAY);
            backdateAge(sub, 3 * DAY);
            assertFalse(I2PAppContext.isUnmodifiedSince(root, System.currentTimeMillis() - DAY));
            backdateAge(f, 3 * DAY);
            assertTrue("the whole tree old is a leftover",
                       I2PAppContext.isUnmodifiedSince(root, System.currentTimeMillis() - DAY));
        } finally {
            deleteTree(root);
        }
    }

    /**
     * The cutoff is the boundary: an entry modified at it is in use, one
     * modified before it is a leftover.
     */
    @Test
    public void theCutoffIsTheBoundary() {
        File root = tempDir("boundary");
        try {
            long now = System.currentTimeMillis();
            backdate(root, now - 2 * DAY);
            assertFalse(I2PAppContext.isUnmodifiedSince(root, now - 2 * DAY));
            assertTrue(I2PAppContext.isUnmodifiedSince(root, now - 2 * DAY + 1000));
        } finally {
            deleteTree(root);
        }
    }

    /**
     * Makes a uniquely-named directory under the system temp dir.
     *
     * @param tag what the directory is for, kept in its name to stay greppable
     * @return the directory, already created
     */
    private static File tempDir(String tag) {
        File f = new File(System.getProperty("java.io.tmpdir"),
                          "i2p-stale-" + tag + "-" + System.nanoTime());
        assertTrue("could not create " + f, f.mkdir());
        return f;
    }

    /**
     * Sets a path's modification time to an absolute time.
     *
     * @param f the path to backdate
     * @param when the time to set, in milliseconds since the epoch
     */
    private static void backdate(File f, long when) {
        assertTrue("could not set the time of " + f, f.setLastModified(when));
    }

    /**
     * Sets a path's modification time to the given age before now.
     *
     * @param f the path to backdate
     * @param age how long ago the path is to look as if it was modified
     */
    private static void backdateAge(File f, long age) {
        backdate(f, System.currentTimeMillis() - age);
    }

    /**
     * Makes {@code link} a symbolic link to {@code target}, or skips the test on
     * a platform or filesystem that will not make one.
     *
     * @param link the path the link is created at
     * @param target the path the link points at
     */
    private static void makeLink(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            assumeNoException("no symbolic links on this filesystem", e);
        }
    }

    /**
     * Removes a directory tree, links included, whatever a test leaves behind.
     *
     * @param f the path to remove
     */
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {return;}
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
