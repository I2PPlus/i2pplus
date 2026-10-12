package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.transport.Transport;

/**
 * A peer profile must only ever hold evidence it can date honestly.
 *
 * <p>Three failures share one root: a measurement was stamped with when it was
 * <em>read</em> rather than when it was <em>taken</em>. That makes an old reading
 * look current, which holds the validity window open forever and repeatedly damps
 * the stored average toward a single stale sample. These tests pin the behaviour
 * that prevents it, along with the calibrated threshold the stored value is then
 * judged against.
 *
 * @since 0.9.71+
 */
public class LatencyMeasurementFidelityTest {

    /**
     * A profile, plus a timestamp the profile's own clock will agree with.
     *
     * <p>setFirstHopRtt stores the timestamp it is handed and getFirstHopRtt
     * judges it against the context clock, so the two have to come from the same
     * clock or the reading reads back as unknown.
     */
    private static final RouterContext CTX = RouterTestHelper.getContext();
    private static final long NOW = (CTX != null) ? CTX.clock().now() : 0L;
    private static final long HOUR = 60L * 60 * 1000;

    private static Hash hash(int b) {
        byte[] d = new byte[Hash.HASH_LENGTH];
        d[0] = (byte) b;
        d[1] = (byte) (3 - b);
        return Hash.create(d);
    }

    private static PeerProfile profile() {
        Assume.assumeTrue("no RouterContext available", CTX != null);
        return new PeerProfile(CTX, hash(8));
    }

    // ---------------- calibrated threshold scaling ----------------

    /**
     * The bound a profile is judged against is the baseline times the multiple.
     *
     * <p>This is the whole point of the calibration: the baseline is where a
     * transport's population starts, so a peer may sit well above it and still
     * belong to the fast end. Comparing against the raw baseline would reject
     * everything above it, which at a live baseline of ~43ms means disqualifying
     * peers the multiple was chosen specifically to admit.
     */
    @Test
    public void testBaselineIsScaledByMultiple() {
        assertEquals(129L, ProfileOrganizer.applyBaselineMultiple(43L, 3));
        assertEquals(60L, ProfileOrganizer.applyBaselineMultiple(20L, 3));
    }

    /**
     * A multiple of one or less must not silently widen, which would make the
     * bound vacuous and the calibration meaningless.
     */
    @Test
    public void testMultipleOfOneLeavesBaselineUnchanged() {
        assertEquals(43L, ProfileOrganizer.applyBaselineMultiple(43L, 1));
        assertEquals(43L, ProfileOrganizer.applyBaselineMultiple(43L, 0));
        assertEquals(43L, ProfileOrganizer.applyBaselineMultiple(43L, -2));
    }

    /**
     * "No opinion yet" must survive scaling as a negative, not become zero.
     *
     * <p>A zero or negative threshold is skipped by the caller, so collapsing
     * -1 to 0 here would not change this decision - but it would make the
     * difference between unknown and known unreadable at the call site.
     */
    @Test
    public void testNonPositiveBaselineIsPassedThrough() {
        assertEquals(-1L, ProfileOrganizer.applyBaselineMultiple(-1L, 3));
        assertEquals(0L, ProfileOrganizer.applyBaselineMultiple(0L, 3));
    }

    /**
     * Overflow must saturate, not wrap.
     *
     * <p>A wrapped long would land negative and read as "no opinion", silently
     * switching the check off - the exact opposite of what a huge baseline means.
     */
    @Test
    public void testOverflowSaturatesRatherThanWrapping() {
        assertEquals(Long.MAX_VALUE,
                     ProfileOrganizer.applyBaselineMultiple(Long.MAX_VALUE / 2, 4));
        assertTrue(ProfileOrganizer.applyBaselineMultiple(Long.MAX_VALUE, 3) > 0);
    }

    // ---------------- a sample may never predate the stored one ----------------

    /**
     * An older sample must be discarded outright.
     *
     * <p>This is the defect: a transport whose reading is old but not yet expired
     * re-submits that same number every cycle. Without this rule the stored value
     * is damped back toward the one stale sample and the timestamp is pushed to
     * the present, so the validity window never opens and the peer stays flagged
     * low latency indefinitely.
     */
    @Test
    public void testOlderSampleIsRejected() {
        PeerProfile p = profile();
        p.setFirstHopRtt(80, NOW);
        assertEquals(80, p.getFirstHopRtt(NOW));
        // A reading taken an hour ago arrives after one taken now.
        p.setFirstHopRtt(20, NOW - HOUR);
        assertEquals("an older sample must not overwrite a newer one",
                     80, p.getFirstHopRtt(NOW));
    }

    /** An equally-timed re-submission of the same value is harmless. */
    @Test
    public void testSameTimestampSampleIsAccepted() {
        PeerProfile p = profile();
        p.setFirstHopRtt(80, NOW);
        p.setFirstHopRtt(80, NOW);
        assertEquals(80, p.getFirstHopRtt(NOW));
    }

    /**
     * A newer sample is damped into the stored average, not substituted for it.
     *
     * <p>Pinned because the damping is deliberate: one noisy reading should not
     * set the value on its own. The exact figure is 0.75*80 + 0.25*20 = 65.
     */
    @Test
    public void testNewerSampleIsAccepted() {
        PeerProfile p = profile();
        p.setFirstHopRtt(80, NOW);
        p.setFirstHopRtt(20, NOW + HOUR);
        assertEquals(65, p.getFirstHopRtt(NOW + HOUR));
    }

    /**
     * Once the stored reading has aged past the validity window it reads back
     * unknown, which is what the coverage count keys on.
     *
     * <p>This is the property that lets coverage be measured honestly: a peer
     * holding only an expired reading must not be counted as measured.
     */
    @Test
    public void testExpiredReadingReadsBackUnknown() {
        PeerProfile p = profile();
        p.setFirstHopRtt(80, NOW);
        long expiry = NOW + PeerProfile.FIRST_HOP_RTT_VALIDITY_MS;
        assertTrue("a reading inside the window is known",
                   p.getFirstHopRtt(expiry - 1) > 0);
        assertEquals("a reading past the window is unknown, not zero",
                     -1, p.getFirstHopRtt(expiry + 1));
    }

    /**
     * A sample older than the validity window replaces the value outright rather
     * than being averaged into it, so expired evidence cannot drag a live one
     * back toward a value nothing has measured for hours.
     */
    @Test
    public void testExpiredValueIsReplacedNotDamped() {
        PeerProfile p = profile();
        long expired = NOW - PeerProfile.FIRST_HOP_RTT_VALIDITY_MS;
        p.setFirstHopRtt(200, expired);
        p.setFirstHopRtt(30, NOW);
        assertEquals("the replacement is not averaged with the expired value",
                     30, p.getFirstHopRtt(NOW));
    }

    /** An unknown sample is ignored rather than stored as zero latency. */
    @Test
    public void testNonPositiveSampleIsIgnored() {
        PeerProfile p = profile();
        p.setFirstHopRtt(0, NOW);
        assertEquals(-1, p.getFirstHopRtt(NOW));
        p.setFirstHopRtt(-5, NOW);
        assertEquals(-1, p.getFirstHopRtt(NOW));
    }

    // ---------------- transport measurement time contract ----------------

    /**
     * The two transport sentinels must stay distinct and negative.
     *
     * <p>{@code RTT_TIME_UNKNOWN} means the transport does not record when it
     * measured, and {@code RTT_TIME_CURRENT} means its reading is refreshed by
     * live traffic. Collapsing them would make a current reading be discarded as
     * undated, and making either zero would make an unrecorded session look
     * measured at the epoch.
     *
     * <p>The default {@code Transport.getEstimatedRTTTime} is not exercised
     * directly: it declares 33 abstract methods, and on the Java 8 target the
     * only route to an interface default body is a {@code Lookup} reflection hack
     * that needs JDK internals opened. Testing that the default returns a
     * constant it also defines would not add much either.
     */
    @Test
    public void testCurrentSentinelIsDistinctFromUnknown() {
        assertTrue("the two sentinels must not collide, or a current reading " +
                   "would be discarded as undated",
                   Transport.RTT_TIME_CURRENT != Transport.RTT_TIME_UNKNOWN);
        assertTrue(Transport.RTT_TIME_CURRENT < 0);
        assertTrue(Transport.RTT_TIME_UNKNOWN < 0);
    }
}
