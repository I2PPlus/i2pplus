package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;

/**
 * Tests for TunnelPeerSelector.isStalePeer: unprofiled (never-heard-of) peers
 * must NOT be considered stale — they are potential newcomers during pool
 * recovery. Profiled peers are always checked against the activity window
 * regardless of uptime; the startup grace no longer exempts stale profiled peers.
 *
 * @since 0.9.71+
 */
public class TunnelPeerSelectorStalePeerTest {

    private static final long NOW = 2_000_000_000L;
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    private RouterContext _ctx;
    private Router _router;
    private ProfileOrganizer _organizer;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.routerHash()).thenReturn(hash(9));

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        // getActivityWindow scales on active-peer count (0 -> 8 hour window)
        CommSystemFacade cs = mock(CommSystemFacade.class);
        when(cs.countActivePeers()).thenReturn(0);
        when(_ctx.commSystem()).thenReturn(cs);

        _router = mock(Router.class);
        when(_ctx.router()).thenReturn(_router);

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    /**
     * A profile last heard from/about long ago (outside the 8h window).
     *
     * @return a mocked PeerProfile with both last-heard times at 0, so it reads as
     *         never heard of
     */
    private static PeerProfile oldProfile() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(0L);
        when(p.getLastHeardAbout()).thenReturn(0L);
        return p;
    }

    /**
     * A profile heard from within the activity window.
     *
     * @return a mocked PeerProfile heard from 1 second ago and heard about 2
     *         seconds ago, relative to the test clock
     */
    private static PeerProfile freshProfile() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(NOW - 1000L);
        when(p.getLastHeardAbout()).thenReturn(NOW - 2000L);
        return p;
    }

    @Test
    public void testUnprofiledPeerNotStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(null);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    @Test
    public void testProfiledActivePeerNotStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        PeerProfile p = freshProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    @Test
    public void testProfiledInactivePeerStale() {
        when(_router.getUptime()).thenReturn(ONE_HOUR);
        PeerProfile p = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    /**
     * A zero-length window makes even a freshly-heard peer stale, whatever the
     * active-peer count the recomputing form would have derived. The keepalive
     * and first-hop loops hand one computed window to the per-peer check.
     */
    @Test
    public void testWindowOverloadUsesCallerWindowNotRecomputed() {
        PeerProfile p = freshProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0L));
    }

    /**
     * The same profile that is stale under the 8h ladder window is fresh under
     * a year-long one: the window handed in decides, not the ladder.
     */
    @Test
    public void testWindowOverloadLargeWindowNotStale() {
        PeerProfile p = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        long yearMs = 365L * 24 * 60 * 60 * 1000L;
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), yearMs));
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), ONE_HOUR));
    }

    /**
     * A peer with no profile is never stale, whatever the window: excluding
     * newcomers would starve the pool during recovery.
     */
    @Test
    public void testWindowOverloadUnprofiledNotStale() {
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(null);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0L));
    }

    /**
     * The cutoff is now minus the window and the comparison is &lt;, so a peer
     * heard from exactly on the boundary is still fresh and one ms older is
     * stale.
     */
    @Test
    public void testWindowBoundaryOnlyStrictlyOlderIsStale() {
        PeerProfile p = mock(PeerProfile.class);
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        when(p.getLastHeardFrom()).thenReturn(NOW - ONE_HOUR);
        when(p.getLastHeardAbout()).thenReturn(NOW - ONE_HOUR);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), ONE_HOUR));
        when(p.getLastHeardFrom()).thenReturn(NOW - ONE_HOUR - 1);
        when(p.getLastHeardAbout()).thenReturn(NOW - ONE_HOUR - 1);
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), ONE_HOUR));
    }

    /**
     * Staleness needs both contacts to have aged out — a peer we keep hearing
     * about from other routers is still a usable first hop.
     */
    @Test
    public void testRecentHeardAboutRescuesAgedHeardFrom() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(0L);
        when(p.getLastHeardAbout()).thenReturn(NOW - 1000L);
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), ONE_HOUR));
    }

    @Test
    public void testStartupGraceDoesNotSuppressStaleProfiled() {
        // 10 minutes uptime: inside the 15-minute startup grace
        // Profiled peers with old timestamps are STILL stale during
        // startup — the startup grace no longer exempts profiled peers.
        when(_router.getUptime()).thenReturn(10 * 60 * 1000L);
        PeerProfile p = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(p);
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), 0.5));
    }

    /**
     * The no-window overload is a convenience wrapper: it fetches the ladder
     * window once and hands it to the three-argument form. Pinning that both
     * forms agree, and that a peer heard from a second ago is fresh through
     * the wrapper, keeps the wrapper from quietly checking against some other
     * window - the per-peer recomputation this overload used to do is what
     * made one keepalive pass walk the peer tiers per peer.
     */
    @Test
    public void testNoWindowOverloadUsesTheComputedWindow() {
        long window = TunnelPeerSelector.getActivityWindow(_ctx);
        assertTrue("the stubbed context reports no active peers, so the ladder " +
                   "gives the widest window, was " + window + "ms",
                   window > ONE_HOUR);
        PeerProfile fresh = freshProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(fresh);
        assertFalse("a peer heard from a second ago is fresh through the wrapper",
                    TunnelPeerSelector.isStalePeer(_ctx, hash(1)));
        assertFalse(TunnelPeerSelector.isStalePeer(_ctx, hash(1), window));
        PeerProfile aged = oldProfile();
        when(_organizer.getProfileNonblocking(any(Hash.class))).thenReturn(aged);
        assertTrue("an aged profile is stale through the wrapper too",
                   TunnelPeerSelector.isStalePeer(_ctx, hash(1)));
        assertTrue(TunnelPeerSelector.isStalePeer(_ctx, hash(1), window));
    }
}
