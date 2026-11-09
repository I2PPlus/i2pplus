package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Pins which sub-directories of the netdb the periodic rescan lists and reads.
 * <p>
 * The rescan runs every five minutes for the life of the router, under the same
 * monitor the netdb writer takes per entry, and it walks all 64 base-64
 * sub-directories. A directory whose modified time has not advanced cannot hold
 * a new file, so listing it allocates one {@code File} per RouterInfo and reads
 * the whole directory for a count nobody uses unless the total is still short of
 * what the reseed check wants. Both predicates are pinned on each side of their
 * thresholds, including the mtime-equal case, which is the common one and must
 * not read.
 * <p>
 * The cached count is the replacement for that listing, so it is pinned too: an
 * unchanged directory must report the count taken at that same modified time,
 * and a directory whose time moved - or which does not exist - must report
 * nothing so it gets listed again.
 * <p>
 * Finally the read-order sort, which decides which files a capped startup read
 * reaches, is pinned to modified time, newest first: the first tunnels are then
 * built out of recently active routers, and the entries past the cap that get
 * deleted as excess are the stalest ones rather than an arbitrary tail.
 *
 * @since 0.9.72+
 */
public class PersistentDataStoreRescanDecisionTest {

    /** Scratch directory for the placeholder files the sort tests stat. */
    private static final String TMP_DIR = "/tmp/test-i2p/netdb-rescan-test";

    /** The count the reseed check is satisfied with, as the read job uses it. */
    private static final int MIN_ROUTERS = 50;

    /**
     * Only a directory modified strictly after the previous scan holds files that
     * have not been read; an unchanged one must be skipped, and a vanished one
     * (reported as 0) is never newer than anything.
     */
    @Test
    public void testOnlyNewerDirectoriesAreRead() {
        assertTrue(PersistentDataStore.shouldReadSubDir(2000L, 1000L));
        assertFalse("an unchanged directory was read on the previous scan",
                    PersistentDataStore.shouldReadSubDir(1000L, 1000L));
        assertFalse(PersistentDataStore.shouldReadSubDir(999L, 1000L));
        // First scan of all: _lastModified is still 0, every real directory is newer.
        assertTrue(PersistentDataStore.shouldReadSubDir(1L, 0L));
        assertFalse("a missing directory has no files to read",
                    PersistentDataStore.shouldReadSubDir(0L, 0L));
    }

    /**
     * A directory is listed to read its new files, or to top up the count for the
     * reseed check while that count is still short. Past both, it is left alone.
     */
    @Test
    public void testListingFollowsNewnessOrTheCount() {
        // Changed directory: listed whatever the count is.
        assertTrue(PersistentDataStore.shouldListSubDir(2000L, 1000L, 0, MIN_ROUTERS));
        assertTrue(PersistentDataStore.shouldListSubDir(2000L, 1000L, MIN_ROUTERS, MIN_ROUTERS));
        assertTrue(PersistentDataStore.shouldListSubDir(2000L, 1000L, 9999, MIN_ROUTERS));
        // Unchanged directory: still needed only while the count is short.
        assertTrue(PersistentDataStore.shouldListSubDir(1000L, 1000L, MIN_ROUTERS - 1, MIN_ROUTERS));
        assertFalse(PersistentDataStore.shouldListSubDir(1000L, 1000L, MIN_ROUTERS, MIN_ROUTERS));
        assertFalse(PersistentDataStore.shouldListSubDir(1000L, 1000L, MIN_ROUTERS + 1, MIN_ROUTERS));
        // A missing directory past the count is not listed either.
        assertFalse(PersistentDataStore.shouldListSubDir(0L, 0L, MIN_ROUTERS, MIN_ROUTERS));
        // Count 0 and no scan yet: the very first pass lists everything.
        assertTrue(PersistentDataStore.shouldListSubDir(1L, 0L, 0, MIN_ROUTERS));
    }

    /**
     * A directory that has not moved reports the count taken at that same time;
     * one that has moved, or that is gone, has to be listed again.
     */
    @Test
    public void testCachedCountOnlyForAnUnchangedDirectory() {
        assertEquals(137, PersistentDataStore.cachedSubDirCount(1000L, 1000L, 137));
        // An empty directory really did hold zero files; 0 is a valid answer.
        assertEquals(0, PersistentDataStore.cachedSubDirCount(1000L, 1000L, 0));
        assertEquals(-1, PersistentDataStore.cachedSubDirCount(2000L, 1000L, 137));
        assertEquals("a directory never counted has no count to reuse",
                     -1, PersistentDataStore.cachedSubDirCount(1000L, 0L, 137));
        assertEquals("a vanished directory must not reuse the count of a real one",
                     -1, PersistentDataStore.cachedSubDirCount(0L, 0L, 137));
        assertEquals(-1, PersistentDataStore.cachedSubDirCount(0L, 1000L, 137));
    }

    /**
     * The sort keeps every candidate file - dropping one would silently leave
     * that router out of the netdb - and orders them strictly newest modified
     * time first.
     *
     * @throws Exception if a placeholder file cannot be created or stamped
     */
    @Test
    public void testSortOrdersNewestFirstAndKeepsEveryFile() throws Exception {
        // Written out of order: middle, oldest, newest, second-oldest.
        List<File> files = new ArrayList<>(4);
        files.add(timedFile("mid", 3000));
        files.add(timedFile("old", 1000));
        files.add(timedFile("new", 4000));
        files.add(timedFile("old2", 2000));
        PersistentDataStore.sortForRead(files);
        assertEquals(4, files.size());
        assertEquals("newest first", "routerInfo-new.dat", files.get(0).getName());
        assertEquals("routerInfo-mid.dat", files.get(1).getName());
        assertEquals("routerInfo-old2.dat", files.get(2).getName());
        assertEquals("oldest last", "routerInfo-old.dat", files.get(3).getName());
    }

    /**
     * A file that vanished after being listed reports modified time 0 and must
     * sort last, never first as a negative or unset time would.
     *
     * @throws Exception if a placeholder file cannot be created or stamped
     */
    @Test
    public void testSortPutsMissingFilesLast() throws Exception {
        List<File> files = new ArrayList<>(3);
        files.add(new File(TMP_DIR + "/routerInfo-gone.dat"));
        files.add(timedFile("live", 2000));
        files.add(timedFile("live2", 1000));
        PersistentDataStore.sortForRead(files);
        assertEquals(3, files.size());
        assertEquals("a vanished file must not be read first",
                     "routerInfo-live.dat", files.get(0).getName());
        assertEquals("routerInfo-live2.dat", files.get(1).getName());
        assertEquals("routerInfo-gone.dat", files.get(2).getName());
    }

    /**
     * An empty and a single-element candidate list survive the sort unchanged.
     */
    @Test
    public void testSortHandlesEmptyAndSingle() {
        List<File> empty = new ArrayList<>();
        PersistentDataStore.sortForRead(empty);
        assertTrue(empty.isEmpty());
        List<File> one = files(1);
        File only = one.get(0);
        PersistentDataStore.sortForRead(one);
        assertEquals(1, one.size());
        assertSame(only, one.get(0));
    }

    /**
     * A large candidate set comes through complete and in non-increasing
     * modified-time order - the sort must not lose or duplicate entries when
     * the capped startup read walks the result.
     *
     * @throws Exception if a placeholder file cannot be created or stamped
     */
    @Test
    public void testSortKeepsLargeSetCompleteAndOrdered() throws Exception {
        List<File> files = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            // Reverse listing order with distinct times, so the sort must flip it.
            files.add(timedFile(String.valueOf(i), (200 - i) * 1000L));
        }
        PersistentDataStore.sortForRead(files);
        assertEquals(200, files.size());
        long prev = Long.MAX_VALUE;
        for (File f : files) {
            long m = f.lastModified();
            assertTrue("modified times must not increase down the list", m <= prev);
            prev = m;
        }
    }

    /**
     * A placeholder file under the test directory with a specific modified time.
     * Times are whole seconds spaced far apart, so no filesystem timestamp
     * granularity can reorder them.
     *
     * @param name the file's base-64 prefix character run
     * @param mtime the modified time to set, in ms since the epoch
     * @return the file, created on disk with that modified time
     * @throws Exception if the file cannot be created, or its modified time cannot be set
     */
    private static File timedFile(String name, long mtime) throws Exception {
        File f = new File(TMP_DIR + "/routerInfo-" + name + ".dat");
        f.getParentFile().mkdirs();
        f.createNewFile();
        if (!f.setLastModified(mtime)) {
            throw new Exception("could not set modified time on " + f);
        }
        return f;
    }

    /**
     * A list of placeholder files, named so that their natural order is the
     * order they are listed in.
     *
     * @param count how many files to build
     * @return the list, in listing order
     */
    private static List<File> files(int count) {
        List<File> rv = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rv.add(new File(TMP_DIR + "/routerInfo-" + String.format("%04d", i) + ".dat"));
        }
        return rv;
    }
}
