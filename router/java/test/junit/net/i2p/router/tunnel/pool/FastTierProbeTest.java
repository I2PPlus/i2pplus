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

    /** The same holds for inbound: hearing from them proves reachability too. */
    @Test
    public void recentlyHeardFromIsSkipped() {
        setTraffic(0, NOW - ONE_MIN);
        org.junit.Assert.assertTrue(
            "recent inbound traffic is equally good evidence",
            TunnelPoolManager.FastTierProbeJob.isRecentlyActive(_ctx, hash(1), NOW));
    }

    /** Stale in both directions means probe it. */
    @Test
    public void stalePeerIsProbed() {
        setTraffic(NOW - 60 * ONE_MIN, NOW - 60 * ONE_MIN);
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

    /** The batch cap is what bounds a pass; it must stay a small slice. */
    @Test
    public void batchIsBounded() {
        org.junit.Assert.assertTrue(
            "a pass must not probe the whole tier at once",
            TunnelPoolManager.FastTierProbeJob.PROBE_BATCH <= 256);
    }

    /** Sweep and pause: the interval must be long enough to be a background cost. */
    @Test
    public void intervalIsLongEnoughToBeBackground() {
        org.junit.Assert.assertTrue(
            "probing every pass must not approach per-build frequency",
            TunnelPoolManager.FastTierProbeJob.PROBE_INTERVAL >= 5 * 60 * 1000L);
    }
}
