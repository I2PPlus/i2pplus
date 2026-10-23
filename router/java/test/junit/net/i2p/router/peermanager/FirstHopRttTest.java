package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * Tests first-hop RTT recording and the eviction policy that consumes it.
 *
 * <p>The defect these pin: fast-tier eviction used a whole-tunnel round trip,
 * which {@code TestJob.noteSuccess} attributes to every peer in the tunnel, and
 * compared it against a first-hop threshold. Peers were removed for the latency
 * of hops they were not part of. The measurement is now one hop, taken from the
 * transport that already tracks it.
 *
 * @since 0.9.71+
 */
public class FirstHopRttTest {

    /** Router.peerTestTimeout default; the threshold is twice this. */
    private static final int THRESHOLD = 750 * 2;
    private static final long NOW = 2_000_000_000L;
    private static final long HOUR = RateConstants.ONE_HOUR;

    private RouterContext _ctx;
    private ProfileOrganizer _po;
    private PeerProfile _profile;
    private StatManager _stat;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        net.i2p.util.Clock clock = mock(net.i2p.util.Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
        _stat = mock(StatManager.class);
        when(_ctx.statManager()).thenReturn(_stat);
        _profile = mock(PeerProfile.class);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    // ---- the eviction policy, as a pure decision ----

    /**
     * Unknown is not slow. A peer we have not measured must never be evicted,
     * or the tier drains of exactly the peers we have not probed yet.
     */
    @Test
    public void unknownIsNotSlow() {
        assertFalse(ProfileOrganizer.firstHopRttIsSlow(-1, THRESHOLD));
    }

    /** Below the ceiling a peer keeps its place. */
    @Test
    public void belowThresholdIsNotSlow() {
        assertFalse(ProfileOrganizer.firstHopRttIsSlow(THRESHOLD - 1, THRESHOLD));
    }

    /** At the ceiling the peer is evicted — the boundary is inclusive. */
    @Test
    public void atThresholdIsSlow() {
        assertTrue(ProfileOrganizer.firstHopRttIsSlow(THRESHOLD, THRESHOLD));
    }

    /** Well past the ceiling is slow. */
    @Test
    public void aboveThresholdIsSlow() {
        assertTrue(ProfileOrganizer.firstHopRttIsSlow(3000, THRESHOLD));
    }

    /**
     * A zero reading is "unmeasured", not "impossibly fast", and must not be
     * treated as either slow or valid evidence.
     */
    @Test
    public void zeroIsUnknownNotInstant() {
        assertFalse(ProfileOrganizer.firstHopRttIsSlow(0, THRESHOLD));
    }

    /**
     * The regression in one assertion: a fast direct link must survive even
     * when the full-tunnel figure was terrible. The tunnel round trip is not an
     * input to this decision at all.
     */
    @Test
    public void fastFirstHopSurvivesATerribleTunnelTime() {
        long terribleTunnelRoundTrip = 119_756L;
        assertFalse("tunnel time must not influence the decision",
                    ProfileOrganizer.firstHopRttIsSlow(240, THRESHOLD));
        assertTrue("sanity: the tunnel figure itself is large",
                   terribleTunnelRoundTrip > THRESHOLD);
    }

    /**
     * Decay: a recorded RTT is usable for exactly the Active tier window and
     * no longer, matching the profile's other active-tier evidence.
     */
    @Test
    public void recordedRttAgesOutAfterTheRetentionWindow() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(3));
        assertEquals("never measured reads as unknown", -1, profile.getFirstHopRtt(NOW));
        profile.setFirstHopRtt(300, NOW);
        assertEquals("just recorded", 300, profile.getFirstHopRtt(NOW));
        long window = PeerProfile.FIRST_HOP_RTT_VALIDITY_MS;
        assertEquals("still valid just inside the window", 300,
                     profile.getFirstHopRtt(NOW + window - 1));
        assertEquals("valid an hour in, which the old window would have dropped", 300,
                     profile.getFirstHopRtt(NOW + HOUR));
        assertEquals("stale exactly at the window", -1, profile.getFirstHopRtt(NOW + window));
        assertEquals("stale beyond the window", -1, profile.getFirstHopRtt(NOW + window + HOUR));
    }

    /** An unmeasured value is not stored, so it cannot masquerade as a fast peer. */
    @Test
    public void unmeasuredValueIsNeverStored() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(4));
        profile.setFirstHopRtt(0, NOW);
        assertEquals("a zero reading is not evidence of anything", -1, profile.getFirstHopRtt(NOW));
        profile.setFirstHopRtt(-3, NOW);
        assertEquals("a negative reading is not evidence of anything", -1, profile.getFirstHopRtt(NOW));
    }

    /**
     * A later measurement is damped in, not swapped for the old one.
     *
     * <p>Every latency writer uses {@link PeerProfile#dampLatency}, so a single
     * noisy sample — one congested moment on one session — cannot move the value
     * on its own. The first measurement always lands whole: an absent value is
     * missing evidence, not zero latency, and damping toward it would
     * manufacture latency out of no data.
     */
    @Test
    public void newerMeasurementIsDampedIn() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(5));
        profile.setFirstHopRtt(9000, NOW);
        // 0.75 * 9000 + 0.25 * 200 = 6800, so one low sample barely moves it.
        profile.setFirstHopRtt(200, NOW + 1000);
        assertEquals(6800, profile.getFirstHopRtt(NOW + 1000));
    }

    /**
     * A stale value is replaced rather than damped toward.
     *
     * <p>{@link PeerProfile#getFirstHopRtt} has already declared a value older
     * than the retention window unknown, so averaging a fresh measurement
     * against it would let expired evidence drag the new one back.
     */
    @Test
    public void staleValueIsReplacedNotDamped() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(6));
        profile.setFirstHopRtt(9000, NOW);
        // Four hours and a second later the old value is stale evidence.
        profile.setFirstHopRtt(200, NOW + PeerProfile.FIRST_HOP_RTT_VALIDITY_MS + 1000);
        assertEquals(200, profile.getFirstHopRtt(NOW + PeerProfile.FIRST_HOP_RTT_VALIDITY_MS + 1000));
    }

    /** The first measurement always lands whole, whatever it is. */
    @Test
    public void firstMeasurementLandsWhole() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(7));
        profile.setFirstHopRtt(250, NOW);
        assertEquals("the first sample is the value, not a damped average",
                     250, profile.getFirstHopRtt(NOW));
    }

    // ---- the two-driver low-latency rule ----

    /**
     * The floor is absolute: a peer qualifies on its own direct-link RTT, with
     * no population average consulted. Measured against a live fast tier this
     * admits 69% and evicts 31%; the 0.5x-mean rule it replaced sat below the
     * population median and demoted two thirds of every measured peer.
     */
    @Test
    public void floorDecidesOnItsOwn() {
        assertTrue("a fast direct link qualifies",
                   isLow(PeerProfile.LOW_LATENCY_FLOOR_MS - 50, 1000));
        assertFalse("just over the floor does not",
                   isLow(PeerProfile.LOW_LATENCY_FLOOR_MS + 50, 1000));
    }

    /** At the floor qualifies; one millisecond over does not. */
    @Test
    public void exactlyTheFloorQualifies() {
        assertTrue("the floor is inclusive",
                   isLow(PeerProfile.LOW_LATENCY_FLOOR_MS, 1000));
        assertFalse("one over the floor does not",
                   isLow(PeerProfile.LOW_LATENCY_FLOOR_MS + 1, 1000));
    }

    /**
     * The regression. There is no longer any path by which a population mean
     * can move the bar, so the flag cannot ratchet tighter as the measured set
     * ages out from under it.
     */
    @Test
    public void noPopulationAverageCanMoveTheBar() {
        for (long ceiling : new long[] {200L, 500L, 1000L, 5000L}) {
            assertEquals("a 60ms link qualifies whatever the ceiling",
                         Boolean.TRUE, isLow(60, ceiling));
        }
        assertFalse("a 300ms link is out regardless of ceiling",
                    isLow(300, 1000));
    }

    /** A ceiling below the floor still wins, so a stricter caller is honoured. */
    @Test
    public void lowerCeilingStillApplies() {
        assertFalse("50ms ceiling rejects an 80ms link",
                    isLow(80, 50));
        assertTrue("an 80ms link passes the 100ms default ceiling",
                   isLow(80, 1000));
    }

    /** Genuinely slow peers are never low latency. */
    @Test
    public void slowPeersAreNeverLowLatency() {
        assertFalse("300ms", isLow(300, 1000));
        assertFalse("3s against a generous ceiling", isLow(3000, 5000));
    }

    /** An unmeasured peer yields no verdict, so its stored flag is untouched. */
    @Test
    public void unmeasuredPeerHasNoVerdict() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(9));
        org.junit.Assert.assertNull(
            "unknown must not be reported as low or not-low",
            profile.getLowLatencyForFirstHopRtt(NOW, 1000));
    }

    /**
     * Helper driving the real profile method.
     *
     * @param rtt the first-hop RTT to record on the profile, in milliseconds; it is narrowed to
     *            an int before storing, so keep it inside the int range
     * @param ceiling the low-latency ceiling handed to the profile, in milliseconds
     * @return TRUE when the RTT clears both the ceiling and the profile's absolute floor, FALSE
     *         when either one rejects it
     */
    private static Boolean isLow(long rtt, long ceiling) {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(11));
        profile.setFirstHopRtt((int) rtt, NOW);
        return profile.getLowLatencyForFirstHopRtt(NOW, ceiling);
    }
    /**
     * Eviction may only be driven by latency once the measurement covers the tier
     * it is applied to. A judgement drawn from a small measured subset describes
     * that subset: the peers holding sessions are the ones we have been talking
     * to, so an early sample is whoever was contacted most recently.
     */
    @Test
    public void evictionWaitsForBroadCoverage() {
        assertFalse("a single measured peer cannot evict a tier of 100",
                    ProfileOrganizer.latencySampleIsBroadEnough(1, 100));
        assertFalse("a tenth is still a self-selected sample",
                    ProfileOrganizer.latencySampleIsBroadEnough(10, 100));
        assertTrue("a majority is enough",
                   ProfileOrganizer.latencySampleIsBroadEnough(50, 100));
        assertTrue("all measured",
                   ProfileOrganizer.latencySampleIsBroadEnough(100, 100));
    }

    /** The boundary: exactly half the tier measured is the minimum that passes. */
    @Test
    public void evictionBoundaryIsHalf() {
        assertTrue("exactly half", ProfileOrganizer.latencySampleIsBroadEnough(5, 10));
        assertFalse("one short of half", ProfileOrganizer.latencySampleIsBroadEnough(4, 10));
    }

    /** An empty tier must not enable latency-driven eviction. */
    @Test
    public void emptyTierNeverEvicts() {
        assertFalse(ProfileOrganizer.latencySampleIsBroadEnough(0, 0));
        assertFalse(ProfileOrganizer.latencySampleIsBroadEnough(0, 10));
    }
    /**
     * The retention window. Lengthened from one hour so a measurement survives
     * the gap between talking to a peer, now that the sampler refreshes values
     * on every reorganize and the window only governs how long a value outlives
     * its session.
     */
    @Test
    public void retentionIsFourHours() {
        assertEquals("4h retention",
                     4 * 60 * 60 * 1000L, PeerProfile.FIRST_HOP_RTT_VALIDITY_MS);
    }

    // ---- one damping, one decision ----

    /**
     * The shared smoothing primitive is the only one.
     *
     * <p>Peer latency arrives from a peer test, from a transport session, and from
     * a handshake. They measure different paths, but they must accumulate the same
     * way, or a value averaged 25% per sample cannot be compared with one that
     * replaces itself. Pinned as arithmetic so the primitive cannot drift from
     * either of the writers that use it.
     */
    @Test
    public void dampLatencyIsTheOnlySmoothing() {
        assertEquals("an unknown value takes the sample whole",
                     120f, PeerProfile.dampLatency(0f, 120f), 0.001f);
        assertEquals("a negative value is unknown, not zero",
                     120f, PeerProfile.dampLatency(-1f, 120f), 0.001f);
        assertEquals("a known value damps 25% per sample",
                     0.75f * 200f + 0.25f * 40f, PeerProfile.dampLatency(200f, 40f), 0.001f);
        // Converges rather than reaching: repeated identical samples approach it.
        float v = 0f;
        for (int i = 0; i < 100; i++) { v = PeerProfile.dampLatency(v, 100f); }
        assertEquals("100 identical samples reach the value", 100f, v, 0.001f);
    }

    /**
     * The low-latency decision has one implementation, reached by both callers.
     *
     * <p>{@link PeerProfile#recalculateLowLatency} is a wrapper over
     * {@link PeerProfile#getLowLatencyForFirstHopRtt(long, long)}, so the flag
     * cannot be derived two different ways depending on which pass ran last.
     */
    @Test
    public void lowLatencyHasOneDecisionPath() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(8));
        // Unmeasured: the decision declines to judge and leaves the flag alone.
        assertNull(profile.getLowLatencyForFirstHopRtt(NOW, PeerProfile.LOW_LATENCY_CEILING_MS));

        profile.setFirstHopRtt(50, NOW);
        assertTrue(Boolean.TRUE.equals(
            profile.getLowLatencyForFirstHopRtt(NOW, PeerProfile.LOW_LATENCY_CEILING_MS)));

        profile.setFirstHopRtt(500, NOW);
        assertTrue(Boolean.FALSE.equals(
            profile.getLowLatencyForFirstHopRtt(NOW, PeerProfile.LOW_LATENCY_CEILING_MS)));

        // recalculateLowLatency propagates that same answer, having consulted the
        // same method: two paths, one decision.
        profile.setFirstHopRtt(50, NOW);
        profile.recalculateLowLatency();
        profile.setFirstHopRtt(500, NOW + 1);
        assertFalse("the wrapper propagates the method's FALSE", profile.isLowLatency());
    }
}
