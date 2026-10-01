package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
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

    /** router.peerTestTimeout default; the threshold is twice this. */
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
     *  Unknown is not slow. A peer we have not measured must never be evicted,
     *  or the tier drains of exactly the peers we have not probed yet.
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
     *  A zero reading is "unmeasured", not "impossibly fast", and must not be
     *  treated as either slow or valid evidence.
     */
    @Test
    public void zeroIsUnknownNotInstant() {
        assertFalse(ProfileOrganizer.firstHopRttIsSlow(0, THRESHOLD));
    }

    /**
     *  The regression in one assertion: a fast direct link must survive even
     *  when the full-tunnel figure was terrible. The tunnel round trip is not an
     *  input to this decision at all.
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
     *  Decay: a recorded RTT is usable for exactly the Active tier window and
     *  no longer, matching the profile's other active-tier evidence.
     */
    @Test
    public void recordedRttAgesOutAfterTheActiveWindow() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(3));
        assertEquals("never measured reads as unknown", -1, profile.getFirstHopRtt(NOW));
        profile.setFirstHopRtt(300, NOW);
        assertEquals("just recorded", 300, profile.getFirstHopRtt(NOW));
        assertEquals("still valid just inside the window", 300,
                     profile.getFirstHopRtt(NOW + HOUR - 1));
        assertEquals("stale exactly at the window", -1, profile.getFirstHopRtt(NOW + HOUR));
        assertEquals("stale beyond the window", -1, profile.getFirstHopRtt(NOW + 10 * HOUR));
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

    /** A later measurement replaces the previous one rather than averaging it in. */
    @Test
    public void newerMeasurementReplacesOlder() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(5));
        profile.setFirstHopRtt(9000, NOW);
        profile.setFirstHopRtt(200, NOW + 1000);
        assertEquals(200, profile.getFirstHopRtt(NOW + 1000));
    }

    // ---- the two-driver low-latency rule ----

    /**
     *  Both drivers, stated as the rule they implement: under the absolute
     *  ceiling, and at most half the sliding average once that average is
     *  measured over enough peers.
     */
    @Test
    public void lowLatencyNeedsBothDrivers() {
        long avg = 1200;
        long ceiling = 1000;
        assertTrue("well under both", isLow(200, avg, ceiling));
        assertFalse("under the average but over the absolute ceiling", isLow(1100, avg, ceiling));
        assertFalse("under the ceiling but only 60% of the average", isLow(700, avg, ceiling));
    }

    /** Exactly half the average qualifies; the rule is half or less. */
    @Test
    public void exactlyHalfTheAverageQualifies() {
        assertTrue("half of 1000 is 500", isLow(500, 1000, 1000));
        assertFalse("just over half does not", isLow(501, 1000, 1000));
    }

    /**
     *  The user's case: 3s must never be low latency, whatever the average
     *  says. Guards the absolute driver against being swallowed by the relative
     *  one on a slow network.
     */
    @Test
    public void threeSecondsIsNeverLowLatency() {
        assertFalse("3s against a slow average", isLow(3000, 6000, 1000));
        assertFalse("3s against a slow average, generous ceiling", isLow(3000, 5000, 5000));
    }

    /**
     *  Before enough peers are measured the caller passes 0 for the average,
     *  which disables the relative driver and leaves the absolute ceiling.
     */
    @Test
    public void withoutAnAverageOnlyTheAbsoluteCeilingApplies() {
        assertTrue("500ms is under the ceiling", isLow(500, 0, 1000));
        assertFalse("1100ms is over it", isLow(1100, 0, 1000));
    }

    /** An unmeasured peer yields no verdict, so its stored flag is untouched. */
    @Test
    public void unmeasuredPeerHasNoVerdict() {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(9));
        org.junit.Assert.assertNull(
            "unknown must not be reported as low or not-low",
            profile.getLowLatencyForFirstHopRtt(NOW, 1000, 1000));
    }

    /** The gate that keeps the average from steering a thin population. */
    @Test
    public void gateHoldsOffThinEvidence() {
        org.junit.Assert.assertTrue(
            "a mean over a handful of probes must not set the bar for the tier",
            ProfileOrganizer.MIN_MEASURED_PEERS >= 100);
    }

    /** Helper driving the real profile method. */
    private static Boolean isLow(int rtt, long average, long ceiling) {
        RouterContext ctx = RouterTestHelper.getContext();
        org.junit.Assume.assumeTrue("no RouterContext available", ctx != null);
        PeerProfile profile = new PeerProfile(ctx, hash(11));
        profile.setFirstHopRtt(rtt, NOW);
        return profile.getLowLatencyForFirstHopRtt(NOW, average, ceiling);
    }
}
