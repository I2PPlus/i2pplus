package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins how a NetDb lookup answered with a redirect - not with the value asked
 * for - is accounted for in {@link DBHistory}.
 *
 * <p>A floodfill holding the answer is the exception: most of them answer a
 * lookup with a list of routers that might hold it. If that answer leaves no
 * trace, the peer keeps only its failures, and
 * {@code FloodfillPeerSelector.classifyFloodfillPeer()} compares
 * {@code getLastLookupFailed()} against {@code getLastLookupSuccessful()} to
 * rank it, while {@code IterativeSearchJob.sendQuery()} sizes the per-peer
 * reply timeout from the response time that answer never recorded.
 *
 * @since 0.9.72+
 */
public class DBHistoryRedirectCreditTest {

    /**
     * The redirect-credit cases. Every case drives a {@link DBHistory} built
     * from the test context, so the instance holds no state of its own.
     */
    public DBHistoryRedirectCreditTest() {
    }

    /** How many redirects one peer answered in the multi-answer cases. */
    private static final int REPLY_COUNT = 3;

    private RouterContext _ctx;
    private String _group;

    /**
     * Builds an isolated context, so this test's rate data cannot land in the
     * network-wide "peer.failedLookupRate" that sets the failure ceiling every
     * floodfill is measured against, and a stat group no other case shares.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _group = "dbHistoryRedirect-" + Hash.create(new byte[Hash.HASH_LENGTH]).toBase64();
    }

    /**
     * Drops the rate stat the case registered, so a later run in the same JVM
     * does not read this peer's answers as its own.
     */
    @After
    public void tearDown() {
        if (_ctx != null && _group != null) {
            _ctx.statManager().removeRateStat("dbHistory.failedLookupRate." + _group);
        }
    }

    /**
     * Total events recorded in the combined NetDb responsiveness rate.
     *
     * @param hist the history to sample
     * @return events in the 1 hour bucket, lookups and stores together
     */
    private static long hourEvents(DBHistory hist) {
        RateStat rs = hist.getFailedLookupRate();
        return rs.getRate(RateConstants.ONE_HOUR).getLifetimeEventCount();
    }

    /**
     * Fraction of the combined rate recorded as a failure. The lifetime average
     * is used because {@code Rate.getAverageValue()} only reflects data already
     * coalesced into a completed period.
     *
     * @param hist the history to sample
     * @return failures over all events in the 1 hour bucket
     */
    private static double hourFailRate(DBHistory hist) {
        return hist.getFailedLookupRate().getRate(RateConstants.ONE_HOUR).getLifetimeAverageValue();
    }

    /**
     * What a redirect used to leave behind: the reply counter moves, the
     * responsiveness rate does not, and the peer's last-answered time stays
     * unset, so every ranking that compares it against a failure time reads the
     * peer as never having answered anything.
     */
    @Test
    public void testReplyAloneLeavesNoAnswerCredit() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupReply(4, 2, 0, 0);
        assertEquals(0L, hourEvents(hist));
        assertEquals(0L, hist.getLastLookupSuccessful());
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(0L, hist.getFailedLookups());
    }

    /**
     * The answer credit fills the responsiveness rate with a success and stamps
     * the last-answered time, without claiming the peer returned the value.
     */
    @Test
    public void testAnsweredCreditsTheRateNotTheLookupCount() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupAnswered();
        assertEquals(1L, hourEvents(hist));
        assertEquals(0.0d, hourFailRate(hist), 0.0001d);
        assertTrue(hist.getLastLookupSuccessful() > 0);
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(0L, hist.getFailedLookups());
    }

    /**
     * A floodfill that answered every lookup with a redirect, and never held
     * the key, ends up indistinguishable from one that answered with the data:
     * a zero failure rate and a last-answered time newer than any failure.
     */
    @Test
    public void testRedirectOnlyPeerRanksAsAnswering() {
        DBHistory hist = new DBHistory(_ctx, _group);
        for (int i = 0; i < REPLY_COUNT; i++) {
            hist.lookupReply(2, 1, 0, 0);
            hist.lookupAnswered();
        }
        assertEquals(REPLY_COUNT, hourEvents(hist));
        assertEquals(0.0d, hourFailRate(hist), 0.0001d);
        assertTrue(hist.getLastLookupSuccessful() > hist.getLastLookupFailed());
        assertEquals(0L, hist.getFailedLookups());
    }

    /**
     * The answer credit does not move the lookup counters the floodfill
     * reliability rating is computed from, so a redirect is not scored as a
     * lookup that returned the value.
     */
    @Test
    public void testAnsweredLeavesReliabilityCountsAlone() {
        DBHistory hist = new DBHistory(_ctx, _group);
        hist.lookupAnswered();
        hist.lookupAnswered();
        hist.lookupFailed();
        assertEquals(0L, hist.getSuccessfulLookups());
        assertEquals(1L, hist.getFailedLookups());
        assertEquals(3L, hourEvents(hist));
        assertEquals(1.0d / 3.0d, hourFailRate(hist), 0.0001d);
    }
}
