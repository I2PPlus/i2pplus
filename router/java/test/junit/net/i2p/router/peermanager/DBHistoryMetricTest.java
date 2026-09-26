package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the semantics of the combined NetDb responsiveness rate in
 * {@link DBHistory}.
 *
 * <p>{@link DBHistory#getFailedLookupRate()} is one rate covering both lookups
 * and stores: any success adds 0 and any failure adds 1. Peer selection reads
 * this rate, so the add-data value of every mutator has to stay exact.
 *
 * @since 0.9.72
 */
public class DBHistoryMetricTest {

    private RouterContext _ctx;
    private String _group;

    @Before
    public void setUp() {
        // An isolated context keeps this profile's rate data out of the
        // network-wide "peer.failedLookupRate" statistic used by peer selection.
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _group = "dbHistoryMetricTest-" + getClass().getSimpleName() + "-" + Hash.create(new byte[Hash.HASH_LENGTH]).toBase64();
    }

    @After
    public void tearDown() {
        if (_ctx != null && _group != null) {
            _ctx.statManager().removeRateStat("dbHistory.failedLookupRate." + _group);
        }
    }

    /** Total event count recorded in the 1 hour bucket. */
    private long hourEvents(DBHistory hist) {
        RateStat rs = hist.getFailedLookupRate();
        assertNotNull(rs);
        return rs.getRate(RateConstants.ONE_HOUR).getLifetimeEventCount();
    }

    /**
     * Fraction of the 1 hour bucket that was recorded as a failure. The lifetime
     * average is used because {@link Rate#getAverageValue()} only reflects data
     * that has already been coalesced into a completed period.
     */
    private double hourFailRate(DBHistory hist) {
        return hist.getFailedLookupRate().getRate(RateConstants.ONE_HOUR).getLifetimeAverageValue();
    }

    @Test
    public void testLookupSuccessIsRecordedAsSuccess() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupSuccessful();
        assertEquals(1L, hourEvents(hist));
        assertEquals(0.0d, hourFailRate(hist), 0.0001d);
        assertEquals(1L, hist.getSuccessfulLookups());
        assertEquals(0L, hist.getFailedLookups());
    }

    @Test
    public void testLookupFailureIsRecordedAsFailure() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupFailed();
        assertEquals(1L, hourEvents(hist));
        assertEquals(1.0d, hourFailRate(hist), 0.0001d);
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(1L, hist.getFailedLookups());
    }

    /**
     * A verified store is a success for the combined rate, but is deliberately
     * not counted in the lookup counters.
     */
    @Test
    public void testStoreSuccessFeedsCombinedRate() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.storeSuccessful();
        assertEquals(1L, hourEvents(hist));
        assertEquals(0.0d, hourFailRate(hist), 0.0001d);
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(0L, hist.getFailedLookups());
    }

    /**
     * A failed store verify is a failure for the combined rate, but is
     * deliberately not counted in the lookup counters.
     */
    @Test
    public void testStoreFailureFeedsCombinedRate() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.storeFailed();
        assertEquals(1L, hourEvents(hist));
        assertEquals(1.0d, hourFailRate(hist), 0.0001d);
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(0L, hist.getFailedLookups());
    }

    /** The rate is a single combined rate, so lookups and stores share it. */
    @Test
    public void testLookupsAndStoresShareOneRate() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupSuccessful();
        hist.lookupFailed();
        hist.storeSuccessful();
        hist.storeFailed();
        assertEquals(4L, hourEvents(hist));
        assertEquals(0.5d, hourFailRate(hist), 0.0001d);
    }

    @Test
    public void testStoreTimestampsTracked() {
        DBHistory hist = new DBHistory(_ctx, _group);
        assertEquals(0L, hist.getLastStoreSuccessful());
        assertEquals(0L, hist.getLastStoreFailed());
        hist.storeSuccessful();
        hist.storeFailed();
        assertTrue(hist.getLastStoreSuccessful() > 0);
        assertTrue(hist.getLastStoreFailed() > 0);
    }
}
