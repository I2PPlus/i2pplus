package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the hold-off applied to an info hash whose metadata lookup timed out. Every
 * lookup attempt is a full magnet torrent - destination, announce loop, DHT get_peers rounds,
 * peer dials, and a rewrite of the metadata file when it is purged - and the console asks
 * again on every refresh, so a hash nobody in the swarm can serve used to be re-spawned on a
 * steady cadence forever. The hold-off doubles from five minutes to a two hour ceiling.
 *
 * @since 0.9.72+
 */
public class LookupBackoffTest {

    /** Two hours, the ceiling, in millis. */
    private static final long TWO_HOURS_MS = 2 * 60 * 60 * 1000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public LookupBackoffTest() {}

    /** The manager the hold-off state is exercised on. */
    private SnarkManager mgr;

    /** The scratch I2P directory tree the manager is built in. */
    private File baseDir;

    /**
     * Builds a manager that is not started, in a scratch directory tree, so the hold-off state
     * can be exercised without a router or a DHT.
     */
    @Before
    public void setUp() {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-snark-backoff-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        I2PAppContext ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, "/test-snark-backoff", "test-snark-backoff");
    }

    /**
     * Stops the manager and removes the scratch tree it wrote.
     */
    @After
    public void tearDown() {
        if (mgr != null) {
            try {
                mgr.stop();
            } catch (Exception e) {
                // best-effort teardown of a manager that was never started
            }
        }
        FileUtil.rmdir(baseDir, false);
    }

    /** A 20-byte info hash of the given fill byte. */
    private static byte[] hash(byte fill) {
        byte[] rv = new byte[20];
        rv[0] = fill;
        return rv;
    }

    /**
     * A hash nobody served is held off after a timeout, and the hold-off is the doubling window
     * measured from the attempt that failed.
     */
    @Test
    public void testATimeoutHoldsTheHashOff() {
        long now = 1_700_000_000_000L;
        byte[] ih = hash((byte) 0x11);
        assertFalse(mgr.lookupDeferred(ih, now));
        mgr.recordLookupResult(ih, false, now);
        assertTrue(mgr.lookupDeferred(ih, now));
        assertEquals(now + 5 * 60 * 1000L, mgr.lookupRetryTime(ih));
        assertFalse("a different hash is held off too", mgr.lookupDeferred(hash((byte) 0x22), now));
    }

    /**
     * The hold-off expires: a hash that gains peers willing to serve its metadata is asked about
     * again once the window has passed, and the expired entry does not hold the key.
     */
    @Test
    public void testTheHoldOffExpires() {
        long now = 1_700_000_000_000L;
        byte[] ih = hash((byte) 0x33);
        // the failure happened six hours ago, so its five-minute window is long gone
        mgr.recordLookupResult(ih, false, now - 6 * 60 * 60 * 1000L);
        assertFalse(mgr.lookupDeferred(ih, now));
    }

    /**
     * Each further timeout pushes the next attempt further out, so a hash that keeps failing is
     * asked about less and less often, never more.
     */
    @Test
    public void testEachTimeoutLengthensTheWindow() {
        long now = 1_700_000_000_000L;
        byte[] ih = hash((byte) 0x44);
        mgr.recordLookupResult(ih, false, now);
        long first = mgr.lookupRetryTime(ih);
        mgr.recordLookupResult(ih, false, now);
        long second = mgr.lookupRetryTime(ih);
        mgr.recordLookupResult(ih, false, now);
        long third = mgr.lookupRetryTime(ih);
        assertTrue(first < second);
        assertTrue(second < third);
        assertEquals(now + 20 * 60 * 1000L, third);
    }

    /**
     * A lookup that resolves clears the hold-off, so a hash that becomes servable is not left
     * held off by its earlier failures.
     */
    @Test
    public void testAResolvedLookupClearsTheHoldOff() {
        long now = 1_700_000_000_000L;
        byte[] ih = hash((byte) 0x55);
        mgr.recordLookupResult(ih, false, now);
        assertTrue(mgr.lookupDeferred(ih, now));
        mgr.recordLookupResult(ih, true, now);
        assertFalse(mgr.lookupDeferred(ih, now));
        assertEquals(-1L, mgr.lookupRetryTime(ih));
    }

    /**
     * The first timeout holds a hash off for five minutes, and each one after that doubles.
     */
    @Test
    public void testBackoffDoublesFromFiveMinutes() {
        assertEquals(5 * 60 * 1000L, SnarkManager.lookupBackoffMs(1));
        assertEquals(10 * 60 * 1000L, SnarkManager.lookupBackoffMs(2));
        assertEquals(20 * 60 * 1000L, SnarkManager.lookupBackoffMs(3));
        assertEquals(40 * 60 * 1000L, SnarkManager.lookupBackoffMs(4));
    }

    /**
     * The doubling stops at two hours, and the sequence never goes backwards, so a key is
     * never asked about more often than it was the time before.
     */
    @Test
    public void testBackoffIsCappedAtTwoHours() {
        assertEquals(TWO_HOURS_MS, SnarkManager.lookupBackoffMs(7));
        long previous = 0;
        for (int failures = 1; failures <= 64; failures++) {
            long delay = SnarkManager.lookupBackoffMs(failures);
            assertTrue("backoff went backwards at " + failures, delay >= previous);
            assertTrue("backoff over the ceiling at " + failures, delay <= TWO_HOURS_MS);
            previous = delay;
        }
    }

    /**
     * A key that has been failing for months - hundreds of timeouts - still gets a sane
     * number: the shift that reaches the ceiling must not overflow the long first.
     */
    @Test
    public void testBackoffSurvivesAnyNumberOfTimeouts() {
        assertEquals(TWO_HOURS_MS, SnarkManager.lookupBackoffMs(32));
        assertEquals(TWO_HOURS_MS, SnarkManager.lookupBackoffMs(1000));
        assertTrue(SnarkManager.lookupBackoffMs(Integer.MAX_VALUE) > 0);
    }

    /**
     * The next attempt is never sooner than the floor between attempts, and for a first
     * timeout that is the five-minute window rather than the floor.
     */
    @Test
    public void testNextAttemptHonoursTheFloor() {
        long now = 1_700_000_000_000L;
        assertEquals(now + 5 * 60 * 1000L, SnarkManager.lookupRetryAt(now, 1));
        assertTrue(SnarkManager.lookupRetryAt(now, 0) - now >= SnarkManager.LOOKUP_MIN_GAP_MS);
        assertTrue(SnarkManager.lookupRetryAt(now, 9) > now);
    }
}
