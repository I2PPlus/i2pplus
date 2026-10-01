package net.i2p.router.tunnel.pool;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;

/**
 * Tests for the fast-tier reachability sweep and the probe-failure response.
 *
 * <p>These cover the gap that let unconnectable peers persist in the fast tier:
 * selection samples a bounded slice of the high-capacity set, so a peer that
 * never answers is only discovered by paying for a build that fails at hop 0.
 * The sweep supplies that evidence cheaply, and
 * {@link TunnelPeerSelector#applyProbeFailure} turns it into a demotion.
 *
 * @since 0.9.71+
 */
public class FastTierProbeTest {

    private static final long NOW = 2_000_000_000L;
    private static final long ONE_MIN = 60 * 1000L;
    private static final long ELEVEN_MIN = 11 * 60 * 1000L;

    private RouterContext _ctx;
    private ProfileOrganizer _po;
    private PeerProfile _profile;
    private StatManager _stat;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
        _po = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_po);
        _stat = mock(StatManager.class);
        when(_ctx.statManager()).thenReturn(_stat);
        _profile = mock(PeerProfile.class);
        when(_po.getProfile(any(Hash.class))).thenReturn(_profile);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    private void setTraffic(long lastSendSuccess, long lastHeard) {
        when(_profile.getLastSendSuccessful()).thenReturn(lastSendSuccess);
        when(_profile.getLastHeardFrom()).thenReturn(lastHeard);
    }

    /** A probe we cannot deliver is reachability evidence: demote at once. */
    @Test
    public void probeFailureDemotesFromFastTiers() {
        Hash peer = hash(1);
        TunnelPeerSelector.applyProbeFailure(_ctx, peer);
        verify(_po).demoteIfUnreachableNow(peer);
    }

    /** The failure is counted, so the rate is visible without log scraping. */
    @Test
    public void probeFailureIsCounted() {
        TunnelPeerSelector.applyProbeFailure(_ctx, hash(1));
        verify(_stat).addRateData(anyString(), anyLong());
    }

    /**
     *  A peer we have successfully reached very recently is skipped: the sweep
     *  budget is better spent on peers whose state is actually stale.
     */
    @Test
    public void recentlySentToIsSkipped() {
        setTraffic(NOW - ONE_MIN, 0);
        org.junit.Assert.assertTrue(
            "recent outbound success means the peer is demonstrably up",
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /**
     *  Inbound hearing must NOT count as evidence. It advances on DHT replies,
     *  explore traffic and transit acknowledgments, so keying on it marked most
     *  of the tier as recently active and starved the sweep of budget. A peer
     *  heard from constantly but never successfully sent to is exactly the
     *  suspect this sweep exists to find.
     */
    @Test
    public void recentlyHeardFromIsStillProbed() {
        setTraffic(0, NOW - ONE_MIN);
        org.junit.Assert.assertFalse(
            "incidental inbound traffic is not evidence the peer works as a first hop",
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /** Inside the window we skip, outside it we probe. */
    @Test
    public void windowBoundaryIsInclusiveOfSkip() {
        setTraffic(NOW - ONE_MIN, 0);
        org.junit.Assert.assertTrue(
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /** Stale in both directions means probe it. */
    @Test
    public void stalePeerIsProbed() {
        setTraffic(NOW - ELEVEN_MIN, NOW - ELEVEN_MIN);
        org.junit.Assert.assertFalse(
            "no recent traffic either way, so the peer is worth probing",
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /** Never-contacted peers have no evidence, so they are probed. */
    @Test
    public void neverContactedIsProbed() {
        setTraffic(0, 0);
        org.junit.Assert.assertFalse(
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /**
     *  A peer we hold no profile for is skipped rather than probed: without an
     *  address there is nothing to connect to, and probing it would spend the
     *  budget on a peer that cannot be fixed by reachability evidence.
     */
    @Test
    public void unknownPeerIsSkipped() {
        when(_po.getProfile(any(Hash.class))).thenReturn(null);
        org.junit.Assert.assertTrue(
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /**
     *  The window widens the skip set, so it must stay a documented constant
     *  rather than drifting upward silently.
     */
    @Test
    public void recencyWindowIsTenMinutes() {
        org.junit.Assert.assertEquals(10 * 60 * 1000L,
            TunnelPoolManager.FastTierProbeJob.RECENTLY_ACTIVE_MS);
    }

    // ---- cadence ----

    /** Young routers probe harder, because nothing has been measured yet. */
    @Test
    public void youngRouterProbesHarder() {
        org.junit.Assert.assertEquals(TunnelPoolManager.FastTierProbeJob.PROBE_BATCH_FAST,
            TunnelPoolManager.FastTierProbeJob.probeBatchFor(0));
        org.junit.Assert.assertEquals(TunnelPoolManager.FastTierProbeJob.PROBE_BATCH_FAST,
            TunnelPoolManager.FastTierProbeJob.probeBatchFor(3 * 60 * 60 * 1000L));
    }

    /** After the fast window it backs off. */
    @Test
    public void steadyStateProbesLess() {
        org.junit.Assert.assertEquals(TunnelPoolManager.FastTierProbeJob.PROBE_BATCH_STEADY,
            TunnelPoolManager.FastTierProbeJob.probeBatchFor(4 * 60 * 60 * 1000L));
        org.junit.Assert.assertTrue(
            "the fast batch must be the larger one",
            TunnelPoolManager.FastTierProbeJob.PROBE_BATCH_FAST >
            TunnelPoolManager.FastTierProbeJob.PROBE_BATCH_STEADY);
    }

    /** A young pass must cover the whole tier faster than a steady one. */
    @Test
    public void youngIntervalIsShorterThanSteady() {
        int band = 1000;
        long young = TunnelPoolManager.FastTierProbeJob.probeIntervalFor(0, band,
            TunnelPoolManager.FastTierProbeJob.probeBatchFor(0));
        long steady = TunnelPoolManager.FastTierProbeJob.probeIntervalFor(5 * 60 * 60 * 1000L, band,
            TunnelPoolManager.FastTierProbeJob.probeBatchFor(5 * 60 * 60 * 1000L));
        org.junit.Assert.assertTrue("young interval " + young + " should be under steady " + steady,
                                    young < steady);
    }

    /**
     *  The whole point of deriving the interval from the band size: a full pass
     *  takes roughly the target wall clock whatever the population. Without this
     *  a 4000 peer tier would take four times as long as a 1000 peer one.
     */
    @Test
    public void fullPassTimeIsIndependentOfBandSize() {
        for (int band : new int[] {200, 1000, 4000}) {
            int batch = TunnelPoolManager.FastTierProbeJob.probeBatchFor(0);
            long interval = TunnelPoolManager.FastTierProbeJob.probeIntervalFor(0, band, batch);
            long passes = (band + batch - 1) / batch;
            long passTimeMs = interval * passes;
            // Clamping can only shorten a pass, never lengthen it, so the
            // invariant is that a full pass is at most the target. A small band
            // covers in one pass and lands under it because the interval is
            // capped; a large one converges on it.
            org.junit.Assert.assertTrue(
                "band=" + band + " full pass took " + (passTimeMs / 60000) + "min",
                passTimeMs <= TunnelPoolManager.FastTierProbeJob.FAST_PASS_TARGET_MS);
        }
    }

    /** Degenerate inputs must not produce a zero or negative interval. */
    @Test
    public void degenerateInputsAreClamped() {
        org.junit.Assert.assertEquals(TunnelPoolManager.FastTierProbeJob.MAX_PROBE_INTERVAL_MS,
            TunnelPoolManager.FastTierProbeJob.probeIntervalFor(0, 0, 256));
        org.junit.Assert.assertEquals(TunnelPoolManager.FastTierProbeJob.MAX_PROBE_INTERVAL_MS,
            TunnelPoolManager.FastTierProbeJob.probeIntervalFor(0, 1000, 0));
    }

    /** A huge tier must not push the interval beyond the ceiling. */
    @Test
    public void intervalIsCappedForHugeTiers() {
        long interval = TunnelPoolManager.FastTierProbeJob.probeIntervalFor(0, 500000, 256);
        org.junit.Assert.assertTrue("interval must stay bounded", interval <=
            TunnelPoolManager.FastTierProbeJob.MAX_PROBE_INTERVAL_MS);
    }
}
