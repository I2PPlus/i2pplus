package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests where in a reorganize cycle the on-disk profile purge runs.
 *
 * The purge gunzips and property-parses every stored profile file, up to
 * {@link ProfileOrganizer#MAX_STORED_PROFILE_FILES} of them, and does a netdb
 * lookup per file. {@code _reorganizeLock} is the lock tunnel peer selection
 * contends on, so running the purge while holding it stalls every pool build for
 * as long as the walk takes. The purge still has to happen - it is the only
 * thing that keeps the profile directory from growing without bound - so what is
 * pinned here is that it runs, and that it runs with the write lock released.
 *
 * @since 0.9.72+
 */
public class ProfileOrganizerPurgeLockTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /**
     * Persistence helper that reports a stored-file count above the retention
     * floor and records the lock state at the moment the purge is asked to run,
     * instead of walking the profile directory.
     */
    private static class RecordingHelper extends ProfilePersistenceHelper {

        private final ReentrantReadWriteLock _lock;
        private final AtomicInteger _purgeCalls = new AtomicInteger();
        private final AtomicInteger _purgesUnderWriteLock = new AtomicInteger();
        private volatile int _stored;

        /**
         * @param ctx the router context
         * @param lock the organizer's reorganize lock, to sample at purge time
         */
        RecordingHelper(RouterContext ctx, ReentrantReadWriteLock lock) {
            super(ctx);
            _lock = lock;
            _stored = ProfileOrganizer.MAX_STORED_PROFILE_FILES + 1;
        }

        @Override
        public int getStoredProfileCount() {return _stored;}

        @Override
        public void purgeExcessProfiles(Set<Hash> keepPeers, int retentionFloor) {
            _purgeCalls.incrementAndGet();
            if (_lock.isWriteLockedByCurrentThread()) {_purgesUnderWriteLock.incrementAndGet();}
        }

        /**
         * @param n the count getStoredProfileCount() reports from now on
         */
        void setStoredCount(int n) {_stored = n;}

        int purgeCalls() {return _purgeCalls.get();}

        int purgesUnderWriteLock() {return _purgesUnderWriteLock.get();}
    }

    /**
     * Install the recording helper and hand back the organizer's reorganize lock.
     *
     * @param stored the stored-file count the helper should report
     * @return a two-element array: the organizer and its reorganize lock
     * @throws Exception if reflection on ProfileOrganizer fails - the
     *                   {@code _reorganizeLock} or
     *                   {@code _persistenceHelper} field is missing, or
     *                   {@code setAccessible()} is denied
     */
    private Object[] organizerWith(int stored) throws Exception {
        ProfileOrganizer org = new ProfileOrganizer(_ctx);
        Field lockField = ProfileOrganizer.class.getDeclaredField("_reorganizeLock");
        lockField.setAccessible(true);
        ReentrantReadWriteLock lock = (ReentrantReadWriteLock) lockField.get(org);
        RecordingHelper helper = new RecordingHelper(_ctx, lock);
        helper.setStoredCount(stored);
        Field helperField = ProfileOrganizer.class.getDeclaredField("_persistenceHelper");
        helperField.setAccessible(true);
        helperField.set(org, helper);
        return new Object[]{org, helper};
    }

    /**
     * The purge must still run on a reorganize cycle - dropping it would let the
     * profile directory grow forever - and it must run after the write lock has
     * been released, not inside the locked region.
     *
     * @throws Exception if the recording helper cannot be installed - the
     *                   organizer's private reorganize lock or persistence
     *                   helper is unreachable by reflection
     */
    @Test
    public void testPurgeRunsAfterTheWriteLockIsReleased() throws Exception {
        Object[] pair = organizerWith(ProfileOrganizer.MAX_STORED_PROFILE_FILES + 1);
        ProfileOrganizer org = (ProfileOrganizer) pair[0];
        RecordingHelper helper = (RecordingHelper) pair[1];

        org.reorganize();

        assertEquals("reorganize must still purge the stored profile files",
                     1, helper.purgeCalls());
        assertEquals("reorganize must not purge while holding the peer-selection lock",
                     0, helper.purgesUnderWriteLock());
    }

    /**
     * Below the retention margin there is nothing to purge, and the walk - which
     * materializes every tracked hash and stats every file - must be skipped
     * outright rather than run on every reorganize.
     *
     * @throws Exception if the recording helper cannot be installed - the
     *                   organizer's private reorganize lock or persistence
     *                   helper is unreachable by reflection
     */
    @Test
    public void testPurgeSkippedWellBelowRetentionFloor() throws Exception {
        Object[] pair = organizerWith(ProfileOrganizer.MAX_STORED_PROFILE_FILES / 4);
        ProfileOrganizer org = (ProfileOrganizer) pair[0];
        RecordingHelper helper = (RecordingHelper) pair[1];

        org.reorganize();

        assertEquals("no file walk while the stored count is under the margin",
                     0, helper.purgeCalls());
    }

    /**
     * The purge is reachable on its own (the console and the store job both have
     * reasons to ask for it), and called that way it must not take the write lock
     * either - it only needs the read lock that selectAllPeers() already takes.
     *
     * @throws Exception if the recording helper cannot be installed - the
     *                   organizer's private reorganize lock or persistence
     *                   helper is unreachable by reflection
     */
    @Test
    public void testDirectPurgeDoesNotTakeTheWriteLock() throws Exception {
        Object[] pair = organizerWith(ProfileOrganizer.MAX_STORED_PROFILE_FILES + 1);
        ProfileOrganizer org = (ProfileOrganizer) pair[0];
        RecordingHelper helper = (RecordingHelper) pair[1];

        org.purgeStaleProfileFiles();

        assertEquals(1, helper.purgeCalls());
        assertEquals(0, helper.purgesUnderWriteLock());
    }
}
