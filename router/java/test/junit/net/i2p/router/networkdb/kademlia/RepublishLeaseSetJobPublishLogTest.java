package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.util.RandomSource;

/**
 * The publish-attempt bookkeeping. The timestamp map is not just a log throttle:
 * cleanupPublishedOnce(), cleanupGlobalFailCount() and cleanupRemintDefers() all
 * treat it as the set of destinations still being published for. When the write
 * sat inside a shouldInfo() guard it stayed empty at the default log level, so
 * every cleanup cycle wiped the failure counters and the escalation path could
 * never accumulate enough failures to fire.
 */
public class RepublishLeaseSetJobPublishLogTest {

    private static final long THROTTLE = 10L * 1000;

    private static Hash hash() {
        byte[] b = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(b, 0, b.length);
        return new Hash(b);
    }

    /** First attempt for a destination is always worth logging. */
    @Test
    public void testFirstAttemptIsLogged() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        assertTrue(RepublishLeaseSetJob.recordPublishAttempt(map, d, null, 1000L));
        assertEquals(Long.valueOf(1000L), map.get(d));
    }

    /** Within the throttle window the log is suppressed. */
    @Test
    public void testSuppressedWithinWindow() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        RepublishLeaseSetJob.recordPublishAttempt(map, d, null, 1000L);
        assertFalse(RepublishLeaseSetJob.recordPublishAttempt(map, d, 1000L, 1000L + THROTTLE - 1));
    }

    /** Exactly at the window it is still suppressed - the test is strictly greater. */
    @Test
    public void testBoundaryAtWindowIsSuppressed() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        assertFalse(RepublishLeaseSetJob.recordPublishAttempt(map, d, 1000L, 1000L + THROTTLE));
    }

    /** Past the window it logs again. */
    @Test
    public void testLoggedAfterWindow() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        assertTrue(RepublishLeaseSetJob.recordPublishAttempt(map, d, 1000L, 1000L + THROTTLE + 1));
    }

    /**
     * The regression itself: a suppressed call must still record the timestamp.
     * If it did not, the destination would age out of the map and the cleanups
     * would treat it as no longer tracked.
     */
    @Test
    public void testSuppressedCallStillRecordsState() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        RepublishLeaseSetJob.recordPublishAttempt(map, d, null, 1000L);
        long[] t = {1000L};
        for (int i = 0; i < 50; i++) {
            t[0] += 100L;
            RepublishLeaseSetJob.recordPublishAttempt(map, d, map.get(d), t[0]);
        }
        assertEquals("the timestamp must advance on every attempt", Long.valueOf(t[0]), map.get(d));
    }

    /**
     * Sustained attempts must keep the destination fresh, i.e. older than the
     * cleanup threshold only when publishing genuinely stopped.
     */
    @Test
    public void testSustainedAttemptsStayFresh() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        long now = 0L;
        for (int i = 0; i < 100; i++) {
            now += 1000L;
            RepublishLeaseSetJob.recordPublishAttempt(map, d, map.get(d), now);
        }
        assertNotNull(map.get(d));
        assertTrue("last attempt must be recent relative to now",
                   now - map.get(d).longValue() <= 1000L);
    }

    /** Destinations are tracked independently. */
    @Test
    public void testPerDestinationIndependence() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash a = hash();
        Hash b = hash();
        RepublishLeaseSetJob.recordPublishAttempt(map, a, null, 500L);
        assertEquals(1, map.size());
        RepublishLeaseSetJob.recordPublishAttempt(map, b, null, 600L);
        assertEquals(2, map.size());
        assertEquals(Long.valueOf(500L), map.get(a));
        assertEquals(Long.valueOf(600L), map.get(b));
    }

    /** A null previous with an existing entry still records and logs. */
    @Test
    public void testNullPreviousOverridesExistingEntry() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        map.put(d, 999_000L);
        // an expiring LeaseSet clears the caller's cached value to force a log
        assertTrue(RepublishLeaseSetJob.recordPublishAttempt(map, d, null, 1_000_000L));
        assertEquals(Long.valueOf(1_000_000L), map.get(d));
    }

    /** Repeated attempts for one destination never grow the map. */
    @Test
    public void testNoMapGrowthForOneDestination() {
        ConcurrentHashMap<Hash, Long> map = new ConcurrentHashMap<>();
        Hash d = hash();
        for (int i = 0; i < 500; i++)
            RepublishLeaseSetJob.recordPublishAttempt(map, d, map.get(d), i * 1000L);
        assertEquals(1, map.size());
    }
}
